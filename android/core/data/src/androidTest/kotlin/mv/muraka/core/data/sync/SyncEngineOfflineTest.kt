package mv.muraka.core.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.common.ServerClock
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.database.MurakaDatabase
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity
import mv.muraka.core.datastore.KeystoreCipher
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.datastore.StoredSession
import mv.muraka.core.model.OutboxState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The offline half of the acceptance checklist in `mobile-shared/README.md`.
 *
 * Those items were the app's stated definition of done and had never been walked,
 * because each one describes a *situation* rather than a function: no network, a
 * force-quit, a connection dropped halfway through an upload. The instinct is to test
 * them by hand with a device in aeroplane mode, which is worth doing once and is
 * useless as a regression guard — nobody re-walks it after every change.
 *
 * So the situations are constructed instead. Real Room, real files, real
 * Keystore-encrypted session store; only the server is a fake, and it keeps genuine
 * state rather than expectations (see [FakeMurakaApi]).
 *
 * The one thing these cannot show is that the *capture UI* works with no network. That
 * is a screen, and it is recorded in `docs/evidence/mobile/acceptance.md` by hand.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineOfflineTest {

    private lateinit var context: Context
    private lateinit var database: MurakaDatabase
    private lateinit var api: FakeMurakaApi
    private lateinit var tokens: SessionTokenStore
    private lateinit var photos: PhotoStore
    private lateinit var engine: SyncEngineImpl

    private val userId = "11111111-1111-7111-8111-111111111111"

    private object TestDispatchers : DispatcherProvider {
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val default: CoroutineDispatcher get() = Dispatchers.Default
        override val main: CoroutineDispatcher get() = Dispatchers.Main
    }

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MurakaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Asserted rather than assumed: the engine relies on ON DELETE CASCADE from
        // sighting_queue to photo_queue, and a test that ran without enforcement could
        // pass while orphaning photo rows.
        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            cursor.moveToFirst()
            check(cursor.getInt(0) == 1) { "foreign keys are not enforced in this test database" }
        }

        api = FakeMurakaApi()
        tokens = SessionTokenStore(context, KeystoreCipher())
        tokens.save(
            StoredSession(
                accessToken = "test-access",
                refreshToken = "test-refresh",
                expiresAtEpochMs = System.currentTimeMillis() + 900_000,
                userId = userId,
            ),
        )
        // A precondition, not a test: the engine returns an empty outcome and touches
        // nothing when there is no session, so a session that failed to store would make
        // every test below pass or fail for a reason that has nothing to do with what it
        // is testing.
        val session = tokens.current()
        check(session != null) { "the test session did not store — SessionTokenStore returned null" }
        check(session.userId == userId) { "stored session belongs to ${session.userId}" }

        photos = PhotoStore(context, TestDispatchers)
        engine = newEngine()
    }

    @After
    fun tearDown() = runTest {
        tokens.clear()
        database.close()
    }

    /**
     * A fresh engine over the *same* database and the same files. This is how a
     * force-quit is expressed: the process is gone, so every in-memory field is gone,
     * and only what reached the disk survives.
     */
    private fun newEngine() = SyncEngineImpl(
        api = api,
        outbox = database.outboxDao(),
        cache = database.cacheDao(),
        photos = photos,
        tokens = tokens,
        serverClock = ServerClock(),
        json = Json { ignoreUnknownKeys = true },
        dispatchers = TestDispatchers,
    )

    /**
     * Brings a backed-off row forward so it is due again, standing in for the wait.
     *
     * A retryable failure records an attempt and sets `next_attempt_at` to
     * `min(2^attempts, 300)s` ahead, which is correct and is why a second drain
     * immediately afterwards finds nothing due — the first version of these tests read
     * that as "the engine failed to resume". The curve itself is covered by
     * `RetryPolicyTest`; these tests are about reconciliation, so they skip the clock
     * rather than sleeping through it.
     */
    private suspend fun waitOutTheBackoff() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sighting_queue SET next_attempt_at = 0 WHERE user_id = ?",
            arrayOf(userId),
        )
        database.openHelper.writableDatabase.execSQL("UPDATE photo_queue SET state = 'queued' WHERE state = 'sending'")
    }

    /** Queues a sighting with [photoCount] real files on disk, as capture does. */
    private suspend fun enqueue(photoCount: Int = 1): Pair<String, List<String>> {
        val sightingId = UUID.randomUUID().toString()
        val photoIds = (0 until photoCount).map { UUID.randomUUID().toString() }
        val rows = photoIds.mapIndexed { index, photoId ->
            // Real bytes: the engine reads the file to build the multipart body, so an
            // empty or absent file would fail for the wrong reason.
            val file = photos.fileFor(photoId).apply { writeBytes(ByteArray(2048) { 0x42 }) }
            PhotoQueueEntity(
                id = photoId,
                sightingId = sightingId,
                localPath = file.absolutePath,
                ordinal = index,
                state = OutboxState.QUEUED.wire,
            )
        }
        database.outboxDao().enqueue(
            SightingQueueEntity(
                id = sightingId,
                userId = userId,
                lat = 4.17,
                lon = 73.51,
                locationSource = "gps",
                capturedAtDevice = System.currentTimeMillis() - 3_600_000,
                state = OutboxState.QUEUED.wire,
                createdAt = System.currentTimeMillis(),
            ),
            rows,
        )
        return sightingId to photoIds
    }

    // ── "Capture completes with the device in airplane mode" ─────────────────

    @Test
    fun capturingWithNoNetworkQueuesTheSightingAndKeepsThePhotograph() = runTest {
        api.offline = true

        val (sightingId, photoIds) = enqueue(photoCount = 2)

        // Enqueue never touches the network, which is the requirement (NFR7): the write
        // lands locally and a worker uploads later.
        assertEquals(0, api.calls.size)
        assertNotNull(database.outboxDao().sighting(sightingId))
        assertEquals(2, database.outboxDao().photosFor(sightingId).size)
        photoIds.forEach { assertTrue(photos.fileFor(it).exists()) }
    }

    @Test
    fun drainingWithNoNetworkLeavesTheRowQueuedAndDoesNotBurnItsAttempts() = runTest {
        api.offline = true
        val (sightingId, _) = enqueue()

        val due = database.outboxDao().dueForSync(userId, System.currentTimeMillis())
        assertEquals("the row should be due for sync: $due", 1, due.size)

        val outcome = engine.drain()

        assertTrue("the engine should report itself offline", outcome.offline)
        val row = database.outboxDao().sighting(sightingId)!!
        assertEquals(OutboxState.QUEUED.wire, row.state)
        // The attempt counter drives exponential backoff. Burning it against a network
        // that is not there would push a sighting into a long wait — or into the
        // give-up state after eight — for a reason that was never its fault.
        assertEquals(0, row.attempts)
    }

    // ── "Queued sightings survive a force-quit and a device restart" ─────────

    @Test
    fun theQueueSurvivesTheProcessDying() = runTest {
        api.offline = true
        val (sightingId, photoIds) = enqueue(photoCount = 2)
        engine.drain()

        // The process dies: a brand-new engine, nothing carried over in memory.
        engine = newEngine()

        val row = database.outboxDao().sighting(sightingId)
        assertNotNull("the sighting did not survive the restart", row)
        assertEquals(2, database.outboxDao().photosFor(sightingId).size)
        photoIds.forEach {
            assertTrue("photo ${it.take(8)} was lost", photos.fileFor(it).exists())
        }

        // And it is still owed to the server, not quietly marked done.
        assertTrue(database.outboxDao().dueForSync(userId, System.currentTimeMillis()).any { it.id == sightingId })
    }

    // ── "Sync resumes automatically when connectivity returns" ──────────────

    @Test
    fun theQueueDrainsOnceTheNetworkReturns() = runTest {
        api.offline = true
        val (sightingId, photoIds) = enqueue(photoCount = 2)
        assertTrue(engine.drain().offline)
        assertEquals(0, api.stored.size)

        api.offline = false
        val outcome = engine.drain()

        assertEquals(1, outcome.sightingsConfirmed)
        assertEquals(2, outcome.photosUploaded)
        assertEquals(photoIds.toSet(), api.stored[sightingId])
        // Confirmed by the server, so nothing local is still owed.
        assertNull(database.outboxDao().sighting(sightingId))
        photoIds.forEach {
            assertTrue("the photo file should be cleaned up once confirmed", !photos.fileFor(it).exists())
        }
    }

    // ── "Killing the app mid-upload does not duplicate or lose" ─────────────

    @Test
    fun aConnectionLostMidUploadResumesWithoutResendingWhatArrived() = runTest {
        val (sightingId, photoIds) = enqueue(photoCount = 3)

        // The first photograph lands; the connection drops during the second.
        api.onPhotoUpload = { index ->
            if (index == 2) throw java.io.IOException("connection reset mid-upload (fake)")
        }
        engine.drain()
        api.onPhotoUpload = null

        val landed = api.stored[sightingId]?.size ?: 0
        assertEquals("exactly one photograph should have arrived before the break", 1, landed)

        // The process dies and comes back: a new engine over the same disk.
        engine = newEngine()
        waitOutTheBackoff()
        val uploadsBefore = api.uploadsOf(sightingId)

        val outcome = engine.drain()

        assertEquals(1, outcome.sightingsConfirmed)
        // Every photograph present exactly once. The ids are the client's own, so a
        // duplicate upload could not create a duplicate row even if it happened.
        assertEquals(photoIds.toSet(), api.stored[sightingId])

        // And what already arrived was not sent again. The engine asks the server what
        // it holds before sending anything, so the second pass carries only the
        // difference — this is what separates "safe because the ids are idempotent"
        // from "actually reconciled", and only the second saves a diver's tethering.
        assertEquals(
            "only the two missing photographs should have been re-sent",
            2,
            api.uploadsOf(sightingId) - uploadsBefore,
        )
    }

    // ── "Submitting the same sighting twice creates exactly one record" ─────

    @Test
    fun drainingTwiceCreatesOneRecordAndUploadsNothingTheSecondTime() = runTest {
        val (sightingId, photoIds) = enqueue(photoCount = 2)

        engine.drain()
        val callsAfterFirst = api.calls.size
        engine.drain()

        assertEquals(photoIds.toSet(), api.stored[sightingId])
        assertEquals(1, api.stored.keys.count { it == sightingId })
        // The row is gone after the first drain, so the second has nothing to do at all.
        assertEquals(callsAfterFirst, api.calls.size)
    }

    // ── Nothing is deleted on a response the client could not trust ─────────

    @Test
    fun aServerErrorOnTheReadBackLeavesTheRowInTheQueue() = runTest {
        val (sightingId, photoIds) = enqueue(photoCount = 1)

        // The uploads succeed, then the confirming read fails with a server error. The
        // engine must not delete anything: it does not know what the server holds.
        api.forceSightingStatus = 500
        engine.drain()

        assertNotNull(
            "a row must never be deleted on the strength of an unverified upload",
            database.outboxDao().sighting(sightingId),
        )
        photoIds.forEach { assertTrue(photos.fileFor(it).exists()) }

        // With the server answering again, the same row completes.
        api.forceSightingStatus = null
        waitOutTheBackoff()
        val outcome = engine.drain()
        assertEquals(1, outcome.sightingsConfirmed)
        assertNull(database.outboxDao().sighting(sightingId))
    }

    // ── No session means no uploads, and no data loss either ───────────────

    @Test
    fun signingOutLeavesTheQueueUntouchedRatherThanUploadingOrDiscardingIt() = runTest {
        val (sightingId, photoIds) = enqueue(photoCount = 2)
        tokens.clear()

        val outcome = engine.drain()

        assertEquals(0, outcome.sightingsConfirmed)
        assertEquals(0, api.calls.size)
        // "Expired refresh token returns the user to sign-in without losing the queue":
        // the rows belong to their owner and wait for that account to come back.
        assertNotNull(database.outboxDao().sighting(sightingId))
        photoIds.forEach { assertTrue(photos.fileFor(it).exists()) }
    }
}
