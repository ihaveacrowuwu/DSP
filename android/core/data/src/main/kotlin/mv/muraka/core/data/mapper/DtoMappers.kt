package mv.muraka.core.data.mapper

import mv.muraka.core.model.Condition
import mv.muraka.core.model.ContributorStats
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.Patch
import mv.muraka.core.model.Photo
import mv.muraka.core.model.Position
import mv.muraka.core.model.Prediction
import mv.muraka.core.model.Profile
import mv.muraka.core.model.RejectReason
import mv.muraka.core.model.Role
import mv.muraka.core.model.Session
import mv.muraka.core.model.Sighting
import mv.muraka.core.model.SightingDetail
import mv.muraka.core.model.SightingStatus
import mv.muraka.core.model.User
import mv.muraka.core.model.Verification
import mv.muraka.core.model.VerificationDecision
import mv.muraka.core.network.dto.ContributorStatsDto
import mv.muraka.core.network.dto.MeDto
import mv.muraka.core.network.dto.PatchDto
import mv.muraka.core.network.dto.PhotoDto
import mv.muraka.core.network.dto.PredictionDto
import mv.muraka.core.network.dto.SessionDto
import mv.muraka.core.network.dto.SightingDetailDto
import mv.muraka.core.network.dto.SightingDto
import mv.muraka.core.network.dto.UserDto
import mv.muraka.core.network.dto.VerificationDto

/**
 * Wire types to domain types.
 *
 * This is the boundary the architecture rule protects: a `SightingDto` must never appear
 * above this file. The mapping is deliberately total - unknown enum values fall back to a
 * sane default rather than throwing, so a server that grows a sixth status shows an
 * installed app something odd rather than crashing it.
 */

fun UserDto.toDomain() = User(
    id = id,
    email = email,
    displayName = displayName,
    role = Role.fromWire(role) ?: Role.CONTRIBUTOR,
    status = status,
    createdAt = createdAt,
)

fun ContributorStatsDto.toDomain() = ContributorStats(
    total = total,
    verified = verified,
    pending = pending,
    rejected = rejected,
)

fun MeDto.toDomain() = Profile(user = user.toDomain(), stats = stats.toDomain())

fun SessionDto.toDomain() = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAt = expiresAt,
    user = user.toDomain(),
)

fun SightingDto.toDomain() = Sighting(
    id = id,
    contributorId = contributorId,
    contributorName = contributorName,
    siteId = siteId,
    siteName = siteName,
    position = Position(location.lat, location.lon),
    locationSource = LocationSource.fromWire(locationSource) ?: LocationSource.GPS,
    locationAccuracyM = locationAccuracyM,
    depthM = depthM,
    capturedAt = capturedAt,
    note = note,
    selfAssessedCondition = Condition.fromWire(selfAssessedCondition),
    // An unrecognised status is treated as "still being worked on" rather than as a
    // verdict, which is the safe direction to be wrong in.
    status = SightingStatus.fromWire(status) ?: SightingStatus.PROCESSING,
    createdAt = createdAt,
    photoCount = photoCount,
    condition = Condition.fromWire(condition),
    severity = severity,
    confidence = confidence,
    verified = verified,
)

fun PatchDto.toDomain() = Patch(
    row = row,
    col = col,
    label = Condition.fromWire(label) ?: Condition.HEALTHY,
    confidence = confidence,
)

fun PredictionDto.toDomain() = Prediction(
    id = id,
    photoId = photoId,
    modelVersion = modelVersion,
    label = Condition.fromWire(label) ?: Condition.HEALTHY,
    confidence = confidence,
    severity = severity,
    patchGrid = patchGrid,
    patches = patches.map { it.toDomain() },
    inferenceMs = inferenceMs,
    createdAt = createdAt,
)

fun PhotoDto.toDomain() = Photo(
    id = id,
    sightingId = sightingId,
    url = url,
    width = width,
    height = height,
    bytes = bytes,
    createdAt = createdAt,
    prediction = prediction?.toDomain(),
)

fun VerificationDto.toDomain() = Verification(
    id = id,
    sightingId = sightingId,
    verifierId = verifierId,
    verifierName = verifierName,
    decision = VerificationDecision.fromWire(decision) ?: VerificationDecision.CONFIRMED,
    label = Condition.fromWire(label),
    rejectReason = RejectReason.fromWire(rejectReason),
    comment = comment,
    createdAt = createdAt,
)

fun SightingDetailDto.toDomain() = SightingDetail(
    sighting = sighting.toDomain(),
    photos = photos.map { it.toDomain() },
    verifications = verifications.map { it.toDomain() },
)
