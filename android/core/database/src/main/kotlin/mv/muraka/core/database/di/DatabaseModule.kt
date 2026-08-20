package mv.muraka.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import mv.muraka.core.database.MurakaDatabase
import mv.muraka.core.database.dao.CacheDao
import mv.muraka.core.database.dao.OutboxDao
import javax.inject.Singleton

/**
 * Builds the database with durability turned up.
 *
 * The defaults trade durability for speed, which is the wrong trade for the only thing
 * standing between a captured sighting and lost data:
 *
 * - **WAL** so a reader (the history screen) never blocks the writer (the capture flow),
 *   and so a crash mid-write leaves a recoverable log rather than a torn page.
 * - **`synchronous = FULL`** so a committed transaction has actually reached the storage
 *   medium before `enqueue` returns. Android's default under WAL is `NORMAL`, which lets
 *   the OS buffer a commit — and a phone that dies in that window loses a sighting the
 *   contributor watched the app accept.
 *
 * Both are asserted by `DurabilityPragmaTest`, because a pragma set in the wrong place
 * silently does nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MurakaDatabase =
        Room.databaseBuilder(context, MurakaDatabase::class.java, MurakaDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Must be set on open rather than in the builder: `synchronous` is
                        // per-connection state, and there is no Room API for it.
                        db.query("PRAGMA synchronous = FULL").close()
                        // Photo rows are meaningless without their parent sighting, and
                        // Room does not enable this by default.
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            // No fallbackToDestructiveMigration. On a schema mismatch Room must fail
            // loudly in development rather than quietly dropping a contributor's
            // undelivered sightings on a version upgrade.
            .build()

    @Provides
    fun outboxDao(database: MurakaDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun cacheDao(database: MurakaDatabase): CacheDao = database.cacheDao()
}
