package mv.muraka.core.model

/**
 * The wire vocabulary, exactly as the Go API speaks it.
 *
 * Every enum here carries its `wire` string rather than relying on `name.lowercase()`,
 * because two of them would not survive that (`MANUAL_PIN` → `manual_pin`,
 * `PENDING_PHOTOS` → `pending_photos`) and a silent mismatch would be a 422 the client
 * cannot retry. [fromWire] returns null for an unknown value rather than throwing: a
 * server that grows a new status must not crash an installed app.
 *
 * Read alongside `mobile-shared/integration.md`, which lists the same values.
 */

/** What the reef looks like. Binary by design — see D3 in `docs/08`. */
enum class Condition(val wire: String) {
    HEALTHY("healthy"),
    BLEACHED("bleached"),
    ;

    companion object {
        fun fromWire(value: String?): Condition? = entries.firstOrNull { it.wire == value }
    }
}

/** How the position was obtained. Researchers filter on this, so the distinction is stored. */
enum class LocationSource(val wire: String) {
    GPS("gps"),
    MANUAL_PIN("manual_pin"),
    ;

    companion object {
        fun fromWire(value: String?): LocationSource? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a sighting has reached, server-side. The client never sets this; it only
 * displays it. See the state machine in `mobile-shared/integration.md`.
 */
enum class SightingStatus(val wire: String) {
    PENDING_PHOTOS("pending_photos"),
    PROCESSING("processing"),
    AWAITING_VERIFICATION("awaiting_verification"),
    VERIFIED("verified"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromWire(value: String?): SightingStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** A researcher's decision. The app displays these; contributors cannot make them. */
enum class VerificationDecision(val wire: String) {
    CONFIRMED("confirmed"),
    CORRECTED("corrected"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromWire(value: String?): VerificationDecision? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a photograph was rejected. Shown to the contributor — it is their own record. */
enum class RejectReason(val wire: String) {
    BLURRY("blurry"),
    NOT_CORAL("not_coral"),
    DUPLICATE("duplicate"),
    SPAM("spam"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): RejectReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Account role. The apps only ever sign in as [CONTRIBUTOR]; the other two exist so a
 * researcher signing in on a phone gets an explanation rather than a wall of 403s.
 */
enum class Role(val wire: String) {
    CONTRIBUTOR("contributor"),
    RESEARCHER("researcher"),
    ADMIN("admin"),
    ;

    companion object {
        fun fromWire(value: String?): Role? = entries.firstOrNull { it.wire == value }
    }
}
