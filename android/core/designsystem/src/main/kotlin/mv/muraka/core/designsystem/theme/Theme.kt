package mv.muraka.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app's theme.
 *
 * Material 3 governs everything the contributor touches: components, the shape scale,
 * tonal elevation, the motion tokens, the type ramp. Where M3 and the dashboard's
 * appearance disagree, M3 wins - that is a recorded project decision, not a default.
 *
 * **Dynamic colour is preferred**, and applies to chrome only. `MaterialTheme.colorScheme`
 * is the wallpaper's; `MurakaTheme.reef` is the reef's, and nothing connects the two.
 */
object MurakaTheme {
    /**
     * The data colours - condition, severity, signals.
     *
     * Read these for anything that means something about a reef. Read
     * `MaterialTheme.colorScheme` for anything that is merely interface.
     */
    val reef: ReefColors
        @Composable @ReadOnlyComposable
        get() = LocalReefColors.current
}

@Composable
fun MurakaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off in screenshot tests and in the project's figures, so the same screen is the same
     * colour every time it is captured. On everywhere else, because a phone that themes
     * itself to its owner is what Material 3 asks for.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme: ColorScheme = when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        darkTheme -> SeededDarkScheme
        else -> SeededLightScheme
    }

    CompositionLocalProvider(
        // The one line that keeps the two palettes apart. Note it is driven by
        // `darkTheme`, not by anything derived from `colorScheme`: a wallpaper cannot
        // reach it even indirectly.
        LocalReefColors provides if (darkTheme) DarkReefColors else LightReefColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = MurakaTypography,
            content = content,
        )
    }
}

/**
 * The fallback scheme below Android 12, seeded from the one accent the three clients
 * share - reef teal.
 *
 * Hand-written rather than generated so the below-12 appearance is reviewable in the
 * report next to the dynamic one.
 */
private val Seed = Color(0xFF0F8168)
private val SeedDark = Color(0xFF2EC8A2)

private val SeededDarkScheme = darkColorScheme(
    primary = SeedDark,
    onPrimary = Color(0xFF04231C),
    primaryContainer = Color(0xFF17705D),
    onPrimaryContainer = Color(0xFFB9F2E2),
    secondary = Color(0xFF8ADCC5),
    onSecondary = Color(0xFF04231C),
    tertiary = Color(0xFF67BCE4),
    onTertiary = Color(0xFF042430),
    error = Color(0xFFE2643D),
    onError = Color(0xFF2A0A02),
)

private val SeededLightScheme = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F2E2),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF47A289),
    onSecondary = Color.White,
    tertiary = Color(0xFF2A6D93),
    onTertiary = Color.White,
    error = Color(0xFFB4441F),
    onError = Color.White,
)
