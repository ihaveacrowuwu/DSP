package mv.muraka

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Deliberately thin: nothing here reaches the network or touches the outbox on the main
 * thread at launch. The drain loop is scheduled by [mv.muraka.sync.SyncScheduler] once
 * the dependency graph is up, so a cold start on a boat with no signal is instant.
 */
@HiltAndroidApp
class MurakaApplication : Application()
