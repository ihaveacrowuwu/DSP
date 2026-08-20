package mv.muraka.core.model

import java.time.Instant

/**
 * Limits the **client** is solely responsible for.
 *
 * The API enforces none of these: it accepts any number of photographs and a note of any
 * length. FR2 says one to five photographs, so if the app does not cap it, nothing does.
 * They live here rather than in a screen so both the capture form and the outbox agree.
 */
object CaptureLimits {
    const val MIN_PHOTOS = 1

    /** FR2. The API would happily take a sixth. */
    const val MAX_PHOTOS = 5

    /** Unbounded server-side. Long enough for a real observation, short enough to read. */
    const val MAX_NOTE_LENGTH = 500

    /** The server *does* enforce this one; matching it locally turns a 422 into a hint. */
    val DEPTH_RANGE_M = 0.0..200.0

    /**
     * Longest edge, in pixels, that uploads are downscaled to.
     *
     * The server analyses at 224 px per grid cell, so a 5×5 grid gains nothing above
     * roughly 1600 px. Below the 12 MiB cap by a wide margin, and far kinder to a resort
     * Wi-Fi connection than a 12-megapixel original.
     */
    const val UPLOAD_MAX_EDGE_PX = 1600

    /** JPEG quality for the downscaled upload. */
    const val UPLOAD_JPEG_QUALITY = 85
}

/** A position fix, however it was obtained. */
data class LocationFix(
    val position: Position,
    val source: LocationSource,
    /** Reported accuracy in metres, when the platform provides one. */
    val accuracyM: Double? = null,
)

/**
 * One photograph, already copied into app-private storage.
 *
 * The copy happens at capture time, not upload time: a gallery URI can be revoked, and
 * the file behind it deleted, long before the outbox drains.
 */
data class PhotoDraft(
    /** Client-generated UUIDv7. This is the idempotency key for the upload. */
    val id: String,
    val localPath: String,
)

/**
 * Everything needed to queue a sighting. Built by the capture screen, handed to the
 * repository, and never seen again — the outbox row is what survives.
 *
 * [capturedAt] is **device** time. It is translated into server time by `ServerClock` at
 * upload, once an offset is known; correcting it here would be guessing, because a
 * sighting captured offline has no offset to correct against yet.
 */
data class SightingDraft(
    val id: String,
    val fix: LocationFix,
    val capturedAt: Instant,
    val depthM: Double? = null,
    val note: String? = null,
    val selfAssessedCondition: Condition? = null,
    val photos: List<PhotoDraft>,
) {
    /** Problems the contributor must fix before this can be queued, keyed by field. */
    fun validate(): Map<String, String> = buildMap {
        if (!fix.position.isValid) put("position", "must be a valid coordinate")
        if (photos.size < CaptureLimits.MIN_PHOTOS) put("photos", "add at least one photograph")
        if (photos.size > CaptureLimits.MAX_PHOTOS) {
            put("photos", "at most ${CaptureLimits.MAX_PHOTOS} photographs")
        }
        depthM?.let {
            if (it !in CaptureLimits.DEPTH_RANGE_M) put("depthM", "must be between 0 and 200 metres")
        }
        note?.let {
            if (it.length > CaptureLimits.MAX_NOTE_LENGTH) {
                put("note", "at most ${CaptureLimits.MAX_NOTE_LENGTH} characters")
            }
        }
    }
}

/**
 * A sighting as the contributor's own history shows it: the server's record where one
 * exists, the outbox's where it does not, and never a blend that implies more certainty
 * than either provides.
 *
 * [server] is null until the server has answered about this id even once — which is the
 * normal state of a sighting captured on a boat. [serverReadAt] is what lets the UI say
 * "as of 20 minutes ago" instead of presenting a stale truth as a current one.
 */
data class ContributorSighting(
    val id: String,
    /** Device capture time. What the contributor recognises, whatever the server stored. */
    val capturedAt: Instant,
    val position: Position,
    val locationSource: LocationSource,
    val photoCount: Int,
    val displayStatus: SightingDisplayStatus,
    val server: Sighting? = null,
    val serverReadAt: Instant? = null,
    val outboxState: OutboxState? = null,
    /** Why a terminally failed row failed, in words the contributor can act on. */
    val failureReason: String? = null,
    /** Photographs still waiting to upload, of [photoCount]. */
    val photosPending: Int = 0,
) {
    /** True when nothing about this sighting has been confirmed by the server yet. */
    val isUnconfirmed: Boolean get() = server == null
}

/** The signed-in account plus its authoritative totals. */
data class Profile(val user: User, val stats: ContributorStats)

/** Who, if anyone, is signed in. */
sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}
