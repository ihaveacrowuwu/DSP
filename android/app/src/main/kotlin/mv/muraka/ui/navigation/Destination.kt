package mv.muraka.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every place the app can be.
 *
 * A sealed hierarchy rather than loose route strings, so a typo in a destination is a
 * compile error and the exhaustive `when` in the navigation bar cannot silently miss a
 * tab when one is added.
 */
sealed interface Destination {
    val route: String

    /** The three top-level destinations, reachable from the navigation bar. */
    sealed interface Tab : Destination {
        val label: String
        val icon: ImageVector
    }

    data object SignIn : Destination {
        override val route = "sign-in"
    }

    data object MySightings : Tab {
        override val route = "sightings"
        override val label = "Sightings"
        override val icon = Icons.Outlined.Waves
    }

    /**
     * The queue.
     *
     * A top-level destination rather than something buried in a menu, because
     * `sync-protocol.md` asks for pending work to be permanently visible — a silent queue
     * is how reef data goes missing unnoticed.
     */
    data object Sync : Tab {
        override val route = "sync"
        override val label = "Sync"
        override val icon = Icons.Outlined.CloudUpload
    }

    /**
     * The account and the app's settings.
     *
     * Named "Config" at the user's request. Note that both platforms conventionally call this
     * "Settings" — Apple's HIG and Material both use that word — so if a design review ever
     * flags it, this is the one label to change and it changes here.
     */
    data object Config : Tab {
        override val route = "config"
        override val label = "Config"
        override val icon = Icons.Outlined.Tune
    }

    data object Capture : Destination {
        override val route = "capture"
    }

    data object SightingDetail : Destination {
        override val route = "sighting/{sightingId}"
        const val ARG_SIGHTING_ID = "sightingId"
        fun routeTo(sightingId: String) = "sighting/$sightingId"
    }

    companion object {
        val tabs: List<Tab> = listOf(MySightings, Sync, Config)
    }
}
