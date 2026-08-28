package mv.muraka.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mv.muraka.core.common.ApiError
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.data.mapper.capturedAtInstant
import mv.muraka.core.data.mapper.outboxState
import mv.muraka.core.data.mapper.position
import mv.muraka.core.data.mapper.toCacheEntity
import mv.muraka.core.data.mapper.toDomain
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.database.dao.CacheDao
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.CachedDetailEntity
import mv.muraka.core.database.entity.CachedSightingEntity
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.domain.SightingWithDetail
import mv.muraka.core.model.ContributorSighting
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.OutboxState
import mv.muraka.core.model.SightingDisplayStatus
import mv.muraka.core.model.SightingDraft
import mv.muraka.core.network.ErrorMapper
import mv.muraka.core.network.MurakaApi
import mv.muraka.core.network.dto.SightingDetailDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture, history and detail.
 *
 * The merge in [observeMySightings] is the heart of D21: outbox rows and cached server
 * records are held separately and combined only for display, so the interface can never
 * accidentally present a local flag as a server fact.
 */
@Singleton
class SightingRepositoryImpl @Inject constructor(
    private val api: MurakaApi,
    private val outbox: OutboxDao,
    private val cache: CacheDao,
    private val photos: PhotoStore,
    private val tokens: SessionTokenStore,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : SightingRepository {

    /**
     * Queues a sighting. Local only - this returns as soon as the bytes are durably on
     * disk and never waits for the network (NFR7).
     */
    override suspend fun capture(draft: SightingDraft): Result<String> = withContext(dispatchers.io) {
        val problems = draft.validate()
        if (problems.isNotEmpty()) return@withContext Result.failure(ApiError.Validation(problems))

        val userId = tokens.current()?.userId
            ?: return@withContext Result.failure(ApiError.Unauthorized)

        val now = System.currentTimeMillis()
        outbox.enqueue(
            sighting = SightingQueueEntity(
                id = draft.id,
                userId = userId,
                lat = draft.fix.position.lat,
                lon = draft.fix.position.lon,
                locationSource = draft.fix.source.wire,
                locationAccuracyM = draft.fix.accuracyM,
                depthM = draft.depthM,
                capturedAtDevice = draft.capturedAt.toEpochMilli(),
                note = draft.note?.takeIf { it.isNotBlank() },
                selfCondition = draft.selfAssessedCondition?.wire,
                state = OutboxState.QUEUED.wire,
                createdAt = now,
            ),
            photos = draft.photos.mapIndexed { index, photo ->
                PhotoQueueEntity(
                    id = photo.id,
                    sightingId = draft.id,
                    localPath = photo.localPath,
                    ordinal = index,
                    state = OutboxState.QUEUED.wire,
                )
            },
        )
        Result.success(draft.id)
    }

    /**
     * The contributor's own history.
     *
     * Three sources, combined without ever letting one speak for another: the outbox
     * (what we still owe), the photo queue (how much of each is left) and the cache (what
     * the server last said). A sighting the server has never confirmed shows what the
     * outbox knows and nothing more.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMySightings(): Flow<List<ContributorSighting>> = tokens.session.flatMapLatest { session ->
        val userId = session?.userId ?: return@flatMapLatest flowOf(emptyList())

        combine(
            outbox.observeQueue(userId),
            outbox.observePhotos(userId),
            cache.observeSightings(userId),
        ) { queued, queuedPhotos, cached ->
            merge(queued, queuedPhotos, cached)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSighting(id: String): Flow<SightingWithDetail?> = combine(
        observeMySightings().map { list -> list.firstOrNull { it.id == id } },
        cache.observeDetail(id),
    ) { summary, detailRow ->
        if (summary == null) return@combine null

        val detail = detailRow?.let { row ->
            runCatching {
                json.decodeFromString(SightingDetailDto.serializer(), row.json).toDomain()
            }.getOrNull()
        }

        SightingWithDetail(
            summary = summary,
            detail = detail,
            photos = detail?.photos.orEmpty(),
            verifications = detail?.verifications.orEmpty(),
            pendingPhotoPaths = outbox.photosFor(id)
                .filter { it.state != OutboxState.CONFIRMED.wire }
                .map { it.localPath },
        )
    }

    override suspend fun refreshMySightings(): Result<Unit> = withContext(dispatchers.io) {
        val userId = tokens.current()?.userId
            ?: return@withContext Result.failure(ApiError.Unauthorized)

        val response = runCatching { api.listSightings(limit = PAGE_SIZE) }
            .getOrElse { return@withContext Result.failure(ErrorMapper.from(it)) }

        val page = response.body()
        if (!response.isSuccessful || page == null) {
            // A failed refresh leaves the cache exactly as it was. Never show a blank
            // history because the network dropped.
            return@withContext Result.failure(ErrorMapper.from(response))
        }

        val readAt = Instant.now()
        val entities = page.items.map { it.toDomain().toCacheEntity(userId, readAt) }
        cache.upsertSightings(entities)

        // Scenario 10: a sighting removed server-side must stop appearing here. Nothing
        // survives in the interface on local authority alone.
        cache.pruneSightings(userId, entities.map(CachedSightingEntity::id))
        cache.pruneDetails()
        Result.success(Unit)
    }

    /** The read that turns "Checking..." into a real status. */
    override suspend fun refreshSighting(id: String): Result<Unit> = withContext(dispatchers.io) {
        val userId = tokens.current()?.userId
            ?: return@withContext Result.failure(ApiError.Unauthorized)

        val response = runCatching { api.sighting(id) }
            .getOrElse { return@withContext Result.failure(ErrorMapper.from(it)) }

        val detail = response.body()
        if (!response.isSuccessful || detail == null) {
            return@withContext Result.failure(ErrorMapper.from(response))
        }

        val readAt = Instant.now()
        // Replaced wholesale, never merged: an expert's correction, a rejection and an
        // anonymisation all arrive the same way, and there is no merge logic to get wrong.
        cache.upsertSightings(listOf(detail.sighting.toDomain().toCacheEntity(userId, readAt)))
        cache.upsertDetail(
            CachedDetailEntity(
                id = id,
                json = json.encodeToString(SightingDetailDto.serializer(), detail),
                readAt = readAt.toEpochMilli(),
            ),
        )
        Result.success(Unit)
    }

    override suspend fun photoBytes(photoId: String): Result<ByteArray> = withContext(dispatchers.io) {
        val response = runCatching { api.photoImage(photoId) }
            .getOrElse { return@withContext Result.failure(ErrorMapper.from(it)) }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return@withContext Result.failure(ErrorMapper.from(response))
        }
        Result.success(body.use { it.bytes() })
    }

    // -- The merge -----------------------------------------------------------

    private fun merge(
        queued: List<SightingQueueEntity>,
        queuedPhotos: List<PhotoQueueEntity>,
        cached: List<CachedSightingEntity>,
    ): List<ContributorSighting> {
        val outboxById = queued.associateBy { it.id }
        val cachedById = cached.associateBy { it.id }
        val pendingPhotosById = queuedPhotos
            .filter { it.state != OutboxState.CONFIRMED.wire }
            .groupBy { it.sightingId }

        return (outboxById.keys + cachedById.keys).map { id ->
            val row = outboxById[id]
            val record = cachedById[id]
            val server = record?.toDomain()
            val pending = pendingPhotosById[id]?.size ?: 0

            ContributorSighting(
                id = id,
                // The device's capture time is what the contributor recognises, so it
                // wins for display even once the server has stored its own version.
                capturedAt = row?.capturedAtInstant
                    ?: server?.capturedAt
                    ?: Instant.EPOCH,
                position = row?.position ?: server?.position
                    ?: mv.muraka.core.model.Position(0.0, 0.0),
                locationSource = row?.let { LocationSource.fromWire(it.locationSource) }
                    ?: server?.locationSource
                    ?: LocationSource.GPS,
                photoCount = maxOf(server?.photoCount ?: 0, pending),
                displayStatus = SightingDisplayStatus.of(row?.outboxState, server?.status),
                server = server,
                serverReadAt = record?.readAt?.let(Instant::ofEpochMilli),
                outboxState = row?.outboxState,
                failureReason = row?.lastError.takeIf { row?.outboxState == OutboxState.FAILED },
                photosPending = pending,
            )
        }.sortedByDescending { it.capturedAt }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
