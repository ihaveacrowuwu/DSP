package mv.muraka.core.model

/**
 * The life of a row in the outbox.
 *
 * This is a string-valued state machine and **not** a `synced` boolean, because a boolean
 * cannot express *"we sent it and do not know what happened"* — and that is exactly the
 * state a lost response leaves you in. Collapsing it to `false` re-sends work that already
 * succeeded; collapsing it to `true` tells the contributor their sighting is safe when
 * nobody has confirmed it. The second is the worst failure this system can have, because
 * nobody goes looking for it.
 *
 * ```
 * QUEUED ──▶ SENDING ──▶ IN_DOUBT ──▶ CONFIRMED ──▶ (row dropped, record cached)
 *    ▲          │            │
 *    └──────────┴────────────┘   transient failure: back to QUEUED, with backoff
 *               │
 *               └──▶ FAILED   terminal (422/409/413) — needs the contributor
 * ```
 *
 * [OutboxState] decides what to send next. It is **never** what the contributor is shown:
 * see [SightingDisplayStatus].
 */
enum class OutboxState(val wire: String) {
    /** Bytes on disk, nothing sent. */
    QUEUED("queued"),

    /** A request is in flight right now. */
    SENDING("sending"),

    /**
     * Sent, outcome not durably recorded. Reconciliation asks the server rather than
     * guessing. Displayed as "Checking…", which is an honest thing to say.
     */
    IN_DOUBT("in_doubt"),

    /** The server acknowledged it. The row's job is over. */
    CONFIRMED("confirmed"),

    /** Terminally rejected. Needs the contributor to do something. */
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): OutboxState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What the contributor is actually told.
 *
 * **Only the first two may be stated on the client's own authority.** Everything below
 * that line is the server's answer or nothing at all. There is deliberately no "Synced":
 * a local flag saying the upload worked is a claim, not a fact, and the app either has
 * the server's answer or says it is still checking. See D21 in `docs/08`.
 *
 * The [label] strings are a **cross-platform contract**, not chrome. The same sighting
 * must not read "Analysing" on Android and "Processing" on iOS, so these exact strings
 * also appear in `ios/Muraka/Core/Model/SightingDisplayStatus.swift`, and
 * `scripts/check_status_vocabulary.py` fails the build if the two ever drift.
 *
 * They are not in `strings.xml` because they are shared with another platform rather than
 * localised — localisation is an explicitly C-tier, out-of-scope item in `docs/08`.
 */
enum class SightingDisplayStatus(
    val label: String,
    /** True for the two states the client may assert without asking the server. */
    val isClientAsserted: Boolean,
) {
    WAITING_TO_UPLOAD("Waiting to upload", true),
    UPLOADING("Uploading", true),

    /** Accepted, not yet read back. The honest name for "we do not know yet". */
    CHECKING("Checking…", false),

    PHOTOS_PENDING("Photos pending", false),
    ANALYSING("Analysing", false),
    AWAITING_REVIEW("Awaiting expert review", false),

    /**
     * Note the wording: "Verified by an expert", not "Verified".
     *
     * `sync-protocol.md` and `integration.md` disagreed here — the first said "Verified by
     * an expert", the second "Verified". The longer form wins because it carries the
     * provenance distinction NFR13 asks for **in the word itself**, so the status survives
     * a greyscale screenshot with no chip beside it. Both documents now say this.
     */
    VERIFIED_BY_EXPERT("Verified by an expert", false),

    /** Shown with the reason when the server gives one. */
    NOT_USABLE("Not usable", false),

    /** The row failed terminally and needs the contributor. Carries its own reason. */
    FAILED("Could not upload", true),
    ;

    companion object {
        /**
         * The single place outbox state and server status are combined.
         *
         * [serverStatus] is null when the server has never answered for this sighting —
         * which is normal offline, and is exactly when the client must NOT invent a
         * status. Note the precedence: an in-flight upload is reported as such even if a
         * stale server status exists, because that is a fact about our own queue; but a
         * row we merely *think* succeeded never outranks the server's answer.
         */
        fun of(outboxState: OutboxState?, serverStatus: SightingStatus?): SightingDisplayStatus =
            when (outboxState) {
                OutboxState.QUEUED -> WAITING_TO_UPLOAD
                OutboxState.SENDING -> UPLOADING
                OutboxState.FAILED -> FAILED
                // Sent, no server answer yet: the one case where "we do not know" is the
                // truthful thing to display.
                OutboxState.IN_DOUBT -> fromServer(serverStatus) ?: CHECKING
                OutboxState.CONFIRMED, null -> fromServer(serverStatus) ?: CHECKING
            }

        private fun fromServer(status: SightingStatus?): SightingDisplayStatus? = when (status) {
            SightingStatus.PENDING_PHOTOS -> PHOTOS_PENDING
            SightingStatus.PROCESSING -> ANALYSING
            SightingStatus.AWAITING_VERIFICATION -> AWAITING_REVIEW
            SightingStatus.VERIFIED -> VERIFIED_BY_EXPERT
            SightingStatus.REJECTED -> NOT_USABLE
            null -> null
        }
    }
}
