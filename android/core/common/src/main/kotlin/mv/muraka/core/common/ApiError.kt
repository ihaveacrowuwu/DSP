package mv.muraka.core.common

/**
 * Every way a request can fail, grouped by **what the client should do** rather than by
 * status code. That grouping is the whole design: the outbox drain asks
 * [isRetryable] and nothing else, so a new failure mode can never accidentally become an
 * infinite retry loop or a silently dropped sighting.
 *
 * The catalogue is `mobile-shared/integration.md`; the retry rules are
 * `mobile-shared/sync-protocol.md`.
 *
 * Extends [Exception] so repositories can return `Result<T>` without a second wrapper
 * type — the value is that `Result.failure(ApiError.Offline)` is exhaustively matchable
 * at the call site.
 */
sealed class ApiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    // ── Terminal: never retry, surface something the contributor can act on ──────

    /**
     * `422 validation_failed`. [fields] maps a request field to its problem, which is
     * what the sync list shows next to the failed item — "capturedAt cannot be in the
     * future" is actionable; "upload failed" is not.
     */
    data class Validation(val fields: Map<String, String>) :
        ApiError("validation failed: ${fields.entries.joinToString { "${it.key} ${it.value}" }}")

    /** `409`. This UUID belongs to another account. Regenerate the id or discard. */
    data object IdOwnedByAnotherUser : ApiError("this id already belongs to another account")

    /** `409` on registration only. */
    data object EmailTaken : ApiError("an account with that email already exists")

    /** `413`. Downscale locally and upload under a NEW photo id. */
    data object UploadTooLarge : ApiError("the image is too large")

    /** `400`. A malformed request is a client bug, not a transient failure. */
    data class BadRequest(val code: String) : ApiError("bad request: $code")

    /** `403 forbidden`. Wrong role — should never happen in this app; treat as a bug. */
    data object Forbidden : ApiError("this account may not do that")

    /** `403 account_disabled`. Suspended by an admin. Sign out with an explanation. */
    data object AccountDisabled : ApiError("this account is not active")

    /** `401 invalid_credentials`. Wrong email or password — not a token problem. */
    data object InvalidCredentials : ApiError("email or password is incorrect")

    /** `404`. Does not exist, or is not ours. */
    data object NotFound : ApiError("not found")

    // ── Recoverable: refresh once, then retry ───────────────────────────────────

    /**
     * `401 unauthorized` / `invalid_token`. The access token expired or was rejected.
     *
     * Reaching the drain loop with this means the refresh already ran and also failed, so
     * the queue is kept and the contributor is returned to sign-in.
     */
    data object Unauthorized : ApiError("the session has expired")

    // ── Transient: retry with backoff. The outcome is genuinely unknown ──────────

    /** `5xx`. Includes `503 not_ready`/`ml_service`, where ingest still succeeded. */
    data class Server(val status: Int) : ApiError("the server is having trouble ($status)")

    /** `429`. */
    data object RateLimited : ApiError("too many requests")

    /** No route to the host. The ordinary state of a phone on a boat, not an error. */
    data object Offline : ApiError("no connection")

    /** The request went out and nothing came back. This is the "in doubt" case. */
    data object Timeout : ApiError("the request timed out")

    /** Anything unclassified. Treated as transient, because assuming failure is safe. */
    data class Unexpected(val detail: String, val throwable: Throwable? = null) :
        ApiError("unexpected: $detail", throwable)

    /**
     * Whether the drain loop may send this again.
     *
     * Retrying a transient failure can never duplicate anything: both writes are keyed on
     * a client-generated UUID and a replay answers `200` instead of `201`. That is the
     * entire reason the ids are the client's.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is Server, RateLimited, Offline, Timeout, is Unexpected -> true
            is Validation, IdOwnedByAnotherUser, EmailTaken, UploadTooLarge, is BadRequest,
            Forbidden, AccountDisabled, InvalidCredentials, NotFound, Unauthorized,
            -> false
        }

    /**
     * Whether the failure leaves the outcome **unknown** rather than known-failed.
     *
     * A timeout or a dropped connection may have committed server-side before the
     * response was lost, so the row moves to `IN_DOUBT` and reconciliation asks the
     * server rather than guessing. Guessing is what loses sightings.
     */
    val outcomeIsUnknown: Boolean
        get() = when (this) {
            Timeout, is Server, is Unexpected -> true
            else -> false
        }

    /** Whether the caller should try one token refresh and repeat the request. */
    val needsRefresh: Boolean get() = this is Unauthorized
}
