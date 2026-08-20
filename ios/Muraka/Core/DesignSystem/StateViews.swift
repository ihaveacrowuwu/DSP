import UIKit

/// The states every data-backed screen has to handle.
///
/// They live here rather than being rewritten per screen because the loading, empty and error
/// paths are where interfaces are usually thinnest — and in this app the error path is not an
/// edge case: a phone on a boat spends most of its life in it.
final class MessageStateView: UIStackView {
    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let actionButton = UIButton(configuration: .filled())
    private var action: (() -> Void)?

    init(
        title: String,
        body: String,
        systemImage: String? = nil,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        super.init(frame: .zero)
        axis = .vertical
        alignment = .center
        spacing = 12
        isLayoutMarginsRelativeArrangement = true
        layoutMargins = UIEdgeInsets(top: 32, left: 32, bottom: 32, right: 32)

        if let systemImage {
            let imageView = UIImageView(image: UIImage(systemName: systemImage))
            imageView.tintColor = .secondaryLabel
            imageView.contentMode = .scaleAspectFit
            imageView.setContentHuggingPriority(.required, for: .vertical)
            addArrangedSubview(imageView)
        }

        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        addArrangedSubview(titleLabel)

        bodyLabel.text = body
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0
        addArrangedSubview(bodyLabel)

        // Always offer a way forward where one could conceivably help. A dead end with no
        // action is what makes a contributor close the app with a sighting still queued.
        if let actionTitle, let action {
            self.action = action
            actionButton.setTitle(actionTitle, for: .normal)
            actionButton.addTarget(self, action: #selector(runAction), for: .touchUpInside)
            addArrangedSubview(actionButton)
        }
    }

    @available(*, unavailable)
    required init(coder _: NSCoder) {
        // UIStackView's init(coder:) is non-failable, so this cannot return nil the way a
        // UIView subclass's can. Nothing in this app comes from a XIB, so reaching it is a
        // programmer error rather than a runtime condition.
        fatalError("Muraka builds its views in code; init(coder:) is unused")
    }

    @objc private func runAction() { action?() }
}

extension UIViewController {
    /// Shows a message the contributor can dismiss. Used for failures that are not a state
    /// of the screen — a retry that did not work, say.
    func presentMessage(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}
