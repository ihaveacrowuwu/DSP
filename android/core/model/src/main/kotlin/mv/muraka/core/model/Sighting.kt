package mv.muraka.core.model

import java.time.Instant

/** A coordinate in WGS84, the only spatial type the client needs. */
data class Position(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0
}

/**
 * A sighting as the **server** holds it.
 *
 * There is deliberately no `synced` field and no client-computed status. This type only
 * ever comes from a server response; anything the client knows about work it has not
 * delivered yet lives in the outbox instead, and the two are combined for display by
 * `SightingDisplayStatus`. See D21 in `docs/08` for why that separation is the whole
 * point rather than an implementation detail.
 */
data class Sighting(
    val id: String,
    val contributorId: String,
    val contributorName: String? = null,
    val siteId: String? = null,
    val siteName: String? = null,
    val position: Position,
    val locationSource: LocationSource,
    val locationAccuracyM: Double? = null,
    val depthM: Double? = null,
    val capturedAt: Instant,
    val note: String? = null,
    val selfAssessedCondition: Condition? = null,
    val status: SightingStatus,
    /** When the server received it, as distinct from when it was captured. */
    val createdAt: Instant,
    val photoCount: Int = 0,
    /**
     * Effective condition: the expert's label when one exists, otherwise the model's.
     * Absent until analysis completes. Never render this without [verified] beside it —
     * a model label presented as fact is the NFR13 failure.
     */
    val condition: Condition? = null,
    /** Worst bleached extent across the sighting's photos, 0–1. */
    val severity: Double? = null,
    val confidence: Double? = null,
    /** True only when an expert confirmed or corrected. */
    val verified: Boolean = false,
)

/** One photograph belonging to a sighting, with the model's reading of it if it exists. */
data class Photo(
    val id: String,
    val sightingId: String,
    /** Relative path to the bytes. Requires the bearer token — it is not a public URL. */
    val url: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val createdAt: Instant,
    /** Absent until classification finishes. Absent is not an error. */
    val prediction: Prediction? = null,
)

/** One cell of the inference grid. */
data class Patch(
    val row: Int,
    val col: Int,
    val label: Condition,
    val confidence: Double,
)

/**
 * What the model made of one photograph.
 *
 * [severity] is the number to lead with, not [label]: "62% bleached" tells a contributor
 * something "bleached" does not. [modelVersion] is provenance and must be shown —
 * `fake-0.0.0` means no trained model is loaded yet (D19).
 */
data class Prediction(
    val id: String,
    val photoId: String,
    val modelVersion: String,
    val label: Condition,
    val confidence: Double,
    val severity: Double,
    val patchGrid: Int,
    val patches: List<Patch>,
    val inferenceMs: Int? = null,
    val createdAt: Instant,
)

/** An expert's decision on a sighting. */
data class Verification(
    val id: String,
    val sightingId: String,
    val verifierId: String,
    val verifierName: String? = null,
    val decision: VerificationDecision,
    val label: Condition? = null,
    val rejectReason: RejectReason? = null,
    val comment: String? = null,
    val createdAt: Instant,
)

/** Everything `GET /v1/sightings/{id}` returns. */
data class SightingDetail(
    val sighting: Sighting,
    val photos: List<Photo>,
    val verifications: List<Verification>,
)
