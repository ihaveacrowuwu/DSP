# iOS app (not started)

Swift + **UIKit** with the iOS 26 Liquid Glass design language. UIKit rather than
SwiftUI is a project requirement, not a preference.

**Read [`../mobile-shared/`](../mobile-shared/) first** — the API contract, the
offline sync protocol and the shared design tokens are all settled.

Planned stack (see `mobile-shared/README.md` for the reasoning):

- GRDB (or Core Data) for the offline queue
- `URLSession` background upload tasks so uploads survive suspension, plus
  `BGProcessingTaskRequest` to drain the queue
- `PHPickerViewController` / `UIImagePickerController` for capture and import
- `CLLocationManager` for position

Point the app at `http://localhost:8090` from the simulator. Liquid Glass is a
surface treatment: the app must degrade to standard UIKit materials on older
systems.
