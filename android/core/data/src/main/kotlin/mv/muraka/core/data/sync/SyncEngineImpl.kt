package mv.muraka.core.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mv.muraka.core.common.ApiError
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.common.ServerClock
import mv.muraka.core.data.mapper.toCacheEntity
import mv.muraka.core.data.mapper.toDomain
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.database.dao.CacheDao
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.CachedDetailEntity
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.database.entity.SightingQueueEntity
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.domain.SyncEngine
import mv.muraka.core.domain.SyncOutcome
import mv.muraka.core.model.OutboxState
import mv.muraka.core.network.ErrorMapper
import mv.muraka.core.network.MurakaApi
import mv.muraka.core.network.dto.CreateSightingRequest
import mv.muraka.core.network.dto.SightingDetailDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The drain loop.
 *
 * The algorithm is `mobile-shared/sync-protocol.md`, and the shape of it is:
 *
 * ```
 * for each row the contributor still owes, oldest capture first:
 *     1. work out what the SERVER already has  (GET /v1/sightings/{id})
 *     2. send only what is missing             (metadata, then the missing photos)
 *     3. read the sighting back                (GET again)
 *     4. only then delete anything local
 * ```
 *
 * Step 1 is what makes the whole thing safe. The client never has to guess whether a
 * write landed, because the ids are its own: `404` means the server has nothing, and
 * `200` carries `photos[]`, whose ids are also the client's — so the difference is the
 * exact set still missing, not an estimate. No bookkeeping column could be more
 * trustworthy, because it is the database answering.
 *
 * Step 4 is where data is lost if you get it wrong. Nothing local is deleted on the
 * strength of an upload call returning, and nothing is deleted on a response that could
 * not be parsed.
 */
