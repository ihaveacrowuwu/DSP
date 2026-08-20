import Foundation

/// The signed-in account.
struct User: Equatable, Sendable {
    let id: String
    var email: String
    var displayName: String
    var role: Role
    var status: String
    let createdAt: Date
}

/// Contribution totals.
///
/// These come from `GET /v1/me` and **only** from there. A client-side tally drifts the
/// moment anything is rejected, verified or anonymised, and the number the contributor
/// sees would then disagree with the dashboard (D21).
struct ContributorStats: Equatable, Sendable {
    var total: Int = 0
    var verified: Int = 0
    var pending: Int = 0
    var rejected: Int = 0
}

/// The account plus its authoritative totals.
struct Profile: Equatable, Sendable {
    let user: User
    var stats: ContributorStats
}

/// Who, if anyone, is signed in.
enum SessionState: Equatable, Sendable {
    case unknown
    case signedOut
    case signedIn(User)
}

/// A session as it is held in the Keychain.
///
/// ``refreshToken`` is single-use: every refresh returns a new one, and it must be
/// persisted alongside the access token or the next refresh fails and the contributor is
/// signed out for no reason.
struct StoredSession: Equatable, Sendable, Codable {
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
    let userID: String
}
