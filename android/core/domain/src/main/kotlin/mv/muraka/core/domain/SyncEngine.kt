package mv.muraka.core.domain

/**
 * Drains the outbox and reconciles it with the server.
 *
 * A domain interface with its implementation in `:core:data` so that `:core:sync` - which
 * exists only to talk to WorkManager - needs neither Retrofit nor Room on its classpath.
 * The practical benefit is that the drain algorithm is testable without WorkManager, and
 * the scheduling is testable without a network.
 */
interface SyncEngine {
    /**
     * One pass over everything the signed-in contributor still owes the server.
     *
     * Safe to call as often as you like: every step is idempotent, and reconciliation is
     * cheaper than showing a contributor something untrue. Returns rather than throws
     * a drain that cannot reach the server has not failed, it has simply found the
     * ordinary state of a phone on a boat.
     */
    suspend fun drain(): SyncOutcome
}

/** What one drain pass achieved. Reported to the sync screen, and logged for the project. */
data class SyncOutcome(
    val sightingsConfirmed: Int = 0,
    val photosUploaded: Int = 0,
    val stillPending: Int = 0,
    val failedTerminally: Int = 0,
    /** True when the pass stopped because the server could not be reached. */
    val offline: Boolean = false,
    /** True when the session ended mid-drain and the contributor must sign in again. */
    val needsSignIn: Boolean = false,
) {
    /** Whether WorkManager should be asked to try again later. */
    val shouldRetry: Boolean get() = (offline || stillPending > 0) && !needsSignIn
}
