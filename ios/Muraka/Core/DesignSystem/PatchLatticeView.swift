import UIKit

/// How the lattice is being drawn, and therefore which opacity formula applies.
enum LatticeMode {
    /// Drawn on top of a photograph: `0.28 + confidence x 0.42`.
    ///
    /// The range stops well short of solid on purpose. Past roughly 0.7 the cells stop
    /// annotating the reef and start replacing it, and a contributor cannot check a
    /// judgement against coral they can no longer see.
    case overlay

    /// The small standalone glyph in a list row, with no photograph behind it:
    /// `0.45 + confidence x 0.55`.
    ///
    /// Nothing is being obscured, so the full range is available and a hesitant model can
    /// look properly hesitant.
    case glyph

    func opacity(confidence: Double) -> CGFloat {
        let clamped = min(max(confidence, 0), 1)
        return switch self {
        case .overlay: CGFloat(0.28 + clamped * 0.42)
        case .glyph: CGFloat(0.45 + clamped * 0.55)
        }
    }
}

/// The patch lattice - the model's reasoning, drawn.
///
/// The classifier tiles a photograph into a `patchGrid x patchGrid` grid and judges each
/// cell, so drawing that grid is not decoration: it is the only way a contributor or a
/// researcher can see *where* the model thinks the bleaching is, and disagree with it. It is
/// the element all three clients share.
///
/// Two things about it are easy to get wrong and both make it lie:
///
/// **The geometry.** Cells cover the **centre square** of the photograph, because that is how
/// the server tiled it. Stretching the lattice across a non-square frame puts cell (0,0) over
/// pixels the model never saw.
///
/// **The opacity.** There are two formulas, not one - see ``LatticeMode``.
final class PatchLatticeView: UIView {
    private var patches: [Patch] = []
    private var grid = 5
    private var mode: LatticeMode = .overlay

    /// The gap between cells, from `design-tokens.json`.
    private let cellGap: CGFloat = 1

    /// The glyph's fixed size, from `design-tokens.json`.
    static let glyphSize = CGSize(width: 34, height: 34)

    init(mode: LatticeMode) {
        self.mode = mode
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isAccessibilityElement = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func configure(patches: [Patch], grid: Int) {
        self.patches = patches
        self.grid = max(grid, 1)
        // A proportion rather than a cell-by-cell reading, because the proportion is what
        // the lattice is for. "14 of 25 patches classified bleached" survives being read
        // aloud; twenty-five separate announcements do not.
        accessibilityLabel = Self.description(patches: patches, grid: self.grid)
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard !patches.isEmpty, let context = UIGraphicsGetCurrentContext() else { return }

        // The centre square, matching how the server tiled the image. Anything else
        // misaligns the cells with the pixels that were actually classified.
        let side = min(rect.width, rect.height)
        let origin = CGPoint(x: (rect.width - side) / 2, y: (rect.height - side) / 2)
        let cell = side / CGFloat(grid)

        // Hard-light keeps the reef's own texture visible through the tint instead of
        // flooding it. Where the blend is unavailable the fallback is normal compositing at
        // the SAME opacity - never a higher one to compensate, which would defeat the point.
        context.setBlendMode(mode == .overlay ? .hardLight : .normal)

        for patch in patches where (0 ..< grid).contains(patch.row) && (0 ..< grid).contains(patch.col) {
            let fill = ReefPalette.condition(patch.label)
                .withAlphaComponent(mode.opacity(confidence: patch.confidence))

            let frame = CGRect(
                x: origin.x + CGFloat(patch.col) * cell + cellGap / 2,
                y: origin.y + CGFloat(patch.row) * cell + cellGap / 2,
                width: max(cell - cellGap, 0),
                height: max(cell - cellGap, 0)
            )
            context.setFillColor(fill.cgColor)
            context.fill(frame)
        }
    }

    static func description(patches: [Patch], grid: Int) -> String {
        let bleached = patches.count { $0.label == .bleached }
        let total = patches.isEmpty ? grid * grid : patches.count
        return "\(bleached) of \(total) patches classified bleached"
    }
}

/// A flat swatch for a photograph with no prediction yet. Absent is not an error.
final class UnassessedGlyphView: UIView {
    init() {
        super.init(frame: .zero)
        backgroundColor = ReefPalette.unassessed.withAlphaComponent(0.35)
        layer.cornerRadius = 6
        layer.cornerCurve = .continuous
        isAccessibilityElement = true
        accessibilityLabel = "Not yet assessed"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }
}
