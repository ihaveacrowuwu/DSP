package mv.muraka.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mv.muraka.core.domain.LocationProvider
import mv.muraka.core.model.LocationFix
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.Position
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A position fix, preferring Google's fused provider and falling back to the platform's.
 *
 * The fused provider is markedly better at
 * getting a fix quickly on a moving boat. It needs no API key and no account - it is the
 * platform's own sensor fusion, not a hosted service, so it does not engage the key-free
 * constraint. It does need Play Services, which not every device has, so
 * [LocationManager] backs it up and nothing depends on Play Services being present.
 *
 * **Null is an ordinary outcome, not an error.** A diver under cloud on a hull that
 * blocks the sky may simply have no fix, and the capture flow then offers a dropped pin
 * recorded as `manual_pin` so researchers can filter on the difference.
 */
@Singleton
class PlatformLocationProvider @Inject constructor(@param:ApplicationContext private val context: Context) :
    LocationProvider {

    override fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // guarded by hasPermission() on the line above
    override suspend fun currentFix(timeoutMs: Long): LocationFix? {
        if (!hasPermission()) return null

        return withTimeoutOrNull(timeoutMs) {
            fusedFix() ?: platformFix()
        }?.toFix()
    }

    /**
     * Google Play Services' fused provider. Null when Play Services is unavailable.
     *
     * Note the two different cancellation types: the fused client takes Play Services'
     * own `CancellationToken`, while [LocationManager] below takes the platform's
     * `CancellationSignal`. They are not interchangeable.
     */
    @SuppressLint("MissingPermission")
    private suspend fun fusedFix(): Location? = runCatching {
        val client = LocationServices.getFusedLocationProviderClient(context)
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }.getOrNull()

    /**
     * The platform provider, for devices with no Play Services.
     *
     * Goes through `LocationManagerCompat` rather than calling
     * `LocationManager.getCurrentLocation` directly: that method is API 30 and this app
     * supports API 26, so the direct call would compile, pass review, and crash on a
     * five-year-old phone. `ContextCompat.getMainExecutor` is here for the same reason
     * the platform version of it arrived in API 28.
     *
     * The signal is the PLATFORM `android.os.CancellationSignal` (API 16), not
     * `androidx.core.os.CancellationSignal`: `LocationManagerCompat` has an overload for
     * each and the androidx one is deprecated.
     */
    @SuppressLint("MissingPermission")
    private suspend fun platformFix(): Location? = runCatching {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location -> continuation.resume(location) }
        }
    }.getOrNull()

    private fun Location.toFix() = LocationFix(
        position = Position(latitude, longitude),
        source = LocationSource.GPS,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    )

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
