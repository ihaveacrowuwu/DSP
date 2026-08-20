import Foundation

/// Every way a request can fail, grouped by **what the client should do** rather than by
/// status code.
///
/// That grouping is the design: the outbox drain asks ``isRetryable`` and nothing else, so
/// a new failure mode can never accidentally become an infinite retry loop or a silently
/// dropped sighting.
///
/// The catalogue is `mobile-shared/integration.md`; the retry rules are
/// `mobile-shared/sync-protocol.md`. This mirrors `ApiError.kt` on Android case for case —
/// same server, same decisions.
enum ApiError: Error, Equatable, Sendable {
    // ── Terminal: never retry, surface something the contributor can act on ──

    /// `422`. `fields` maps a request field to its problem, which is what the sync list
    /// shows — "capturedAt cannot be in the future" is actionable; "upload failed" is not.
    case validation(fields: [String: String])
    /// `409`. This UUID belongs to another account. Regenerate the id or discard.
    case idOwnedByAnotherUser
    /// `409` on registration only.
    case emailTaken
    /// `413`. Downscale locally and upload under a NEW photo id.
    case uploadTooLarge
    /// `400`. A malformed request is a client bug, not a transient failure.
    case badRequest(code: String)
    /// `403`. Wrong role — should never happen in this app; treat as a bug.
    case forbidden
    /// `403 account_disabled`. Suspended by an admin. Sign out with an explanation.
    case accountDisabled
    /// `401 invalid_credentials`. A wrong password, not a token problem.
    case invalidCredentials
    /// `404`. Does not exist, or is not ours.
    case notFound

    // ── Recoverable: refresh once, then retry ───────────────────────────────

    /// `401`. Reaching the drain loop with this means the refresh already failed too.
    case unauthorized

    // ── Transient: retry with backoff. The outcome is genuinely unknown ──────

    case server(status: Int)
    case rateLimited
    /// No route to the host. The ordinary state of a phone on a boat, not an error.
    case offline
    /// The request went out and nothing came back. This is the "in doubt" case.
    case timedOut
    case unexpected(detail: String)

    /// Whether the drain loop may send this again.
    ///
    /// Retrying a transient failure can never duplicate anything: both writes are keyed on
    /// a client-generated UUID, and a replay answers `200` instead of `201`. That is the
    /// entire reason the ids are the client's.
    var isRetryable: Bool {
        switch self {
        case .server, .rateLimited, .offline, .timedOut, .unexpected: true
        case .validation, .idOwnedByAnotherUser, .emailTaken, .uploadTooLarge, .badRequest,
             .forbidden, .accountDisabled, .invalidCredentials, .notFound, .unauthorized: false
        }
    }

    /// Whether the failure leaves the outcome **unknown** rather than known-failed.
    ///
    /// A timeout or a dropped connection may have committed server-side before the response
    /// was lost, so the row moves to `inDoubt` and reconciliation asks the server rather
    /// than guessing. Guessing is what loses sightings.
    var outcomeIsUnknown: Bool {
        switch self {
        case .timedOut, .server, .unexpected: true
        default: false
        }
    }

    /// What to show the contributor.
    var message: String {
        switch self {
        case let .validation(fields):
            fields.map { "\($0.key) \($0.value)" }.sorted().joined(separator: ", ")
        case .idOwnedByAnotherUser: "This sighting id already belongs to another account."
        case .emailTaken: "An account with that email already exists."
        case .uploadTooLarge: "The photograph is too large to upload."
        case let .badRequest(code): "The request was rejected (\(code))."
        case .forbidden: "This account may not do that."
        case .accountDisabled: "This account has been suspended."
        case .invalidCredentials: "Email or password is incorrect."
        case .notFound: "Not found."
        case .unauthorized: "The session has expired."
        case let .server(status): "The server is having trouble (\(status))."
        case .rateLimited: "Too many requests. Try again shortly."
        case .offline: "No connection."
        case .timedOut: "The request timed out."
        case let .unexpected(detail): "Unexpected: \(detail)"
        }
    }

    /// Maps a thrown `URLError` and anything else that escapes `URLSession`.
    ///
    /// The distinction that matters is ``offline`` versus ``timedOut``: an unreachable host
    /// means the request never left, so nothing can have happened server-side, while a
    /// timeout means the outcome is unknown and the row must go to `inDoubt`.
    static func from(_ error: Error) -> ApiError {
        if let apiError = error as? ApiError { return apiError }

        guard let urlError = error as? URLError else {
            return .unexpected(detail: String(describing: error))
        }

        switch urlError.code {
        case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost,
             .networkConnectionLost, .dataNotAllowed:
            return .offline
        case .timedOut:
            return .timedOut
        case .cancelled:
            return .unexpected(detail: "cancelled")
        default:
            return .timedOut
        }
    }

    /// Maps an HTTP status and the server's error envelope.
    static func from(status: Int, envelope: ErrorEnvelope?) -> ApiError {
        let code = envelope?.error ?? ""

        switch status {
        case 422:
            // A 422 with no parseable body would otherwise be an empty terminal failure
            // with nothing to show the contributor.
            let fields = envelope?.fields ?? [:]
            return .validation(fields: fields.isEmpty
                ? ["request": envelope?.message ?? "was rejected"]
                : fields)
        case 409:
            return code == "email_taken" ? .emailTaken : .idOwnedByAnotherUser
        case 413:
            return .uploadTooLarge
        case 404:
            return .notFound
        case 403:
            return code == "account_disabled" ? .accountDisabled : .forbidden
        case 401:
            // `invalid_credentials` is a wrong password, not an expired token: refreshing
            // would be pointless and would burn the single-use refresh token for nothing.
            return code == "invalid_credentials" ? .invalidCredentials : .unauthorized
        case 429:
            return .rateLimited
        case 500...:
            return .server(status: status)
        default:
            return .badRequest(code: code.isEmpty ? "http_\(status)" : code)
        }
    }
}

/// The one error shape the API uses. `fields` is present on `422` only.
struct ErrorEnvelope: Decodable, Sendable {
    var error: String = ""
    var message: String = ""
    var fields: [String: String] = [:]
}
