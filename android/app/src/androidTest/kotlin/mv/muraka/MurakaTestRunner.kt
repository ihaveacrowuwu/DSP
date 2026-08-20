package mv.muraka

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps [MurakaApplication] for Hilt's test application so instrumented tests can
 * replace bindings. Referenced by `testInstrumentationRunner` in build.gradle.kts.
 */
class MurakaTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
