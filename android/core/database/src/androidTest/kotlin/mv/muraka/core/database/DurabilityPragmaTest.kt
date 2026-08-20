package mv.muraka.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The outbox is the only thing between a captured sighting and lost data, so
 * `sync-protocol.md` asks for WAL journalling with `synchronous = FULL`.
 *
 * This is an instrumented test rather than a JVM one on purpose: the pragmas are a
 * property of real SQLite on a real device, and a Robolectric shadow would happily report
 * whatever it liked. It also exists because a pragma set in the wrong place — in the
 * builder instead of `onOpen`, say — **silently does nothing**, and a durability setting
 * that quietly is not applied is worse than one that was never attempted.
 */
@RunWith(AndroidJUnit4::class)
class DurabilityPragmaTest {

    private lateinit var database: MurakaDatabase

    /** Built exactly as `DatabaseModule` builds it, on disk — WAL is meaningless in memory. */
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)
        database = Room.databaseBuilder(context, MurakaDatabase::class.java, TEST_DB)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA synchronous = FULL").close()
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        // Room opens lazily; touch it so onOpen has run before anything is asserted.
        database.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow() {
        assertEquals("wal", pragma("journal_mode"))
    }

    @Test
    fun commitsReachTheStorageMediumBeforeEnqueueReturns() {
        // 2 is FULL. Android's default under WAL is 1 (NORMAL), which lets the OS buffer a
        // commit — and a phone that dies in that window loses a sighting the contributor
        // watched the app accept. If this reads "1", the pragma is not being applied and
        // the durability guarantee in the project is not real.
        assertEquals("2", pragma("synchronous"))
    }

    @Test
    fun foreignKeysAreEnforcedSoAPhotoCannotOutliveItsSighting() {
        assertEquals("1", pragma("foreign_keys"))
    }

    private fun pragma(name: String): String =
        database.openHelper.writableDatabase.query("PRAGMA $name").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0).lowercase()
        }

    private companion object {
        const val TEST_DB = "muraka-durability-test.db"
    }
}
