package mv.muraka.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mv.muraka.core.database.entity.CachedDetailEntity
import mv.muraka.core.database.entity.CachedProfileEntity
import mv.muraka.core.database.entity.CachedSightingEntity

/**
 * Last-known server state.
 *
 * Everything here is written by a read from the server and by nothing else. There is no
 * update method that takes a field - a cached record is replaced **wholesale** on every
 * refresh, which is why an expert correcting a label, a rejection, and an account
 * anonymisation all reach the app the same way and there is no merge logic to get wrong.
 */
@Dao
interface CacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSightings(sightings: List<CachedSightingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetail(detail: CachedDetailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: CachedProfileEntity)

    @Query("SELECT * FROM cached_sighting WHERE user_id = :userId ORDER BY captured_at DESC")
    fun observeSightings(userId: String): Flow<List<CachedSightingEntity>>

    @Query("SELECT * FROM cached_sighting WHERE id = :id")
    fun observeSighting(id: String): Flow<CachedSightingEntity?>

    @Query("SELECT * FROM cached_detail WHERE id = :id")
    fun observeDetail(id: String): Flow<CachedDetailEntity?>

    @Query("SELECT * FROM cached_profile WHERE user_id = :userId")
    fun observeProfile(userId: String): Flow<CachedProfileEntity?>

    /**
     * Removes cached rows the server no longer lists for this contributor.
     *
     * Scenario 10: a sighting deleted straight out of the database must stop appearing in
     * the app. Nothing survives in the interface on local authority alone.
     */
    @Query("DELETE FROM cached_sighting WHERE user_id = :userId AND id NOT IN (:keepIds)")
    suspend fun pruneSightings(userId: String, keepIds: List<String>)

    @Query("DELETE FROM cached_detail WHERE id NOT IN (SELECT id FROM cached_sighting)")
    suspend fun pruneDetails()

    @Query("DELETE FROM cached_sighting WHERE user_id = :userId")
    suspend fun deleteAllFor(userId: String)

    @Query("DELETE FROM cached_profile WHERE user_id = :userId")
    suspend fun deleteProfileFor(userId: String)
}
