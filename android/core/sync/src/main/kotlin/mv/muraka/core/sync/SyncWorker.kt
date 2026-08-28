package mv.muraka.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mv.muraka.core.domain.SyncEngine

/**
 * Runs one drain pass.
 *
 * Deliberately almost empty: the algorithm lives in `SyncEngine`, which knows nothing
 * about WorkManager, so the hard part is testable without an emulator and this class has
 * only one decision to make - whether to ask for another go.
 *
 * `Result.retry()` versus `Result.success()` is that decision, and it matters. Returning
 * success while sightings are still queued tells WorkManager the job is done, and nothing
 * wakes the app again until the next periodic run - which on a boat could be an hour of a
 * contributor watching a queue that says it is waiting and never moves.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val outcome = syncEngine.drain()

        return when {
            // The session ended. Retrying cannot help until somebody signs in, and the
            // queue is safe where it is.
            outcome.needsSignIn -> Result.success()
            outcome.shouldRetry -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "muraka.sync.periodic"
        const val UNIQUE_ONE_SHOT = "muraka.sync.now"
    }
}
