import Foundation
import Testing
@testable import Muraka

/// The build configuration is load-bearing: the wrong host means the app talks to
/// nothing, and the wrong scheme means a release build could talk to the dev stack in
/// clear text. Both are cheap to assert and expensive to discover by hand.
struct AppConfigurationTests {
    @Test func baseURLIsTheLocalDevelopmentStackUnderDebug() {
        #expect(AppConfiguration.baseURL.absoluteString == "http://localhost:8090")
    }

    @Test func backgroundTaskIdentifierMatchesTheInfoPlist() {
        let permitted = Bundle.main
            .object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        #expect(permitted?.contains(AppConfiguration.backgroundDrainTaskIdentifier) == true)
    }
}
