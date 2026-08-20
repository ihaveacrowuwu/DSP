package mv.muraka.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue's behaviour, against real SQLite and real Room codegen — which is where the
 * bugs these guard against would actually live.
 */
@RunWith(AndroidJUnit4::class)
class OutboxDaoTest {

    private lateinit var database: MurakaDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MurakaDatabase::class.java,
        ).build()
        dao = database.outboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun enqueuesASightingWithItsPhotographsAtomically() = runTest {
        dao.enqueue(sighting("s1"), listOf(photo("p1", "s1", 0), photo("p2", "s1", 1)))

        assertEquals(2, dao.photosFor("s1").size)
        assertEquals(1, dao.observeQueue(USER).first().size)
    }

    @Test
    fun photographs_come_back_in_capture_order() = runTest {
        // The researcher's queue has to reflect the order things were photographed, so
        // ordinal — not insertion order or id — governs.
        dao.enqueue(sighting("s1"), listOf(photo("p3", "s1", 2), photo("p1", "s1", 0), photo("p2", "s1", 1)))
        assertEquals(listOf("p1", "p2", "p3"), dao.photosFor("s1").map { it.id })
    }

    @Test
    fun theQueueNeverOffersAnotherContributorsRows() = runTest {
        // Two divers, one phone. Uploading A's sighting under B's session attributes reef
        // data to the wrong person — corrupt science, and an ethics problem.
        dao.enqueue(sighting("mine", userId = USER), emptyList())
        dao.enqueue(sighting("theirs", userId = "other-diver"), emptyList())

        val due = dao.dueForSync(USER, now = Long.MAX_VALUE)
        assertEquals(listOf("mine"), due.map { it.id })
        assertEquals(1, dao.observePendingCount(USER).first())
    }

    @Test
    fun backoffKeepsARowOutOfTheQueueUntilItsTimeComes() = runTest {
        dao.enqueue(sighting("s1"), emptyList())
        dao.recordSightingAttempt("s1", state = "queued", error = "timeout", nextAttemptAt = 5_000)

        assertTrue("must not be offered before its backoff expires", dao.dueForSync(USER, now = 4_999).isEmpty())
        assertEquals(1, dao.dueForSync(USER, now = 5_000).size)
    }

    @Test
    fun theAttemptCounterOnlyEverIncreases() = runTest {
        // A row that resets its own counter retries forever, and a contributor never finds
        // out something is stuck.
        dao.enqueue(sighting("s1"), emptyList())
        repeat(3) { dao.recordSightingAttempt("s1", "queued", "timeout", null) }
        assertEquals(3, dao.sighting("s1")?.attempts)
    }

    @Test
    fun requeueingClearsTheFailureSoAContributorsRetryActuallyRetries() = runTest {
        dao.enqueue(sighting("s1"), emptyList())
        dao.recordSightingAttempt("s1", "failed", "413 too large", 9_999)

        dao.requeue("s1")

        val row = dao.sighting("s1")
        assertEquals("queued", row?.state)
        assertEquals(0, row?.attempts)
        assertNull(row?.lastError)
        assertNull(row?.nextAttemptAt)
    }

    @Test
    fun deletingASightingTakesItsPhotographsWithIt() = runTest {
        dao.enqueue(sighting("s1"), listOf(photo("p1", "s1", 0)))
        dao.delete("s1")
        assertTrue(dao.photosFor("s1").isEmpty())
    }

    @Test
    fun aConfirmedRowLeavesTheQueueButIsNotCountedAsPending() = runTest {
        dao.enqueue(sighting("s1"), emptyList())
        dao.setSightingState("s1", "confirmed")

        assertTrue(dao.observeQueue(USER).first().isEmpty())
        assertEquals(0, dao.observePendingCount(USER).first())
    }

    @Test
    fun aFailedRowStaysVisibleBecauseItNeedsTheContributor() = runTest {
        dao.enqueue(sighting("s1"), emptyList())
        dao.recordSightingAttempt("s1", "failed", "422 capturedAt", null)

        assertEquals(1, dao.observeQueue(USER).first().size)
        assertEquals(1, dao.observePendingCount(USER).first())
        // ...but it is not offered to the sender again without an explicit retry.
        assertTrue(dao.dueForSync(USER, now = Long.MAX_VALUE).isEmpty())
    }

    private fun sighting(id: String, userId: String = USER) = SightingQueueEntity(
        id = id,
        userId = userId,
        lat = 4.1755,
        lon = 73.5093,
        locationSource = "gps",
        capturedAtDevice = 1_760_000_000_000,
        state = "queued",
        createdAt = 1_760_000_000_000,
    )

    private fun photo(id: String, sightingId: String, ordinal: Int) = PhotoQueueEntity(
        id = id,
        sightingId = sightingId,
        localPath = "/data/photos/$id.jpg",
        ordinal = ordinal,
        state = "queued",
    )

    private companion object {
        const val USER = "diver-a"
    }
}
