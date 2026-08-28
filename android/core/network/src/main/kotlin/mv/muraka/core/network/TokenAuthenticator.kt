package mv.muraka.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mv.muraka.core.common.SessionEvent
import mv.muraka.core.common.SessionEvents
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.datastore.StoredSession
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Handles `401` by refreshing once and retrying the original request.
 *
 * Access tokens last fifteen minutes, so expiry mid-session is a normal code path rather
 * than an edge case. Two things here are easy to get wrong and both lose the session when
 * they are:
 *
 * 1. **Refreshes are serialised.** If four queued uploads all `401` at once, only one
 *    refresh may run. Refresh tokens are single-use, so two concurrent refreshes mean the
 *    second presents an already-consumed token, the server rejects it, and the
 *    contributor is signed out for no reason. The mutex is the whole fix; the check
 *    against the stale token afterwards is what lets the other three proceed on the new
 *    token instead of queueing for a refresh each.
 *
 * 2. **The refresh call must not itself carry an authenticator**, or a failing refresh
 *    recurses into another refresh. [refreshApi] is built on a bare client - see
 *    `NetworkModule` - for exactly that reason.
 *
 * Returning null from [authenticate] tells OkHttp to give up and surface the `401`, which
 * `ErrorMapper` turns into [mv.muraka.core.common.ApiError.Unauthorized].
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: SessionTokenStore,
    /**
     * A Provider, not the instance: the refresh API is built from a Retrofit that this
     * authenticator is not part of, and asking Dagger for it eagerly would be a cycle.
     */
    private val refreshApi: Provider<RefreshApi>,
    private val sessionEvents: SessionEvents,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // One retry only. Without this a server that answers 401 to a perfectly good
        // token would spin forever.
        if (response.priorResponseCount() >= 1) return null

        val staleToken = response.request
            .header(AuthInterceptor.AUTHORIZATION)
            ?.removePrefix("Bearer ")

        // OkHttp's Authenticator is a synchronous API on a thread it owns; there is no
        // suspending variant, so this is the intended shape rather than a shortcut.
        val freshToken = runBlocking {
            mutex.withLock {
                val stored = tokenStore.current() ?: return@withLock null

                // Another request refreshed while this one waited for the lock. Use what
                // it stored rather than burning a second single-use refresh token.
                if (stored.accessToken != staleToken) return@withLock stored.accessToken

                refreshOnce(stored)
            }
        } ?: return null

        return response.request.newBuilder()
            .header(AuthInterceptor.AUTHORIZATION, "Bearer $freshToken")
            .build()
    }

    /** Returns the new access token, or null when the session is genuinely over. */
    private suspend fun refreshOnce(stored: StoredSession): String? {
        val result = runCatching {
            refreshApi.get().refresh(mv.muraka.core.network.dto.RefreshRequest(stored.refreshToken))
        }.getOrNull()

        val body = result?.body()
        if (result?.isSuccessful == true && body != null) {
            // BOTH tokens, in one atomic write. The presented refresh token is dead the
            // moment the server answered, so losing the new one here signs the
            // contributor out for no reason.
            tokenStore.save(
                StoredSession(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresAtEpochMs = body.expiresAt.toEpochMilli(),
                    userId = body.user.id,
                ),
            )
            return body.accessToken
        }

        // A network failure is not a dead session - the token may be perfectly valid and
        // simply unreachable. Only an explicit rejection ends the session.
        val status = result?.code()
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            tokenStore.clear()
            sessionEvents.emit(
                if (status == HTTP_FORBIDDEN) SessionEvent.AccountDisabled else SessionEvent.RefreshFailed,
            )
        }
        return null
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Just the refresh call, on a client with **no** authenticator attached.
 *
 * Separate from [MurakaApi] so that a refresh which itself returns `401` surfaces as a
 * failure rather than triggering another refresh.
 */
interface RefreshApi {
    @retrofit2.http.POST("v1/auth/refresh")
    suspend fun refresh(
        @retrofit2.http.Body body: mv.muraka.core.network.dto.RefreshRequest,
    ): retrofit2.Response<mv.muraka.core.network.dto.SessionDto>
}
