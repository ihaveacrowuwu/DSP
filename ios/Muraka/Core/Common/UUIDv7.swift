import Foundation
import os

/// UUIDv7 — a time-ordered UUID, generated on the device at capture time.
///
/// This is the single rule that makes the whole offline protocol simple: because the client
/// owns the id, the server can upsert on it, so a retry after a timeout, after a killed
/// process, or after a lost response creates nothing new. The client never has to ask "did
/// that one get through?" — it can just send again.
///
/// `Foundation.UUID()` is v4 and unordered, so this is written out. v7 is time-ordered,
/// which keeps PostgreSQL's index locality good and makes the outbox naturally
/// chronological — the queue sorts by id.
///
/// Layout (RFC 9562 §5.7): 48-bit millisecond timestamp, 4-bit version, 12-bit `rand_a`,
/// 2-bit variant, 62-bit `rand_b`.
///
/// `rand_a` is used as a **monotonic counter** within a millisecond (RFC 9562 "method 1")
/// rather than as random bits. Without that, five photographs captured in the same
/// millisecond get ids in random order, and the researcher's queue then shows them in an
/// order that is not the order they were taken.
enum UUIDv7 {
    /// The counter state, behind a lock so the type stays `Sendable` without `@unchecked`.
    private struct Counter {
        var lastMillis: Int64 = -1
        var value: UInt16 = 0
    }

    private static let state = OSAllocatedUnfairLock(initialState: Counter())

    private static let counterMax: UInt16 = 0x0FFF

    /// A new id string, lowercase and hyphenated, as the API expects.
    static func generate(millis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        let (stamp, counter) = state.withLock { counter -> (Int64, UInt16) in
            // A clock that jumps backwards must not produce ids that sort before ones
            // already in the outbox: hold the previous millisecond instead. The drift is
            // bounded and disappears as soon as real time catches up.
            var stamp = max(millis, counter.lastMillis)

            if stamp != counter.lastMillis {
                counter.value = UInt16.random(in: 0 ... (counterMax / 2))
            } else if counter.value < counterMax {
                counter.value += 1
            } else {
                // 4096 ids inside one millisecond is not reachable from a capture flow,
                // but rolling over silently would break ordering, so borrow from the next
                // millisecond instead.
                stamp += 1
                counter.value = 0
            }

            counter.lastMillis = stamp
            return (stamp, counter.value)
        }

        var bytes = [UInt8](repeating: 0, count: 16)

        // 48-bit big-endian millisecond timestamp.
        let unsignedStamp = UInt64(bitPattern: stamp)
        for index in 0 ..< 6 {
            bytes[index] = UInt8((unsignedStamp >> (40 - 8 * UInt64(index))) & 0xFF)
        }

        // Version 7 in the high nibble of byte 6, then the counter across bytes 6-7.
        bytes[6] = 0x70 | UInt8((counter >> 8) & 0x0F)
        bytes[7] = UInt8(counter & 0xFF)

        // Variant `10` in the top two bits of byte 8, random for the rest.
        var randomTail = [UInt8](repeating: 0, count: 8)
        for index in randomTail.indices { randomTail[index] = UInt8.random(in: 0 ... 255) }
        randomTail[0] = (randomTail[0] & 0x3F) | 0x80
        for index in 0 ..< 8 { bytes[8 + index] = randomTail[index] }

        return format(bytes)
    }

    /// The millisecond a v7 id was generated in. Used by tests and by queue ordering.
    static func timestampMillis(of uuid: String) -> Int64? {
        let hex = uuid.replacingOccurrences(of: "-", with: "")
        guard hex.count == 32, let value = UInt64(hex.prefix(12), radix: 16) else { return nil }
        return Int64(value)
    }

    /// True when the string parses as a UUID of any version. The server requires no more.
    static func isValid(_ value: String) -> Bool { UUID(uuidString: value) != nil }

    private static func format(_ bytes: [UInt8]) -> String {
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let groups = [
            hex.prefix(8),
            hex.dropFirst(8).prefix(4),
            hex.dropFirst(12).prefix(4),
            hex.dropFirst(16).prefix(4),
            hex.dropFirst(20).prefix(12),
        ]
        return groups.map(String.init).joined(separator: "-")
    }
}
