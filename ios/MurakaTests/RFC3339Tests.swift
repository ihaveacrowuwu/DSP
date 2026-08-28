import Foundation
import Testing
@testable import Muraka

/// The bug these guard broke every screen in the app at once, and presented as "signing in
/// does not work" - because the decode failure was the last link in a chain that started at
/// the login form.
///
/// Both producers in this system are represented literally, with real values captured from
/// the running stack, so a future change to either is caught here rather than in a simulator.
struct RFC3339Tests {
    @Test func parsesGoRFC3339NanoWithNineFractionalDigits() throws {
        // Exactly what `time.Time` marshals to. ISO8601FormatStyle rejects this outright.
        let date = try #require(RFC3339.date(from: "2026-08-20T23:10:01.340152254Z"))
        #expect(abs(date.timeIntervalSince1970 - 1_787_267_401.340152) < 0.001)
    }

    @Test func parsesPostgresTimestampsWithSixFractionalDigits() throws {
        let date = try #require(RFC3339.date(from: "2026-08-20T22:12:34.405288Z"))
        #expect(abs(date.timeIntervalSince1970 - 1_787_263_954.405288) < 0.001)
    }

    @Test func parsesATimestampWithNoFractionalPart() throws {
        let date = try #require(RFC3339.date(from: "2026-08-20T22:12:34Z"))
        #expect(date.timeIntervalSince1970 == 1_787_263_954)
    }

    @Test func parsesAnExplicitOffsetRatherThanZulu() throws {
        // The Maldives is UTC+5, and nothing stops the server being configured to emit it.
        let offset = try #require(RFC3339.date(from: "2026-08-21T03:12:34+05:00"))
        let zulu = try #require(RFC3339.date(from: "2026-08-20T22:12:34Z"))
        #expect(offset == zulu)
    }

    @Test func rejectsSomethingThatIsNotATimestamp() {
        #expect(RFC3339.date(from: "not a date") == nil)
        #expect(RFC3339.date(from: "") == nil)
    }

    @Test func decodesAWholeSessionPayloadAsTheServerSendsIt() throws {
        // Captured verbatim from `POST /v1/auth/login` against the local stack.
        let json = """
        {
          "accessToken": "a", "refreshToken": "r",
          "expiresAt": "2026-08-20T23:10:01.340152254Z",
          "user": {
            "id": "018f3c2a-0000-7000-8000-000000000000",
            "email": "diver@muraka.test", "displayName": "Demo Dive Guide",
            "role": "contributor", "status": "active",
            "createdAt": "2026-08-20T22:12:34.405288Z"
          }
        }
        """
        let session = try JSONDecoder.muraka().decode(SessionDTO.self, from: Data(json.utf8))
        #expect(session.user.displayName == "Demo Dive Guide")
        #expect(session.user.role == "contributor")
    }
}
