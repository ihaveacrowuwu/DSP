package mv.muraka.ui.common

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import mv.muraka.BuildConfig
import okhttp3.OkHttpClient

/**
 * Photograph bytes are **not** a public URL.
 *
 * `GET /v1/photos/{id}/image` requires the bearer token, so a bare URL handed to a stock
 * image loader returns `401` and renders nothing. This is listed under "things that will
 * cost you an afternoon" in `mobile-shared/integration.md`, and it does.
 *
 * The fix is to give Coil the app's own OkHttp client, which already carries the auth
 * interceptor and the refresh-on-401 authenticator. A photograph loaded while the access
 * token is expiring therefore refreshes and retries exactly like any other request,
 * rather than showing a broken frame.
 */
fun authedPhotoUrl(photoId: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/photos/$photoId/image"

/**
 * The image loader.
 *
 * A disk cache is worth having here beyond the usual reasons: a contributor reviewing
 * their own history offline should still see the photographs the server has already sent
 * them, and without one every scroll back would be a blank frame.
 */
fun murakaImageLoader(context: Context, client: OkHttpClient): ImageLoader = ImageLoader.Builder(context)
    .components {
        // `callFactory` is a lambda so the client is not built until first use.
        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("photo-cache"))
            .maxSizeBytes(DISK_CACHE_BYTES)
            .build()
    }
    .crossfade(true)
    .build()

/** 96 MiB: enough for a few hundred reef photographs at the size the server returns them. */
private const val DISK_CACHE_BYTES = 96L * 1024 * 1024
