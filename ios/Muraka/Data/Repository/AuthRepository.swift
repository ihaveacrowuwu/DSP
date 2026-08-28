import Foundation
import GRDB

/// Sessions and the account.
///
/// The only screen in the app that needs connectivity is the one this backs (NFR7).
/// Everything else works on a boat; this cannot, and says so plainly rather than queueing a
/// sign-in that could never succeed.
final class AuthRepository: Sendable {
    private let api: APIClient
    private let outbox: OutboxStore
    private let photos: PhotoStore
    private let tokens: TokenStore

    private let decoder = JSONDecoder.muraka()
    private let encoder = JSONEncoder.muraka()

    init(api: APIClient, outbox: OutboxStore, photos: PhotoStore, tokens: TokenStore) {
        self.api = api
        self.outbox = outbox
        self.photos = photos
        self.tokens = tokens
    }

    /// Who is signed in, as far as the device can tell without the network.
    func sessionState() async -> SessionState {
        guard let stored = await tokens.current() else { return .signedOut }
        guard let profile = try? await cachedProfile(userID: stored.userID) else {
            // Tokens but no cached profile: only reachable if the container was cleared
            // while offline. The app stays usable - NFR7 - on a stub that the next
            // successful `/v1/me` replaces.
            return .signedIn(User(
                id: stored.userID,
                email: "",
                displayName: "",
                role: .contributor,
                status: "active",
                createdAt: .distantPast
            ))
        }
        return .signedIn(profile.user)
    }

    func register(email: String, password: String, displayName: String) async throws -> Profile {
        let session = try await api.register(email: email, password: password, displayName: displayName)
        return try await start(session: session)
    }

    func signIn(email: String, password: String) async throws -> Profile {
        let session = try await api.login(email: email, password: password)
        return try await start(session: session)
    }

    /// Revokes the refresh token and forgets the session.
    ///
    /// **Keeps the outbox.** Queued rows belong to the account that captured them and wait
    /// for that account to sign back in - two people share a boat and a phone more often than
    /// you would think, and uploading one diver's sighting under another's name is corrupt
    /// data and an ethics problem, not a cosmetic bug.
    func signOut() async {
        if let stored = await tokens.current() {
            await api.logout(refreshToken: stored.refreshToken)
        }
        await tokens.clear()
    }

    /// The **only** source of contribution totals. Never count local rows.
    func refreshProfile() async throws -> Profile {
        guard let userID = await tokens.currentUserID() else { throw ApiError.unauthorized }
        let me = try await api.me()
        try await cache(me: me, userID: userID)
        return me.domain
    }

    /// Last-known profile, for showing something while offline.
    func cachedProfile(userID: String) async throws -> Profile? {
        guard let record = try await outbox.cachedProfile(for: userID),
              let me = try? decoder.decode(MeDTO.self, from: record.json)
        else { return nil }
        return me.domain
    }

    /// Anonymises the account.
    ///
    /// Sightings survive as scientific record under a tombstone owner; the link to the person
    /// does not. NFR15 requires the app to say so *before* the contributor confirms, which the
    /// profile screen does.
    func deleteAccount() async throws {
        guard let userID = await tokens.currentUserID() else { throw ApiError.unauthorized }

        try await api.deleteAccount()

        // Only after the server confirms. This deletes what is on the device, not the science.
        try await outbox.deleteAll(for: userID)
        await photos.deleteAll()
        await tokens.clear()
    }

    // -- Internals -----------------------------------------------------------

    /// Stores the session and reads the profile back.
    ///
    /// Both tokens are written as one Keychain item before anything else happens: the refresh
    /// token is single-use, and a crash between two separate writes would sign the
    /// contributor out on their very next request.
    private func start(session: SessionDTO) async throws -> Profile {
        let stored = await tokens.save(StoredSession(
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
            expiresAt: session.expiresAt,
            userID: session.user.id
        ))
        // Without this the app signs in, fails to persist, and returns to the sign-in screen
        // with no explanation - which reads to the contributor as a wrong password.
        guard stored else {
            throw ApiError.unexpected(detail: "the session could not be stored in the Keychain")
        }

        // Totals come from `/v1/me` and nowhere else. If it cannot be reached the session is
        // still good - the contributor gets an account with no totals yet rather than a
        // failed sign-in.
        if let me = try? await api.me() {
            try await cache(me: me, userID: session.user.id)
            return me.domain
        }
        return Profile(user: session.user.domain, stats: ContributorStats())
    }

    private func cache(me: MeDTO, userID: String) async throws {
        guard let json = try? encoder.encode(me) else { return }
        try await outbox.cacheProfile(CachedProfileRecord(
            userID: userID,
            json: json,
            readAt: Date().timeIntervalSince1970
        ))
    }
}
