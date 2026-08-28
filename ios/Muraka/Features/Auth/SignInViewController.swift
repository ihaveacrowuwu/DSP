import UIKit

/// The only screen in the app that needs connectivity (NFR7).
///
/// Everything else works on a boat; this cannot, and says so plainly rather than queueing a
/// sign-in that could never succeed.
final class SignInViewController: UIViewController {
    private let container: AppContainer
    private let onSignedIn: () -> Void

    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let nameField = UITextField()
    private let emailField = UITextField()
    private let passwordField = UITextField()
    private let messageLabel = UILabel()

    /// The glass containers the fields live in. Built in `buildHierarchy`, because they wrap
    /// fields that must exist first.
    private var nameFieldContainer: UIView?
    private var emailFieldContainer: UIView?
    private var passwordFieldContainer: UIView?
    private let submitButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.primary))
    private let toggleButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.quiet))
    private let spinner = UIActivityIndicatorView(style: .medium)

    private var isRegistering = false

    init(container: AppContainer, onSignedIn: @escaping () -> Void) {
        self.container = container
        self.onSignedIn = onSignedIn
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildHierarchy()
        applyMode()
        prefillForUITestsIfRequested()
    }

    /// Fills the form from a **debug-only** launch argument.
    ///
    /// It exists because XCUITest cannot reliably type into a `isSecureTextEntry` field
    /// without a software keyboard, and the simulator does not always present one - so the
    /// UI test would be flaky for reasons that have nothing to do with the app. Everything
    /// the test actually cares about happens after sign-in.
    ///
    /// Compiled out of release builds entirely, so a shipped binary cannot be prefilled, and
    /// it only ever fills the fields - it never signs in by itself.
    private func prefillForUITestsIfRequested() {
        #if DEBUG
            let arguments = ProcessInfo.processInfo.arguments
            guard let flag = arguments.firstIndex(of: "-MurakaUITestCredentials"),
                  arguments.indices.contains(flag + 1)
            else { return }

            let parts = arguments[flag + 1].split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { return }

            emailField.text = String(parts[0])
            passwordField.text = String(parts[1])
        #endif
    }

    private func buildHierarchy() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        // Keeps the submit button reachable when the keyboard is up, which is the single
        // most common way a sign-in form ends up unusable.
        scrollView.keyboardDismissMode = .interactive
        view.addSubview(scrollView)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 16
        scrollView.addSubview(stack)

        let title = UILabel()
        title.text = "Muraka"
        title.font = .preferredFont(forTextStyle: .largeTitle)
        title.adjustsFontForContentSizeCategory = true

        let subtitle = UILabel()
        subtitle.text = "Reef condition monitoring for the Maldives."
        subtitle.font = .preferredFont(forTextStyle: .body)
        subtitle.adjustsFontForContentSizeCategory = true
        subtitle.textColor = .secondaryLabel
        subtitle.numberOfLines = 0

        configure(nameField, placeholder: "Your name", content: .name)
        configure(emailField, placeholder: "Email", content: .username, keyboard: .emailAddress)
        configure(passwordField, placeholder: "Password", content: .password, secure: true)

        // Each field is presented inside a glass container, because UIKit has no glass
        // border style - see GlassSurface.wrapTextField. The containers are what the stack
        // lays out; `nameField` etc. are still what the code reads and writes.
        nameFieldContainer = GlassSurface.wrapTextField(nameField)
        emailFieldContainer = GlassSurface.wrapTextField(emailField)
        passwordFieldContainer = GlassSurface.wrapTextField(passwordField)

        let passwordHint = UILabel()
        // The server's rule, stated before it is broken rather than after.
        passwordHint.text = "At least 10 characters"
        passwordHint.font = .preferredFont(forTextStyle: .caption1)
        passwordHint.adjustsFontForContentSizeCategory = true
        passwordHint.textColor = .secondaryLabel

        messageLabel.font = .preferredFont(forTextStyle: .subheadline)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = ReefPalette.rust
        messageLabel.numberOfLines = 0
        messageLabel.isHidden = true

        submitButton.addTarget(self, action: #selector(submit), for: .touchUpInside)
        toggleButton.addTarget(self, action: #selector(toggleMode), for: .touchUpInside)

        [title, subtitle, nameFieldContainer, emailFieldContainer, passwordFieldContainer,
         passwordHint, messageLabel, submitButton, toggleButton]
            .compactMap { $0 }
            .forEach(stack.addArrangedSubview)

        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.hidesWhenStopped = true
        view.addSubview(spinner)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: guide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            // Pinned to the keyboard rather than to the safe area, so the submit button
            // stays reachable when the keyboard is up - the single most common way a
            // sign-in form ends up unusable. `keyboardLayoutGuide` collapses to the safe
            // area when no keyboard is shown, so this is correct in both states.
            scrollView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -24),

            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private func configure(
        _ field: UITextField,
        placeholder: String,
        content: UITextContentType,
        keyboard: UIKeyboardType = .default,
        secure: Bool = false
    ) {
        field.placeholder = placeholder
        field.borderStyle = .roundedRect
        field.textContentType = content
        field.keyboardType = keyboard
        field.isSecureTextEntry = secure
        field.autocapitalizationType = keyboard == .emailAddress ? .none : .words
        field.autocorrectionType = .no
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.accessibilityLabel = placeholder
    }

    private func applyMode() {
        nameFieldContainer?.isHidden = !isRegistering
        submitButton.setTitle(isRegistering ? "Create account" : "Sign in", for: .normal)
        toggleButton.setTitle(
            isRegistering ? "I already have an account" : "Create a contributor account",
            for: .normal
        )
    }

    @objc private func toggleMode() {
        isRegistering.toggle()
        messageLabel.isHidden = true
        applyMode()
    }

    @objc private func submit() {
        let email = emailField.text ?? ""
        let password = passwordField.text ?? ""
        let name = nameField.text ?? ""

        guard !email.isEmpty, !password.isEmpty, !(isRegistering && name.isEmpty) else {
            show(message: "Fill in every field to continue.")
            return
        }

        setBusy(true)
        Task {
            do {
                _ = isRegistering
                    ? try await container.authRepository.register(
                        email: email, password: password, displayName: name
                    )
                    : try await container.authRepository.signIn(email: email, password: password)

                setBusy(false)
                onSignedIn()
            } catch {
                setBusy(false)
                show(message: Self.describe(ApiError.from(error)))
            }
        }
    }

    private func setBusy(_ busy: Bool) {
        submitButton.isEnabled = !busy
        if busy {
            spinner.startAnimating()
        } else {
            spinner.stopAnimating()
        }
    }

    private func show(message: String) {
        messageLabel.text = message
        messageLabel.isHidden = false
        UIAccessibility.post(notification: .announcement, argument: message)
    }

    private static func describe(_ error: ApiError) -> String {
        switch error {
        // Named explicitly rather than folded into a generic failure: "you are offline" is
        // the one message that tells a contributor to stop retrying and wait, and this is the
        // only screen where being offline is genuinely a problem.
        case .offline:
            "No connection. Signing in is the only thing Muraka needs the network for — "
                + "everything else works offline."
        case .invalidCredentials, .emailTaken, .accountDisabled, .validation:
            error.message
        default:
            "Could not reach Muraka. Try again in a moment."
        }
    }
}
