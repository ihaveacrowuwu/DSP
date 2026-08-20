package mv.muraka.core.data.mapper

import mv.muraka.core.database.entity.CachedSightingEntity
import mv.muraka.core.database.entity.SightingQueueEntity
import mv.muraka.core.model.Condition
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.OutboxState
import mv.muraka.core.model.Position
import mv.muraka.core.model.Sighting
import mv.muraka.core.model.SightingStatus
import java.time.Instant

/** Room rows to domain types, and back. Instants are epoch milliseconds on disk. */

fun CachedSightingEntity.toDomain() = Sighting(
    id = id,
    contributorId = userId,
    siteName = siteName,
    position = Position(lat, lon),
    locationSource = LocationSource.fromWire(locationSource) ?: LocationSource.GPS,
    depthM = depthM,
    capturedAt = Instant.ofEpochMilli(capturedAt),
    note = note,
    status = SightingStatus.fromWire(status) ?: SightingStatus.PROCESSING,
    createdAt = Instant.ofEpochMilli(createdAt),
    photoCount = photoCount,
    condition = Condition.fromWire(condition),
    severity = severity,
    confidence = confidence,
    verified = verified,
)

fun Sighting.toCacheEntity(userId: String, readAt: Instant) = CachedSightingEntity(
    id = id,
    userId = userId,
    lat = position.lat,
    lon = position.lon,
    locationSource = locationSource.wire,
    capturedAt = capturedAt.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
    status = status.wire,
    photoCount = photoCount,
    condition = condition?.wire,
    severity = severity,
    confidence = confidence,
    verified = verified,
    depthM = depthM,
    note = note,
    siteName = siteName,
    readAt = readAt.toEpochMilli(),
)

/** The outbox row's own view of a sighting, for showing one that has never been sent. */
val SightingQueueEntity.outboxState: OutboxState
    get() = OutboxState.fromWire(state) ?: OutboxState.QUEUED

val SightingQueueEntity.position: Position get() = Position(lat, lon)

val SightingQueueEntity.capturedAtInstant: Instant get() = Instant.ofEpochMilli(capturedAtDevice)
