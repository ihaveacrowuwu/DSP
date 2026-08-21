import UIKit

/// Picks the capture-date range.
///
/// Two `UIDatePicker`s in a sheet rather than a calendar grid: UIKit has no range picker, and
/// a hand-built one would be a lot of custom code for a control that is used occasionally.
/// The system pickers bring the locale, the calendar, Dynamic Type and VoiceOver with them.
final class DateRangePickerViewController: UIViewController {
    private let fromSwitch = UISwitch()
    private let toSwitch = UISwitch()
    private let fromPicker = UIDatePicker()
    private let toPicker = UIDatePicker()

    private let initialFrom: Date?
    private let initialTo: Date?
    private let onApply: (Date?, Date?) -> Void

    init(from: Date?, to: Date?, onApply: @escaping (Date?, Date?) -> Void) {
        initialFrom = from
        initialTo = to
        self.onApply = onApply
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Captured between"
        view.backgroundColor = .systemBackground

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Apply",
            style: .done,
            target: self,
            action: #selector(apply)
        )

        [fromPicker, toPicker].forEach {
            $0.datePickerMode = .date
            $0.preferredDatePickerStyle = .compact
            // A sighting cannot have been captured in the future, so neither bound can be.
            $0.maximumDate = Date()
        }

        fromPicker.date = initialFrom ?? Calendar.current.date(byAdding: .month, value: -1, to: Date()) ?? Date()
        toPicker.date = initialTo ?? Date()
        fromSwitch.isOn = initialFrom != nil
        toSwitch.isOn = initialTo != nil

        let stack = UIStackView(arrangedSubviews: [
            row(title: "From", toggle: fromSwitch, picker: fromPicker),
            row(title: "Until", toggle: toSwitch, picker: toPicker),
            UIView(),
        ])
        stack.axis = .vertical
        stack.spacing = 20
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: guide.topAnchor, constant: 24),
            stack.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: guide.bottomAnchor, constant: -20),
        ])

        updateEnabledState()
        fromSwitch.addTarget(self, action: #selector(updateEnabledState), for: .valueChanged)
        toSwitch.addTarget(self, action: #selector(updateEnabledState), for: .valueChanged)
    }

    /// A switch per bound, so "from March, no end" is expressible.
    ///
    /// Without them the only way to mean "everything since March" would be to set an end date
    /// of today, which then silently excludes anything captured later.
    private func row(title: String, toggle: UISwitch, picker: UIDatePicker) -> UIView {
        let label = UILabel()
        label.text = title
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true

        let row = UIStackView(arrangedSubviews: [label, UIView(), picker, toggle])
        row.axis = .horizontal
        row.spacing = 12
        row.alignment = .center
        return row
    }

    @objc private func updateEnabledState() {
        fromPicker.isEnabled = fromSwitch.isOn
        toPicker.isEnabled = toSwitch.isOn
    }

    @objc private func cancel() {
        dismiss(animated: true)
    }

    @objc private func apply() {
        let from = fromSwitch.isOn ? Calendar.current.startOfDay(for: fromPicker.date) : nil

        // The end bound is pushed to the end of the chosen day. Taken as midnight, a sighting
        // captured that afternoon falls outside its own selected day, which reads as the
        // filter being broken.
        let to = toSwitch.isOn
            ? Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: toPicker.date))?
                .addingTimeInterval(-1)
            : nil

        onApply(from, to)
        dismiss(animated: true)
    }
}
