package mv.muraka.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import mv.muraka.core.database.dao.CacheDao
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.CachedDetailEntity
import mv.muraka.core.database.entity.CachedProfileEntity
import mv.muraka.core.database.entity.CachedSightingEntity
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity

/**
 * The on-device database.
 *
 * Schemas are exported to `core/database/schemas/` and committed, so a schema change
 * without a migration is caught in review rather than by a crash that loses a diver's
 * queued sightings.
 *
 * There are no type converters: every column is a primitive, and instants are epoch
 * milliseconds. A converter would put a parsing step between a captured sighting and the
 * disk, which is a place this database should not have failure modes.
 */
@Database(
    entities = [
        SightingQueueEntity::class,
        PhotoQueueEntity::class,
        CachedSightingEntity::class,
        CachedDetailEntity::class,
        CachedProfileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MurakaDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun cacheDao(): CacheDao

    companion object {
        const val NAME = "muraka.db"
    }
}
