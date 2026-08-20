import Foundation
import Security

/// Where session tokens live: the iOS Keychain, never `UserDefaults`.
///
/// Two rules from `sync-protocol.md` are load-bearing here, and both are easy to get wrong:
///
/// 1. **Persist the new refresh token immediately.** Refresh tokens are single-use — the old
///    one is dead the moment the server answers — so both tokens are written as one encoded
///    blob under one Keychain item, which makes the write atomic. Storing them as two items
///    leaves a window where a crash loses the new refresh token and signs the contributor
///    out for no reason.
/// 2. **The queue is not part of the session.** ``clear()`` deletes tokens and nothing else.
///    Queued sightings belong to the account that captured them and wait for it to come back.
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` rather than `WhenUnlocked`: the outbox
/// drains from a background task while the phone is locked in a dry bag, and tokens that are
/// unreadable then would stop it. `ThisDeviceOnly` keeps them out of an iCloud Keychain
/// backup, so restoring a backup onto another phone cannot resurrect a session.
actor TokenStore {
    private let service = "mv.muraka.session"
    private let account = "current"

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    /// The stored session, or nil when there is none.
    func current() -> StoredSession? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }

        return try? decoder.decode(StoredSession.self, from: data)
    }

    /// Writes both tokens as one item, so the pair can never be half-updated.
    ///
    /// Returns whether it worked, and callers must act on that. A silent failure here is
    /// indistinguishable from a wrong password from the contributor's side — the app simply
    /// returns to sign-in — and it cost an afternoon to find exactly once.
    @discardableResult
    func save(_ session: StoredSession) -> Bool {
        guard let data = try? encoder.encode(session) else { return false }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]

        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        // Update in place if it exists; add if it does not. `SecItemAdd` on an existing item
        // fails with errSecDuplicateItem rather than replacing, which is the usual way this
        // ends up silently never saving.
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }

        let addStatus = SecItemAdd(
            query.merging(attributes) { current, _ in current } as CFDictionary,
            nil
        )
        return addStatus == errSecSuccess
    }

    /// Forgets the session. Leaves the outbox completely alone.
    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    /// The signed-in account's id, or nil.
    func currentUserID() -> String? { current()?.userID }
}
