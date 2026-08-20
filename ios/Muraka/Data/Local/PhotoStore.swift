import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Where photograph bytes live between capture and acknowledgement.
///
/// Three rules from `sync-protocol.md` are implemented here, and each one exists because
/// skipping it loses a photograph:
///
/// 1. **Copy at capture time.** A `PHPicker` asset can be deleted, and its identifier
///    invalidated, long before the outbox drains. What is queued has to be bytes we own.
/// 2. **Write to a temporary name, then rename atomically.** A half-written file that looks
///    complete is indistinguishable from a real one at upload time — the upload succeeds and
///    the researcher gets a truncated image.
/// 3. **Downscale before uploading.** The server analyses at 224 px per grid cell, so a 5×5
///    grid gains nothing above roughly 1600 px. That is far under the 12 MiB cap and much
///    kinder to a resort Wi-Fi connection than a 12-megapixel original.
///
/// Orientation is baked into the pixels rather than left as an EXIF tag, because the server
/// strips EXIF when it re-encodes — a photograph relying on the tag would reach the
/// researcher sideways, and the patch lattice with it.
actor PhotoStore {
    private let directory: URL

    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = (try? FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )) ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.directory = base.appendingPathComponent("outbox-photos", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }

    nonisolated func fileURL(for photoID: String) -> URL {
        directory.appendingPathComponent("\(photoID).jpg")
    }

    /// Stores an image, downscaled and correctly oriented.
    ///
    /// Returns nil if the data could not be decoded — an ordinary outcome for a corrupt file
    /// from an action camera, not something worth crashing over.
    @discardableResult
    func store(photoID: String, data: Data) -> URL? {
        guard let image = downscaled(from: data) else { return nil }
        return writeAtomically(photoID: photoID, image: image)
    }

    /// Stores an already-decoded image — the camera path.
    @discardableResult
    func store(photoID: String, image: UIImage) -> URL? {
        writeAtomically(photoID: photoID, image: scaleToLongestEdge(image, CaptureLimits.uploadMaxEdge))
    }

    /// Re-encodes an already-stored photograph smaller still, for a `413` the server
    /// refused. The caller uploads the result under a **new** photo id, because the old one
    /// may already be half-known to the server.
    func downscaleFurther(from sourceID: String, to targetID: String) -> URL? {
        guard let data = try? Data(contentsOf: fileURL(for: sourceID)),
              let image = UIImage(data: data)
        else { return nil }

        let smaller = scaleToLongestEdge(image, CaptureLimits.uploadMaxEdge / 2)
        return writeAtomically(photoID: targetID, image: smaller, quality: Self.retryJPEGQuality)
    }

    /// Deletes a photograph's bytes.
    ///
    /// Called **only** once the server's own `photos[]` lists the id — not when an upload
    /// call returns, and not when a local flag is set.
    func delete(photoID: String) {
        try? FileManager.default.removeItem(at: fileURL(for: photoID))
    }

    /// Everything for an account, after `DELETE /v1/me` succeeds.
    func deleteAll() {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ) else { return }
        for file in files { try? FileManager.default.removeItem(at: file) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private func writeAtomically(
        photoID: String,
        image: UIImage,
        quality: CGFloat = CaptureLimits.uploadJPEGQuality
    ) -> URL? {
        guard let data = image.jpegData(compressionQuality: quality) else { return nil }

        let target = fileURL(for: photoID)
        let temporary = directory.appendingPathComponent("\(photoID).jpg.part")

        do {
            // `.atomic` forces the write through a temporary file and a rename, so nothing
            // can ever read a partially written photograph under the real name.
            try data.write(to: temporary, options: [.atomic])
            _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
            return target
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            return nil
        }
    }

    /// Decodes at a reduced size rather than loading a 12-megapixel bitmap first.
    ///
    /// `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceCreateThumbnailWithTransform`
    /// applies the EXIF orientation while it decodes, which is both faster than rotating
    /// afterwards and the reason the result needs no orientation fix-up.
    private func downscaled(from data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: CaptureLimits.uploadMaxEdge,
        ]

        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return UIImage(data: data).map { scaleToLongestEdge($0, CaptureLimits.uploadMaxEdge) }
        }
        return UIImage(cgImage: cgImage)
    }

    private func scaleToLongestEdge(_ image: UIImage, _ maxEdge: CGFloat) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxEdge else { return image }

        let ratio = maxEdge / longest
        let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Lower than the first attempt: this path exists because the server said 413.
    private static let retryJPEGQuality: CGFloat = 0.7
}
