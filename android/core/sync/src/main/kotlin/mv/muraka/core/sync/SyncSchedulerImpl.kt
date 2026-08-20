package mv.muraka.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mv.muraka.core.domain.SyncScheduler
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks WorkManager to drain the outbox.
 *
 * The protocol wants a drain on app foreground, on connectivity returning, after a
 * capture, on a periodic task, and on pull-to-refresh. All five funnel through here, and
 * **unique work** is what stops them running the loop twice: connectivity returning and
 * the periodic task firing together is the ordinary case when a boat comes back into
 * range, not a rare race.
 */
@Singleton
class SyncSchedulerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * `CONNECTED` rather than `UNMETERED`: a diver on a phone's own data plan still wants
     * their reef photographs delivered, and the app downscales to roughly 1600 px before
     * uploading precisely so that is a reasonable thing to allow.
     */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun requestSync(expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF)
            .apply {
                if (expedited) {
                    // Straight after a capture the contributor is watching and the work is
                    // short. RUN_AS_NON_EXPEDITED_WORK_REQUEST is the required fallback:
                    // without it the request throws when the app's expedited quota is
                    // spent, which is precisely when a diver has just queued five
                    // sightings in a row.
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()

        // KEEP, not REPLACE: a drain already queued will do the same job, and replacing it
        // would restart its backoff and delay work that was about to happen.
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_ONE_SHOT, ExistingWorkPolicy.KEEP, request)
    }

    override fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF)
            .build()

        // KEEP so that calling this on every launch — which is the point — does not reset
        // the period and push the next run an hour away each time the app opens.
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override val isSyncing: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_ONE_SHOT)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    private companion object {
        /**
         * WorkManager's own floor is 15 minutes, so anything shorter is silently raised.
         * The periodic task is a safety net for a device that never opens the app rather
         * than the main path — foreground, connectivity and post-capture triggers do the
         * real work.
         */
        val PERIOD: Duration = Duration.ofMinutes(15)
        val MIN_BACKOFF: Duration = Duration.ofSeconds(30)
    }
}
