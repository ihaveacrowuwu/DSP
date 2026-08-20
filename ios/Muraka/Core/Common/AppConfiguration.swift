import Foundation

/// Values baked into the bundle at build time.
///
/// There is no API key here, and there is no code path that reads one. Muraka depends on
/// no service requiring registration — a hard project constraint (NFR9), not an
/// oversight — so the only thing configuration carries is which host to talk to.
enum AppConfiguration {
    /// The API host. `http://localhost:8090` under Debug (the simulator shares the Mac's
    /// loopback), HTTPS under Release. Set `MURAKA_API_BASE_URL` in
    /// `Config/Local.xcconfig` to point a physical iPhone at the Mac's LAN address.
    static let baseURL: URL = {
        guard
            let raw = Bundle.main.object(forInfoDictionaryKey: "MurakaAPIBaseURL") as? String,
            let url = URL(string: raw)
        else {
            // A missing or malformed host is a build-configuration error, not a runtime
            // condition — the app cannot do anything useful, and a descriptive crash is
            // more helpful than a silent fallback that hides the misconfiguration.
            fatalError("MurakaAPIBaseURL is missing or malformed in Info.plist")
        }
        return url
    }()

    /// Identifier the outbox drain is registered under. Must match the
    /// `BGTaskSchedulerPermittedIdentifiers` entry in both Info.plists.
    static let backgroundDrainTaskIdentifier = "mv.muraka.sync.drain"
}
