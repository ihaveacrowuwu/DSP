package mv.muraka

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import mv.muraka.core.domain.SyncScheduler
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Deliberately thin: nothing here reaches the network or opens the outbox on the main
 * thread, so a cold start on a boat with no signal is instant.
 *
 * It implements [Configuration.Provider] so WorkManager initialises **lazily** with
 * Hilt's worker factory. The manifest removes WorkManager's own startup initialiser for
 * the same reason — without both halves, the app either crashes with "WorkManager is
 * already initialized" or hands [mv.muraka.core.sync.SyncWorker] a factory that cannot
 * construct it.
 */
@HiltAndroidApp
class MurakaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Idempotent (unique periodic work with KEEP), so calling it on every launch does
        // not push the next run further away each time the app opens.
        syncScheduler.ensurePeriodicSync()
    }
}
