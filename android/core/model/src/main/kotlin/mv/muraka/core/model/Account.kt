package mv.muraka.core.model

import java.time.Instant

/** The signed-in account. */
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val role: Role,
    val status: String,
    val createdAt: Instant,
)

/**
 * Contribution totals.
 *
 * These come from `GET /v1/me` and **only** from there. A client-side tally drifts the
 * moment anything is rejected, verified or anonymised, and the number the contributor
 * sees would then disagree with the dashboard - see D21.
 */
data class ContributorStats(val total: Int, val verified: Int, val pending: Int, val rejected: Int)

/**
 * A session.
 *
 * [refreshToken] is single-use: every refresh returns a new one, and it must be persisted
 * in the same transaction as [accessToken] or the next refresh fails and the contributor
 * is signed out for no reason.
 */
data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Instant, val user: User)
