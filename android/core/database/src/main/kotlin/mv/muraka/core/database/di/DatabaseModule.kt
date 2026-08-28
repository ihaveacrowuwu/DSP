package mv.muraka.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
 *   the OS buffer a commit - and a phone that dies in that window loses a sighting the
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
            .openHelperFactory(DurableOpenHelperFactory())
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Photo rows are meaningless without their parent sighting, and Room
                        // does not enable this by default.
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            // No fallbackToDestructiveMigration. On a schema mismatch Room must fail loudly in
            // development rather than quietly dropping a contributor's undelivered sightings on
            // a version upgrade.
            .build()

    @Provides
    fun outboxDao(database: MurakaDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun cacheDao(database: MurakaDatabase): CacheDao = database.cacheDao()
}

/**
 * Applies `synchronous = FULL` where it actually sticks.
 *
 * The obvious place is `RoomDatabase.Callback.onOpen`, and it does not work: Android's
 * `SQLiteDatabase` applies its own WAL sync mode - `NORMAL` - when it configures a
 * connection, **after** `onOpen` has run, so the pragma is silently overwritten and
 * `PRAGMA synchronous` reads back `1`. `DurabilityPragmaTest` caught exactly that, which is
 * the whole reason it reads the value back instead of trusting the call.
 *
 * `onConfigure` runs at the point each connection is being set up, before that happens, so
 * the setting survives. Wrapping the framework factory is the only way to reach it through
 * Room.
 */
private class DurableOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(DurableCallback(configuration.callback))
                .build(),
        )
}

/**
 * Room's own callback, with `onConfigure` extended.
 *
 * Everything else delegates untouched - this must not become a second place where schema
 * decisions live.
 */
private class DurableCallback(private val delegate: SupportSQLiteOpenHelper.Callback) :
    SupportSQLiteOpenHelper.Callback(delegate.version) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        // FULL, not NORMAL: a committed transaction must have reached the storage medium
        // before `enqueue` returns. Under NORMAL the OS may still be holding it, and a phone
        // that dies in that window loses a sighting the contributor watched the app accept.
        db.execSQL("PRAGMA synchronous = FULL")
        delegate.onConfigure(db)
    }

    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) = delegate.onCorruption(db)
}
