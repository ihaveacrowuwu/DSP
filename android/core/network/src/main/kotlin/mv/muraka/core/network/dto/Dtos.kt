package mv.muraka.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The wire format, one type per JSON shape the Go API produces.
 *
 * These never leave `:core:network` - mappers turn them into domain models at the
 * repository boundary. A `SightingDto` appearing in a view model signature is an
 * architecture violation, and the module boundaries make it visible.
 *
 * Every optional field has a default because Go's `omitempty` drops nulls entirely
 * rather than sending `null`, so absence is the normal case, not an error.
 */

/** RFC 3339 in, [Instant] out. Accepts both `...Z` and `...+05:00`, which Go may emit. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        // Instant.parse only accepts the Z form; OffsetDateTime covers the rest.
        return runCatching { Instant.parse(raw) }
            .getOrElse { OffsetDateTime.parse(raw).toInstant() }
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}

@Serializable
data class ErrorDto(
    val error: String = "",
    val message: String = "",
    /** Present on 422 only; maps a request field to its problem. */
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class UserDto(
    val id: String,
    val email: String = "",
    val displayName: String = "",
    val role: String = "contributor",
    val status: String = "active",
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

@Serializable
data class ContributorStatsDto(val total: Int = 0, val verified: Int = 0, val pending: Int = 0, val rejected: Int = 0)

@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    val user: UserDto,
)

@Serializable
data class MeDto(val user: UserDto, val stats: ContributorStatsDto = ContributorStatsDto())

@Serializable
data class PointDto(val lat: Double = 0.0, val lon: Double = 0.0)

@Serializable
data class SightingDto(
    val id: String,
    val contributorId: String = "",
    val contributorName: String? = null,
    val siteId: String? = null,
    val siteName: String? = null,
    val location: PointDto = PointDto(),
    val locationSource: String = "gps",
    val locationAccuracyM: Double? = null,
    val depthM: Double? = null,
    @Serializable(with = InstantSerializer::class) val capturedAt: Instant,
    val note: String? = null,
    val selfAssessedCondition: String? = null,
    val status: String = "pending_photos",
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val photoCount: Int = 0,
    val condition: String? = null,
    val severity: Double? = null,
    val confidence: Double? = null,
    val verified: Boolean = false,
)

@Serializable
data class PatchDto(val row: Int = 0, val col: Int = 0, val label: String = "healthy", val confidence: Double = 0.0)

@Serializable
data class PredictionDto(
    val id: String = "",
    val photoId: String = "",
    val modelVersion: String = "",
    val label: String = "healthy",
    val confidence: Double = 0.0,
    val severity: Double = 0.0,
    val patchGrid: Int = 0,
    val patches: List<PatchDto> = emptyList(),
    val inferenceMs: Int? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

@Serializable
data class PhotoDto(
    val id: String,
    val sightingId: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    /** Absent until classification finishes. Absent is not an error. */
    val prediction: PredictionDto? = null,
)

@Serializable
data class VerificationDto(
    val id: String,
    val sightingId: String = "",
    val verifierId: String = "",
    val verifierName: String? = null,
    val decision: String = "confirmed",
    val label: String? = null,
    val rejectReason: String? = null,
    val comment: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

@Serializable
data class SightingDetailDto(
    val sighting: SightingDto,
    val photos: List<PhotoDto> = emptyList(),
    val verifications: List<VerificationDto> = emptyList(),
)

@Serializable
data class SightingPageDto(
    val items: List<SightingDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

// -- Requests ----------------------------------------------------------------

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/**
 * `POST /v1/sightings`.
 *
 * [id] is the client's own UUIDv7 and is what makes the whole submission idempotent
 * see `mobile-shared/sync-protocol.md`. The server resolves `siteId` itself from the
 * coordinate, so the client must not send one.
 */
@Serializable
data class CreateSightingRequest(
    val id: String,
    val lat: Double,
    val lon: Double,
    val locationSource: String,
    val locationAccuracyM: Double? = null,
    val depthM: Double? = null,
    @Serializable(with = InstantSerializer::class) val capturedAt: Instant,
    val note: String? = null,
    val selfAssessedCondition: String? = null,
)

@Serializable
data class PhotoUploadResponse(
    val photoId: String = "",
    val sightingId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    /** False when this was a replay of an upload the server already had. */
    val queued: Boolean = false,
)

@Serializable
data class AtollDto(val id: String, val name: String = "", val code: String = "", val centroid: PointDto = PointDto())
