import CoreLocation
import Foundation

/// A single position fix.
///
/// **Nil is an ordinary outcome, not an error.** A diver under cloud, or on a hull that
/// blocks the sky, may simply have no fix — and the capture flow then offers a dropped pin,
/// recorded as `manual_pin` so researchers can filter on the difference.
///
/// `CLLocationManager` is delegate-based and single-shot requests have no async form, so this
/// bridges to a continuation. The `@MainActor` isolation is not decoration: `CLLocationManager`
/// must be created and its delegate called on a run loop, and Swift 6 enforces that.
@MainActor
final class LocationProvider: NSObject {
    private let manager = CLLocationManager()
    private var pending: CheckedContinuation<LocationFix?, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// True when the app currently holds a location permission.
    var hasPermission: Bool {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: true
        default: false
        }
    }

    var isDenied: Bool { manager.authorizationStatus == .denied }

    /// Asks in context, at the moment of capture, never on launch — non-negotiable 6.
    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    /// One fix, or nil if none arrives in time.
    func currentFix(timeout: Duration = .seconds(10)) async -> LocationFix? {
        guard hasPermission else { return nil }

        // A watchdog, because CLLocationManager can simply never call back — under a hull,
        // or with location services throttled — and a capture flow that waits forever is
        // worse than one that offers a pin.
        let watchdog = Task { [weak self] in
            try? await Task.sleep(for: timeout)
            await self?.resume(with: nil)
        }
        defer { watchdog.cancel() }

        return await withCheckedContinuation { continuation in
            // A second request while one is in flight resolves the first as "no fix" rather
            // than leaking its continuation, which would hang the caller forever.
            pending?.resume(returning: nil)
            pending = continuation
            manager.requestLocation()
        }
    }

    private func resume(with fix: LocationFix?) {
        guard let continuation = pending else { return }
        pending = nil
        continuation.resume(returning: fix)
    }
}

extension LocationProvider: CLLocationManagerDelegate {
    nonisolated func locationManager(
        _: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let location = locations.last else { return }
        let fix = LocationFix(
            position: Position(
                lat: location.coordinate.latitude,
                lon: location.coordinate.longitude
            ),
            source: .gps,
            accuracyM: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        )
        Task { @MainActor [weak self] in self?.resume(with: fix) }
    }

    nonisolated func locationManager(_: CLLocationManager, didFailWithError _: Error) {
        // Not an error worth surfacing: it means "no fix", which the pin path already covers.
        Task { @MainActor [weak self] in self?.resume(with: nil) }
    }
}
