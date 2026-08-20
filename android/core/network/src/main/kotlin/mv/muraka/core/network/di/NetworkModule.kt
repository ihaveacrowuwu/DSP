package mv.muraka.core.network.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import mv.muraka.core.network.AuthInterceptor
import mv.muraka.core.network.MurakaApi
import mv.muraka.core.network.RefreshApi
import mv.muraka.core.network.ServerDateInterceptor
import mv.muraka.core.network.TokenAuthenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * The HTTP stack.
 *
 * Two clients, deliberately:
 *
 * - [BARE_CLIENT] carries no authenticator and is used only for `POST /v1/auth/refresh`.
 *   A refresh that answers `401` on the authenticated client would trigger another
 *   refresh, and so on.
 * - The default client carries the bearer token, the refresh-on-401 authenticator and
 *   the clock-learning interceptor, and is what everything else uses.
 *
 * The base URL is injected from `:app`'s `BuildConfig` rather than hardcoded, because the
 * emulator (`10.0.2.2`), the simulator (`localhost`) and a physical phone (a LAN address)
 * all need different values for the same dev stack.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    const val BARE_CLIENT = "muraka.bare"
    const val BASE_URL = "muraka.baseUrl"

    /** Timeouts short enough that a stalled request becomes a retryable failure. */
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 30L

    /** Uploads are the long pole on a resort Wi-Fi connection. */
    private const val WRITE_TIMEOUT_S = 60L

    @Provides
    @Singleton
    fun json(): Json = Json {
        // A server that adds a field must not break an installed app.
        ignoreUnknownKeys = true
        // Go omits empty optionals rather than sending null, so absence is the norm.
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @Named(BARE_CLIENT)
    fun bareClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun okHttpClient(
        authInterceptor: AuthInterceptor,
        serverDateInterceptor: ServerDateInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        // Order matters: the date interceptor must see the final response, so it goes
        // first in the chain and returns last.
        .addInterceptor(serverDateInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        // Uploads are retried by the outbox with its own backoff curve, not by OkHttp.
        // Leaving this on would silently repeat a request the drain loop is also about
        // to repeat, which is harmless — the ids make it idempotent — but it doubles a
        // diver's tethering use for no benefit and hides real failures from the queue.
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json, @Named(BASE_URL) baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun murakaApi(retrofit: Retrofit): MurakaApi = retrofit.create(MurakaApi::class.java)

    @Provides
    @Singleton
    fun refreshApi(
        @Named(BARE_CLIENT) client: OkHttpClient,
        json: Json,
        @Named(BASE_URL) baseUrl: String,
    ): RefreshApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RefreshApi::class.java)
}
