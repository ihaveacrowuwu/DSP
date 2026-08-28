package mv.muraka.core.domain

import kotlinx.coroutines.flow.Flow
import mv.muraka.core.model.ContributorSighting
import mv.muraka.core.model.LocationFix
import mv.muraka.core.model.Photo
import mv.muraka.core.model.Profile
import mv.muraka.core.model.SessionState
import mv.muraka.core.model.SightingDetail
import mv.muraka.core.model.SightingDraft
import mv.muraka.core.model.Verification

/**
 * The boundary between the app and everything that stores or fetches.
 *
 * These are pure Kotlin interfaces in a module with no Android SDK on its classpath, so a
 * view-model test needs a hand-written fake and nothing else - no Robolectric, no
 * MockWebServer, no emulator. That is the entire reason the layering exists.
 *
 * Every method returns `Result<T>` whose failure is an `ApiError`, so a caller has to
 * decide what a failure means rather than letting an exception escape into the UI.
 */

/** Sessions and the account. */
interface AuthRepository {
    /** Who is signed in. Emits [SessionState.Unknown] until the token store is read. */
    val sessionState: Flow<SessionState>

    /** New accounts are always contributors; elevation is an admin action. */
    suspend fun register(email: String, password: String, displayName: String): Result<Profile>

    suspend fun signIn(email: String, password: String): Result<Profile>

    /**
     * Revokes the refresh token and forgets the session.
     *
     * **Keeps the outbox.** Queued rows belong to the account that captured them and wait
     * for that account to sign back in - two people share a boat and a phone more often
     * than you would think, and uploading one diver's sighting under another's name is
     * corrupt data and an ethics problem, not a cosmetic bug.
     */
    suspend fun signOut(): Result<Unit>

    /** The **only** source of contribution totals. Never count local rows. */
    suspend fun refreshProfile(): Result<Profile>

    /** Last-known profile, for showing something while offline. */
    fun observeProfile(): Flow<Profile?>

    /**
     * Anonymises the account: sightings survive as scientific record under a tombstone
     * owner, and the link to the person does not. NFR15 requires the app to say so
     * *before* the contributor confirms.
     */
    suspend fun deleteAccount(): Result<Unit>
}

/** Capture, history and detail. */
interface SightingRepository {
    /**
     * Queues a sighting. **Local only** - this returns as soon as the row and its photo
     * files are durably on disk, and never waits for the network. NFR7 is the whole point:
     * the app is fully functional with no connectivity except sign-in.
     */
    suspend fun capture(draft: SightingDraft): Result<String>

    /**
     * The contributor's own history: outbox rows and cached server records, merged into
     * one chronological list. Emits again whenever either side changes.
     */
    fun observeMySightings(): Flow<List<ContributorSighting>>

    /** One sighting, with its photographs and any expert verdict. */
    fun observeSighting(id: String): Flow<SightingWithDetail?>

    /** Pulls the list from the server. Failure leaves the cache alone. */
    suspend fun refreshMySightings(): Result<Unit>

    /** Pulls one sighting. This is what turns "Checking..." into a real status. */
    suspend fun refreshSighting(id: String): Result<Unit>

    /** Photo bytes for display, fetched with the bearer token and cached on disk. */
    suspend fun photoBytes(photoId: String): Result<ByteArray>
}

/**
 * A sighting as the detail screen needs it: the contributor's view of it, plus whatever
 * the server has said about its photographs and review.
 */
data class SightingWithDetail(
    val summary: ContributorSighting,
    val detail: SightingDetail?,
    val photos: List<Photo>,
    val verifications: List<Verification>,
    /** Local files for photographs that have not reached the server yet. */
    val pendingPhotoPaths: List<String>,
)

/** The queue itself, for the sync screen and the app-bar count. */
interface OutboxRepository {
    /** Everything not yet acknowledged, newest capture last. */
    fun observeQueue(): Flow<List<QueuedItem>>

    /**
     * How many sightings are still undelivered. Shown permanently somewhere in the
     * interface: a silent queue is how data goes missing unnoticed.
     */
    fun observePendingCount(): Flow<Int>

    /** Puts a terminally failed row back in the queue after the contributor acts. */
    suspend fun retry(sightingId: String): Result<Unit>

    /**
     * Downscales a photograph that was rejected `413` and re-queues it under a **new**
     * photo id, because the old id may already be half-known to the server.
     */
    suspend fun retryWithSmallerPhotos(sightingId: String): Result<Unit>

    /** Gives up on a row, at the contributor's explicit instruction. */
    suspend fun discard(sightingId: String): Result<Unit>
}

/** One row of the sync screen. */
data class QueuedItem(
    val sightingId: String,
    val capturedAt: java.time.Instant,
    val state: mv.muraka.core.model.OutboxState,
    val photosTotal: Int,
    val photosSent: Int,
    val attempts: Int,
    val lastError: String?,
    /** When the backoff curve permits the next attempt. Null when it may go now. */
    val nextAttemptAt: java.time.Instant?,
)

/**
 * The contributor's display preferences.
 *
 * Separate from [AuthRepository] because these outlive a session: signing out must not reset
 * somebody's chosen appearance, and on a shared boat phone the next diver inherits the
 * screen, not the account's taste.
 */
interface AppearanceRepository {
    val themePreference: kotlinx.coroutines.flow.Flow<mv.muraka.core.model.ThemePreference>

    suspend fun setThemePreference(preference: mv.muraka.core.model.ThemePreference)

    /** Whether the patch lattice is drawn over photographs. Defaults to on. */
    val showPatchGrid: kotlinx.coroutines.flow.Flow<Boolean>

    suspend fun setShowPatchGrid(visible: Boolean)
}

/** Position, however the platform can supply it. */
interface LocationProvider {
    /** True when the app currently holds a location permission. */
    fun hasPermission(): Boolean

    /**
     * A single fix, or null if none can be obtained in time.
     *
     * Null is an ordinary outcome, not an error: a diver on a boat under cloud may simply
     * have no fix, and the flow then falls back to dropping a pin, which records
     * `manual_pin` so researchers can filter on the difference.
     */
    suspend fun currentFix(timeoutMs: Long = 10_000): LocationFix?
}

/** Whether the device believes it has a route to the internet. */
interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

/** Asks the platform's scheduler to drain the outbox. */
interface SyncScheduler {
    /**
     * Requests a drain.
     *
     * [expedited] is used immediately after a capture, where the contributor is watching
     * and the work is short. Everything else - app foreground, connectivity returning,
     * the periodic task - takes the ordinary path.
     */
    fun requestSync(expedited: Boolean = false)

    /** Registers the periodic drain. Idempotent; safe to call on every launch. */
    fun ensurePeriodicSync()

    /** Whether a drain is running right now, for the sync screen's indicator. */
    val isSyncing: Flow<Boolean>
}
