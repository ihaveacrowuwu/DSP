package mv.muraka.ui.common

import java.time.Duration
import java.time.Instant

/**
 * "just now", "12 minutes ago", "3 days ago".
 *
 * Used for two different things, and the difference matters: the age of a *sighting*, and
 * the age of what the app *knows* about it. The second is what lets the interface say
 * "checked 20 minutes ago" instead of presenting a stale status as current — which the
 * sync protocol requires it to do rather than merely permits.
 */
fun Instant.relativeAge(now: Instant = Instant.now()): String {
    val elapsed = Duration.between(this, now)
    val seconds = elapsed.seconds

    return when {
        // A clock that reads slightly ahead should not produce "in 3 seconds".
        seconds < 0 -> "just now"
        seconds < MINUTE -> "just now"
        seconds < HOUR -> "${elapsed.toMinutes()} minute${plural(elapsed.toMinutes())} ago"
        seconds < DAY -> "${elapsed.toHours()} hour${plural(elapsed.toHours())} ago"
        seconds < MONTH -> "${elapsed.toDays()} day${plural(elapsed.toDays())} ago"
        else -> "${elapsed.toDays() / DAYS_PER_MONTH} month${plural(elapsed.toDays() / DAYS_PER_MONTH)} ago"
    }
}

private fun plural(value: Long) = if (value == 1L) "" else "s"

private const val MINUTE = 60L
private const val HOUR = 3_600L
private const val DAY = 86_400L
private const val DAYS_PER_MONTH = 30L
private const val MONTH = DAY * DAYS_PER_MONTH
