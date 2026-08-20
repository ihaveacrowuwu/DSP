package mv.muraka.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import mv.muraka.core.common.ApiError
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.common.Uuid7
import mv.muraka.core.data.mapper.capturedAtInstant
import mv.muraka.core.data.mapper.outboxState
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.PhotoQueueEntity
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.domain.OutboxRepository
import mv.muraka.core.domain.QueuedItem
import mv.muraka.core.model.OutboxState
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue, as the sync screen sees it.
 *
 * Everything here is about making pending work **visible**. A silent queue is how data
 * goes missing unnoticed, and the two escape hatches — retry, and retry smaller — exist
 * because `sync-protocol.md` requires a stranded sighting to have a way out rather than
 * sitting in a permanent failure the contributor cannot act on.
 */
@Singleton
class OutboxRepositoryImpl @Inject constructor(
    private val outbox: OutboxDao,
    private val photos: PhotoStore,
    private val tokens: SessionTokenStore,
    private val dispatchers: DispatcherProvider,
) : OutboxRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeQueue(): Flow<List<QueuedItem>> = tokens.session.flatMapLatest { session ->
        val userId = session?.userId ?: return@flatMapLatest flowOf(emptyList())

        combine(
            outbox.observeQueue(userId),
            outbox.observePhotos(userId),
        ) { rows, allPhotos ->
            val photosBySighting = allPhotos.groupBy { it.sightingId }
            rows.map { row ->
                val mine = photosBySighting[row.id].orEmpty()
                QueuedItem(
                    sightingId = row.id,
                    capturedAt = row.capturedAtInstant,
                    state = row.outboxState,
                    photosTotal = mine.size,
                    // "Sent" here means the server acknowledged the upload call. It is
                    // NOT a claim that the photograph is safe — that only follows the
                    // read-back, which is what deletes the row entirely.
                    photosSent = mine.count { it.state != OutboxState.QUEUED.wire },
                    attempts = row.attempts,
                    lastError = row.lastError,
                    nextAttemptAt = row.nextAttemptAt?.let(Instant::ofEpochMilli),
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingCount(): Flow<Int> = tokens.session.flatMapLatest { session ->
        session?.userId?.let(outbox::observePendingCount) ?: flowOf(0)
    }

    override suspend fun retry(sightingId: String): Result<Unit> = withContext(dispatchers.io) {
        outbox.requeue(sightingId)
        outbox.requeuePhotos(sightingId)
        Result.success(Unit)
    }

    /**
     * The way out of a `413`.
     *
     * Each oversized photograph is re-encoded smaller and queued under a **new** photo id,
     * because the old id may already be half-known to the server — reusing it would ask
     * the server to reconcile two different images under one key. The old row and its file
     * go only once the replacement is durably written.
     */
    override suspend fun retryWithSmallerPhotos(sightingId: String): Result<Unit> = withContext(dispatchers.io) {
        val pending = outbox.photosFor(sightingId).filter { it.state != OutboxState.CONFIRMED.wire }
        if (pending.isEmpty()) return@withContext retry(sightingId)

        val replacements = mutableListOf<PhotoQueueEntity>()
        for (photo in pending) {
            val replacementId = Uuid7.generateString()
            val file = photos.downscaleFurther(photo.id, replacementId)
                ?: return@withContext Result.failure(
                    ApiError.Validation(mapOf("file" to "could not be made smaller")),
                )
            replacements += photo.copy(
                id = replacementId,
                localPath = file.absolutePath,
                state = OutboxState.QUEUED.wire,
                attempts = 0,
                lastError = null,
            )
        }

        outbox.insertPhotos(replacements)
        // Only now: the replacements exist on disk and in the queue, so nothing is
        // lost if the process dies on the next line.
        pending.forEach {
            outbox.deletePhoto(it.id)
            photos.delete(it.id)
        }
        retry(sightingId)
    }

    /**
     * Gives up on a row, at the contributor's explicit instruction and never otherwise.
     *
     * Note that this cannot un-send anything: if the metadata already reached the server,
     * the sighting is real and stays real. This deletes the device's copy of work it will
     * no longer attempt.
     */
    override suspend fun discard(sightingId: String): Result<Unit> = withContext(dispatchers.io) {
        outbox.photosFor(sightingId).forEach { photos.delete(it.id) }
        outbox.delete(sightingId)
        Result.success(Unit)
    }
}
