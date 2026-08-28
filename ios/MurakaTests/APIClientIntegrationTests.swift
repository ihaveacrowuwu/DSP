import Foundation
import Testing
@testable import Muraka

/// The client against the **real** API.
///
/// Unit tests with a stubbed session prove the client calls itself correctly; only this
/// proves it agrees with the Go server about the wire format. Every bug worth having found
/// here - timestamp precision, an enum spelling, a field name - is one that a stub would have
/// happily reproduced.
///
/// Skips rather than fails when the stack is not running: a red suite on a machine with no
/// Docker tells nobody anything.
struct APIClientIntegrationTests {
    private static let email = "diver@muraka.test"
    private static let password = "muraka-diver-2026"

    private func makeClient() -> APIClient {
        APIClient(
            tokens: TokenStore(),
            serverClock: ServerClock(),
            sessionEvents: SessionEvents()
        )
    }

    private func requireStack() async throws {
        var request = URLRequest(url: AppConfiguration.baseURL.appendingPathComponent("healthz"))
        request.timeoutInterval = 3
        let reachable = (try? await URLSession.shared.data(for: request)).map { _, response in
            (response as? HTTPURLResponse)?.statusCode == 200
        } ?? false
        try #require(reachable, "the Muraka stack is not running — start it with `make up`")
    }

    @Test func signsInAndDecodesTheSessionTheServerActuallySends() async throws {
        try await requireStack()

        let session = try await makeClient().login(email: Self.email, password: Self.password)

        #expect(!session.accessToken.isEmpty)
        #expect(!session.refreshToken.isEmpty)
        #expect(session.user.email == Self.email)
        #expect(session.user.role == "contributor")
        // The timestamp that broke everything the first time: Go emits nine fractional
        // digits, and a decoder that cannot take them fails the whole request.
        #expect(session.expiresAt > Date())
    }

    @Test func readsTheContributorsOwnSightingsWithTheBearerToken() async throws {
        try await requireStack()

        // A fresh token store per test, so the tests cannot depend on each other's state.
        let tokens = TokenStore()
        let client = APIClient(tokens: tokens, serverClock: ServerClock(), sessionEvents: SessionEvents())

        let session = try await client.login(email: Self.email, password: Self.password)
        let stored = await tokens.save(StoredSession(
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
            expiresAt: session.expiresAt,
            userID: session.user.id
        ))
        #expect(stored, "the Keychain write must succeed or nothing else can work")

        let page = try await client.listSightings(limit: 5)
        #expect(page.total > 0, "seed the stack with `make seed` before running this")

        let me = try await client.me()
        // D21: totals come from here and from nowhere else.
        #expect(me.stats.total >= 0)
    }

    @Test func reconciliationReportsNilForAnIdTheServerHasNeverSeen() async throws {
        try await requireStack()

        let tokens = TokenStore()
        let client = APIClient(tokens: tokens, serverClock: ServerClock(), sessionEvents: SessionEvents())
        let session = try await client.login(email: Self.email, password: Self.password)
        await tokens.save(StoredSession(
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
            expiresAt: session.expiresAt,
            userID: session.user.id
        ))

        // The primitive the whole outbox rests on: `404` must come back as "the server has
        // nothing", not as an error, or reconciliation treats a missing sighting as a
        // failure and never sends it.
        let unknown = try await client.sighting(id: UUIDv7.generate())
        #expect(unknown == nil)
    }
}
