package mv.muraka.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The outbox, and a cache of what the server has said.
 *
 * The two are kept in separate tables on purpose, and nothing joins them in SQL. The
 * outbox is authoritative only about what has NOT been delivered; the cache is
 * last-known server state and never a record. Merging them into one table with a
 * `synced` column is the design this one exists to avoid — see D21 in `docs/08`.
 */

/**
 * One queued sighting.
 *
 * [state] is a string state machine rather than a boolean, because a boolean cannot say
 * *"we sent it and do not know what happened"* — and that is precisely the state a lost
 * response leaves you in.
 */
@Entity(
    tableName = "sighting_queue",
    indices = [Index("user_id"), Index("state"), Index("created_at")],
)
data class SightingQueueEntity(
    /** The client's own UUIDv7. Sent as-is; it is the idempotency key. */
    @PrimaryKey val id: String,

    /**
     * The account that captured this.
     *
     * A row is only ever uploaded under its owner's session. Two people share a boat and
     * a phone more often than you would think, and without this one diver's queued
     * sighting uploads under whoever signs in next — corrupt scientific data, and an
     * ethics problem in a project that collects named contributions.
     */
    @ColumnInfo(name = "user_id") val userId: String,

    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "location_source") val locationSource: String,
    @ColumnInfo(name = "location_accuracy_m") val locationAccuracyM: Double? = null,
    @ColumnInfo(name = "depth_m") val depthM: Double? = null,

    /**
     * Capture time as the **device** clock reported it, in epoch milliseconds.
     *
     * Deliberately not corrected here. A sighting captured offline has no server offset
     * to correct against yet; `ServerClock` translates this at upload time, once a
     * response has taught it the offset.
     */
    @ColumnInfo(name = "captured_at_device") val capturedAtDevice: Long,

    val note: String? = null,
    @ColumnInfo(name = "self_condition") val selfCondition: String? = null,

    /** `queued` | `sending` | `in_doubt` | `confirmed` | `failed`. */
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,

    /** Epoch millis before which the backoff curve forbids another attempt. */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One queued photograph.
 *
 * [localPath] points into app-private storage, never the shared gallery: a gallery URI
 * can be revoked, and the file behind it deleted, long before the outbox drains. The
 * copy is made at capture time for that reason.
 */
@Entity(
    tableName = "photo_queue",
    foreignKeys = [
        ForeignKey(
            entity = SightingQueueEntity::class,
            parentColumns = ["id"],
            childColumns = ["sighting_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sighting_id"), Index("state")],
)
data class PhotoQueueEntity(
    /** The client's own UUIDv7, sent as `photoId`. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sighting_id") val sightingId: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    /** Capture order, so photographs upload in the order they were taken. */
    val ordinal: Int,
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/**
 * Last-known server state for one sighting — a **display cache**, never a record.
 *
 * [readAt] is what lets the interface say "as of 20 minutes ago" rather than presenting a
 * stale truth as a current one. A stale truth labelled stale is fine; a stale truth
 * presented as current is the bug this whole design exists to prevent.
 */
@Entity(tableName = "cached_sighting", indices = [Index("user_id"), Index("captured_at")])
data class CachedSightingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "location_source") val locationSource: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: String,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    val condition: String? = null,
    val severity: Double? = null,
    val confidence: Double? = null,
    val verified: Boolean = false,
    @ColumnInfo(name = "depth_m") val depthM: Double? = null,
    val note: String? = null,
    @ColumnInfo(name = "site_name") val siteName: String? = null,
    /** When this row was last read from the server, epoch millis. */
    @ColumnInfo(name = "read_at") val readAt: Long,
)

/**
 * The full detail response, stored as the JSON the server sent.
 *
 * A blob rather than typed tables because the protocol says a cached record is replaced
 * **wholesale** on every refresh — never merged, never patched field by field. There is
 * therefore no client-side merge logic to get wrong, and no schema to migrate when the
 * prediction payload grows a field.
 */
@Entity(tableName = "cached_detail")
data class CachedDetailEntity(
    @PrimaryKey val id: String,
    val json: String,
    @ColumnInfo(name = "read_at") val readAt: Long,
)

/** Last-known profile, so the profile screen shows something while offline. */
@Entity(tableName = "cached_profile")
data class CachedProfileEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    val json: String,
    @ColumnInfo(name = "read_at") val readAt: Long,
)
