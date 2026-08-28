package mv.muraka.core.network

import kotlinx.coroutines.runBlocking
import mv.muraka.core.common.ServerClock
import mv.muraka.core.datastore.SessionTokenStore
import okhttp3.Interceptor
import okhttp3.Response
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the bearer token.
 *
 * Skips requests that already carry an `Authorization` header - which is how
 * [TokenAuthenticator] re-issues a retried request with a fresher token without this
 * interceptor overwriting it with the stale one it just replaced.
 */
@Singleton
class AuthInterceptor @Inject constructor(private val tokenStore: SessionTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)

        // runBlocking is correct here rather than a smell: OkHttp's interceptor chain is a
        // synchronous API on a background thread it owns, and there is no suspending
        // variant to hand a coroutine to.
        val token = runBlocking { tokenStore.current()?.accessToken }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(AUTHORIZATION, "Bearer $token").build(),
        )
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * Learns the server's clock from the `Date` header of every response.
 *
 * Free - the header is on every response already - and it is what stops a phone with a
 * wrong clock from losing the sighting it just captured to a terminal `422`. See
 * [ServerClock].
 */
@Singleton
class ServerDateInterceptor @Inject constructor(private val serverClock: ServerClock) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        response.header("Date")?.let { raw ->
            runCatching {
                Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw))
            }.onSuccess { serverClock.observeServerDate(it) }
        }
        return response
    }
}