@Singleton
class SyncEngineImpl @Inject constructor(
    private val api: MurakaApi,
    private val outbox: OutboxDao,
    private val cache: CacheDao,
    private val photos: PhotoStore,
    private val tokens: SessionTokenStore,
    private val serverClock: ServerClock,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : SyncEngine {

    /**
     * One drain at a time.
     *
     * Connectivity returning and the periodic task firing together is not a rare race —
     * it is the *ordinary* case when a boat comes back into range, and two concurrent
     * drains would upload the same photograph twice. Harmless, because of the ids, but it
     * doubles a diver's tethering allowance for nothing.
     */
    private val drainLock = Mutex()

    override suspend fun drain(): SyncOutcome = withContext(dispatchers.io) {
        // tryLock rather than withLock: if a drain is already running, this one has
        // nothing to add and should return rather than queue behind it.
        if (!drainLock.tryLock()) return@withContext SyncOutcome()
        try {
            drainLocked()
        } finally {
            drainLock.unlock()
        }
    }

    private suspend fun drainLocked(): SyncOutcome {
        // No session means nothing may be uploaded. Rows are left exactly as they are —
        // they belong to their owner and wait for that account to sign back in.
        val userId = tokens.current()?.userId ?: return SyncOutcome()

        var confirmed = 0
        var uploaded = 0
        var failed = 0

        val due = outbox.dueForSync(userId, now = System.currentTimeMillis())
        for (row in due) {
            when (val outcome = processRow(row)) {
                is RowOutcome.Confirmed -> {
                    confirmed++
                    uploaded += outcome.photosUploaded
                }

                is RowOutcome.StillPending -> uploaded += outcome.photosUploaded

                RowOutcome.FailedTerminally -> failed++

                // Nothing is reachable. Continuing would burn the remaining rows'
                // attempt counters against a network that is not there, and each one
                // would then back off as if it had genuinely failed.
                RowOutcome.Offline -> return SyncOutcome(
                    sightingsConfirmed = confirmed,
                    photosUploaded = uploaded,
                    stillPending = pendingCount(userId),
                    failedTerminally = failed,
                    offline = true,
                )

                RowOutcome.SessionEnded -> return SyncOutcome(
                    sightingsConfirmed = confirmed,
                    photosUploaded = uploaded,
                    stillPending = pendingCount(userId),
                    failedTerminally = failed,
                    needsSignIn = true,
                )
            }
        }

        return SyncOutcome(
            sightingsConfirmed = confirmed,
            photosUploaded = uploaded,
            stillPending = pendingCount(userId),
            failedTerminally = failed,
        )
    }

    private suspend fun pendingCount(userId: String): Int =
        outbox.dueForSync(userId, now = Long.MAX_VALUE, limit = Int.MAX_VALUE).size

    // ── One row ─────────────────────────────────────────────────────────────

    private sealed interface RowOutcome {
        data class Confirmed(val photosUploaded: Int) : RowOutcome
        data class StillPending(val photosUploaded: Int) : RowOutcome
        data object FailedTerminally : RowOutcome
        data object Offline : RowOutcome
        data object SessionEnded : RowOutcome
    }

    private suspend fun processRow(row: SightingQueueEntity): RowOutcome {
        val localPhotos = outbox.photosFor(row.id)
        outbox.setSightingState(row.id, OutboxState.SENDING.wire)

        val alreadyHeld = when (val known = establishServerState(row)) {
            is Known.Failed -> return handleFailure(row, known.error)
            is Known.PhotoIds -> known.ids
        }

        // Upload only what the server does not already hold. Re-sending a photograph it
        // has is harmless — it answers 200 — but it wastes a diver's tethering allowance.
        var uploaded = 0
        for (photo in localPhotos.filterNot { it.id in alreadyHeld }) {
            when (val sent = uploadPhoto(row.id, photo)) {
                Step.Ok -> uploaded++
                // A photograph that will not upload does not fail the sighting: the
                // metadata is on the server and the record is real, just short. The row
                // stays and the sync screen offers the contributor a way out.
                is Step.Failed -> return handleFailure(row, sent.error, photosUploaded = uploaded)
            }
        }

        return confirmOrKeep(row, localPhotos, uploaded)
    }

    /** What the server already has, and whatever sending it takes to make that true. */
    private sealed interface Known {
        data class PhotoIds(val ids: Set<String>) : Known
        data class Failed(val error: ApiError) : Known
    }

    /**
     * Works out what the server holds, creating the metadata if it holds nothing.
     *
     * A row that has never left the device cannot exist server-side, so the reconciliation
     * GET would be a guaranteed 404 and a wasted round trip on the common path. Everything
     * else asks first.
     */
    private suspend fun establishServerState(row: SightingQueueEntity): Known {
        val neverSent = row.state == OutboxState.QUEUED.wire && row.attempts == 0

        val held: Set<String> = if (neverSent) {
            emptySet()
        } else {
            when (val fetched = fetchServerState(row.id)) {
                is Fetch.Failed -> return Known.Failed(fetched.error)
                Fetch.Absent -> emptySet()
                is Fetch.Found -> fetched.detail.photos.map { it.id }.toSet()
            }
        }

        // Nothing server-side, in either branch: send the metadata. `201` and `200` are
        // treated identically — the client never has to know which it was.
        if (held.isEmpty()) {
            when (val created = createMetadata(row)) {
                is Step.Failed -> return Known.Failed(created.error)
                Step.Ok -> Unit
            }
        }
        return Known.PhotoIds(held)
    }

    /**
     * The read-back, and the only place local data may be deleted.
     *
     * Uploading is not finishing: until the database itself lists every photo id, the row
     * stays and the contributor is told "Checking…" rather than something reassuring.
     */
    private suspend fun confirmOrKeep(
        row: SightingQueueEntity,
        localPhotos: List<PhotoQueueEntity>,
        uploaded: Int,
    ): RowOutcome {
        val detail = when (val readBack = fetchServerState(row.id)) {
            is Fetch.Failed -> return handleFailure(row, readBack.error, photosUploaded = uploaded)
            // A 404 immediately after a successful create means something is genuinely
            // wrong. Leave the row alone and let the next pass find out.
            Fetch.Absent -> return handleFailure(row, ApiError.Timeout, photosUploaded = uploaded)
            is Fetch.Found -> readBack.detail
        }

        cacheDetail(row.userId, detail)

        val heldByServer = detail.photos.map { it.id }.toSet()
        if (localPhotos.any { it.id !in heldByServer }) {
            return handleFailure(row, ApiError.Timeout, photosUploaded = uploaded)
        }

        // The database has confirmed every photograph. Only now may anything local go.
        localPhotos.forEach { photos.delete(it.id) }
        outbox.delete(row.id)
        return RowOutcome.Confirmed(uploaded)
    }

    // ── Steps ───────────────────────────────────────────────────────────────

    private sealed interface Step {
        data object Ok : Step
        data class Failed(val error: ApiError) : Step
    }

    /** What the server has under an id. [Absent] is an answer, not a failure. */
    private sealed interface Fetch {
        data class Found(val detail: SightingDetailDto) : Fetch
        data object Absent : Fetch
        data class Failed(val error: ApiError) : Fetch
    }

    /**
     * The reconciliation primitive.
     *
     * `GET /v1/sightings/{id}` answers both questions at once, because the ids are the
     * client's own: `404` means nothing was ever stored, and `200` carries the photo ids
     * the server actually holds — so the difference is the exact set still missing.
     */
    private suspend fun fetchServerState(id: String): Fetch {
        val response = runCatching { api.sighting(id) }
            .getOrElse { return Fetch.Failed(ErrorMapper.from(it)) }

        val body = response.body()
        return when {
            response.isSuccessful && body != null -> Fetch.Found(body)
            // A 200 whose body would not parse is NOT an answer. Treating it as one would
            // delete a diver's local files on the strength of something we could not read.
            response.isSuccessful -> Fetch.Failed(ApiError.Unexpected("unparseable sighting body"))
            response.code() == HTTP_NOT_FOUND -> Fetch.Absent
            else -> Fetch.Failed(ErrorMapper.from(response))
        }
    }

    private suspend fun createMetadata(row: SightingQueueEntity): Step {
        val request = CreateSightingRequest(
            id = row.id,
            lat = row.lat,
            lon = row.lon,
            locationSource = row.locationSource,
            locationAccuracyM = row.locationAccuracyM,
            depthM = row.depthM,
            // Device time translated into the server's, so a wrong device clock does not
            // turn a captured sighting into a terminal 422.
            capturedAt = serverClock.toServerTime(Instant.ofEpochMilli(row.capturedAtDevice)),
            note = row.note,
            selfAssessedCondition = row.selfCondition,
        )

        val response = runCatching { api.createSighting(request) }
            .getOrElse { return Step.Failed(ErrorMapper.from(it)) }

        // 201 created and 200 replay are treated identically. The client never has to know
        // which it was — that is the entire point of the client generating the id.
        return if (response.isSuccessful) Step.Ok else Step.Failed(ErrorMapper.from(response))
    }

    private suspend fun uploadPhoto(sightingId: String, photo: PhotoQueueEntity): Step {
        val file = photos.fileFor(photo.id)
        if (!file.exists()) {
            // The bytes are gone and cannot be recovered — a wiped app directory, or a
            // file that never finished being written. Honest failure beats a silent skip
            // that would leave the sighting permanently one photograph short.
            outbox.recordPhotoAttempt(photo.id, OutboxState.FAILED.wire, "the photograph's file is missing")
            return Step.Failed(ApiError.Validation(mapOf("file" to "is missing from this device")))
        }

        outbox.setPhotoState(photo.id, OutboxState.SENDING.wire)

        val response = runCatching {
            api.uploadPhoto(
                sightingId = sightingId,
                photoId = photo.id.toRequestBody(TEXT_PLAIN),
                file = MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.asRequestBody(IMAGE_JPEG),
                ),
            )
        }.getOrElse {
            val error = ErrorMapper.from(it)
            outbox.recordPhotoAttempt(photo.id, stateFor(error).wire, error.message)
            return Step.Failed(error)
        }

        if (response.isSuccessful) {
            // Not deleted yet, and not called confirmed yet: the file goes only when the
            // server's own photos[] lists this id.
            outbox.setPhotoState(photo.id, OutboxState.IN_DOUBT.wire)
            return Step.Ok
        }

        val error = ErrorMapper.from(response)
        outbox.recordPhotoAttempt(photo.id, stateFor(error).wire, error.message)
        return Step.Failed(error)
    }

    // ── Failure handling ────────────────────────────────────────────────────

    private suspend fun handleFailure(row: SightingQueueEntity, error: ApiError, photosUploaded: Int = 0): RowOutcome {
        val now = System.currentTimeMillis()

        return when {
            error is ApiError.Unauthorized -> {
                // The authenticator already tried a refresh and it failed. Leave the row
                // untouched — the queue survives sign-out, and its attempt counter must
                // not be spent on an expired session.
                outbox.setSightingState(row.id, row.state)
                RowOutcome.SessionEnded
            }

            error is ApiError.Offline -> {
                // The request never left the device, so nothing can have happened
                // server-side and this is not an attempt. Not counted, not backed off.
                outbox.setSightingState(row.id, OutboxState.QUEUED.wire)
                RowOutcome.Offline
            }

            !error.isRetryable -> {
                outbox.recordSightingAttempt(row.id, OutboxState.FAILED.wire, error.message, null)
                RowOutcome.FailedTerminally
            }

            RetryPolicy.isExhausted(row.attempts + 1) -> {
                // Out of attempts. Marked failed and surfaced with a Retry action rather
                // than retried silently forever.
                outbox.recordSightingAttempt(row.id, OutboxState.FAILED.wire, error.message, null)
                RowOutcome.FailedTerminally
            }

            else -> {
                outbox.recordSightingAttempt(
                    row.id,
                    stateFor(error).wire,
                    error.message,
                    RetryPolicy.nextAttemptAt(now, row.attempts + 1),
                )
                RowOutcome.StillPending(photosUploaded)
            }
        }
    }

    /**
     * `IN_DOUBT` when the outcome is genuinely unknown, `QUEUED` when it is not.
     *
     * The distinction is the reason the state is a string rather than a boolean: a
     * timeout may have committed server-side before the response was lost, and treating
     * that as "not sent" re-does work while treating it as "sent" claims a success nobody
     * confirmed.
     */
    private fun stateFor(error: ApiError): OutboxState =
        if (error.outcomeIsUnknown) OutboxState.IN_DOUBT else OutboxState.QUEUED

    // ── Cache ───────────────────────────────────────────────────────────────

    private suspend fun cacheDetail(userId: String, detail: SightingDetailDto) {
        val readAt = Instant.now()
        cache.upsertSightings(listOf(detail.sighting.toDomain().toCacheEntity(userId, readAt)))
        cache.upsertDetail(
            CachedDetailEntity(
                id = detail.sighting.id,
                json = json.encodeToString(SightingDetailDto.serializer(), detail),
                readAt = readAt.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        val TEXT_PLAIN = "text/plain".toMediaType()
        val IMAGE_JPEG = "image/jpeg".toMediaType()
    }
}
