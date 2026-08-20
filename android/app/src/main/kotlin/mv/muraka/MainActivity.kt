package mv.muraka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.compose.setSingletonImageLoaderFactory
import dagger.hilt.android.AndroidEntryPoint
import mv.muraka.ui.common.murakaImageLoader
import okhttp3.OkHttpClient
import javax.inject.Inject

/** The single activity. Navigation happens in Compose, not between activities. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The app's own HTTP client, handed to Coil.
     *
     * Photograph bytes need the bearer token, so the image loader has to be the same
     * client as everything else — see [mv.muraka.ui.common.AuthedPhotoUrl].
     */
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            setSingletonImageLoaderFactory { context -> murakaImageLoader(context, okHttpClient) }
            MurakaApp()
        }
    }
}
