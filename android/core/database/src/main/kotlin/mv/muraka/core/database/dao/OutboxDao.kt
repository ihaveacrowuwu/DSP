package mv.muraka.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity

/**
 * The outbox.
 *
 * Every query that selects work to send filters on `user_id`, without exception. A row
 * belongs to the account that captured it, and uploading it under anyone else's session
 * would attribute reef data to the wrong contributor.
 */
@Dao
interface OutboxDao {

    /**
     * Queues a sighting and its photographs in one transaction.
     *
     * Atomicity is the point: a sighting row with no photo rows would upload as a
     * sighting with zero photographs, which can never be classified, and a photo row with
     * no parent would be orphaned work. Either both land or neither does.
     */
    @Transaction
    suspend fun enqueue(sighting: SightingQueueEntity, photos: List<PhotoQueueEntity>) {
        insertSighting(sighting)
        insertPhotos(photos)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSighting(sighting: SightingQueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<PhotoQueueEntity>)

    // -- Reading -------------------------------------------------------------

    /** Everything still owed to the server, oldest capture first. */
    @Query(
        """
        SELECT * FROM sighting_queue
        WHERE user_id = :userId AND state != 'confirmed'
        ORDER BY created_at ASC
        """,
    )
    fun observeQueue(userId: String): Flow<List<SightingQueueEntity>>

    /** Undelivered sightings, for the permanent count. A silent queue loses data unnoticed. */
    @Query(
        """
        SELECT COUNT(*) FROM sighting_queue
        WHERE user_id = :userId AND state IN ('queued', 'sending', 'in_doubt', 'failed')
        """,
    )
    fun observePendingCount(userId: String): Flow<Int>

    /**
     * The next rows to work on.
     *
     * `created_at ASC` so the researcher's queue reflects capture order, and
     * `next_attempt_at` respects the backoff curve. `sending` rows are included because a
     * process killed mid-request leaves one behind, and reconciliation is what resolves
     * it - not another sender picking it up blindly.
     */
    @Query(
        """
        SELECT * FROM sighting_queue
        WHERE user_id = :userId
          AND state IN ('queued', 'sending', 'in_doubt')
          AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        ORDER BY created_at ASC
        LIMIT :limit
        """,
    )
    suspend fun dueForSync(userId: String, now: Long, limit: Int = 25): List<SightingQueueEntity>

    @Query("SELECT * FROM sighting_queue WHERE id = :id")
    suspend fun sighting(id: String): SightingQueueEntity?

    @Query("SELECT * FROM photo_queue WHERE sighting_id = :sightingId ORDER BY ordinal ASC")
    suspend fun photosFor(sightingId: String): List<PhotoQueueEntity>

    @Query("SELECT * FROM photo_queue WHERE sighting_id IN (:sightingIds) ORDER BY ordinal ASC")
    suspend fun photosForAll(sightingIds: List<String>): List<PhotoQueueEntity>

    @Query("SELECT * FROM photo_queue WHERE sighting_id IN (SELECT id FROM sighting_queue WHERE user_id = :userId)")
    fun observePhotos(userId: String): Flow<List<PhotoQueueEntity>>

    // -- State transitions ---------------------------------------------------

    @Query("UPDATE sighting_queue SET state = :state WHERE id = :id")
    suspend fun setSightingState(id: String, state: String)

    /**
     * Records a failed attempt.
     *
     * `attempts` only ever increases and is never reset by a failure, because the give-up
     * threshold has to be reachable: a row that resets its own counter retries forever,
     * and `sync-protocol.md` is explicit that a contributor deserves to know when
     * something is stuck.
     */
    @Query(
        """
        UPDATE sighting_queue
        SET state = :state, attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
        WHERE id = :id
        """,
    )
    suspend fun recordSightingAttempt(id: String, state: String, error: String?, nextAttemptAt: Long?)

    @Query("UPDATE photo_queue SET state = :state WHERE id = :id")
    suspend fun setPhotoState(id: String, state: String)

    @Query(
        """
        UPDATE photo_queue
        SET state = :state, attempts = attempts + 1, last_error = :error
        WHERE id = :id
        """,
    )
    suspend fun recordPhotoAttempt(id: String, state: String, error: String?)

    /** Puts a terminally failed row back in the queue, at the contributor's instruction. */
    @Query(
        """
        UPDATE sighting_queue
        SET state = 'queued', attempts = 0, last_error = NULL, next_attempt_at = NULL
        WHERE id = :id
        """,
    )
    suspend fun requeue(id: String)

    @Query(
        """
        UPDATE photo_queue
        SET state = 'queued', attempts = 0, last_error = NULL
        WHERE sighting_id = :sightingId AND state != 'confirmed'
        """,
    )
    suspend fun requeuePhotos(sightingId: String)

    // -- Deletion ------------------------------------------------------------

    /**
     * Drops an acknowledged row and its photographs.
     *
     * Called **only** after the server has confirmed the sighting exists and holds every
     * one of its photographs - not when an upload call returns, and never on a response
     * the client could not parse. Deleting earlier is how a sighting disappears with
     * nothing left to retry from.
     */
    @Query("DELETE FROM sighting_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM photo_queue WHERE id = :id")
    suspend fun deletePhoto(id: String)

    /** Account deletion. Everything for this owner goes. */
    @Query("DELETE FROM sighting_queue WHERE user_id = :userId")
    suspend fun deleteAllFor(userId: String)
}
