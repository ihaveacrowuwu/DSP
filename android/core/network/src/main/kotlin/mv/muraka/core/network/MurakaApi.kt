package mv.muraka.core.network

import mv.muraka.core.network.dto.AtollDto
import mv.muraka.core.network.dto.CreateSightingRequest
import mv.muraka.core.network.dto.LoginRequest
import mv.muraka.core.network.dto.MeDto
import mv.muraka.core.network.dto.PhotoUploadResponse
import mv.muraka.core.network.dto.RefreshRequest
import mv.muraka.core.network.dto.RegisterRequest
import mv.muraka.core.network.dto.SessionDto
import mv.muraka.core.network.dto.SightingDetailDto
import mv.muraka.core.network.dto.SightingDto
import mv.muraka.core.network.dto.SightingPageDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Every endpoint a contributor may call.
 *
 * Anything outside this list returns `403` for a contributor account, so it is
 * deliberately absent rather than present-and-unused: the verification queue, the map,
 * trends, the CSV export and everything under `/v1/admin` belong to the dashboard.
 *
 * Calls return `Response<T>` rather than `T` because the client has to distinguish
 * `201 Created` from `200 OK` — not to behave differently (it must not; treating them
 * identically is the entire point of client-generated ids) but because the difference is
 * worth logging when diagnosing a duplicate.
 */
interface MurakaApi {

    // ── Authentication ──────────────────────────────────────────────────────

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<SessionDto>

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<SessionDto>

    /**
     * Single-use: this call revokes the token presented and issues a fresh pair. The new
     * refresh token MUST be persisted or the next refresh fails.
     *
     * Called through a separate OkHttp client with no authenticator attached — see
     * [TokenAuthenticator] — so a failing refresh cannot recurse into itself.
     */
    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<SessionDto>

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    // ── Account ─────────────────────────────────────────────────────────────

    /** The only source of contribution totals. Never count local rows. */
    @GET("v1/me")
    suspend fun me(): Response<MeDto>

    /** Anonymises rather than erases. NFR15 requires the app to say so before confirming. */
    @DELETE("v1/me")
    suspend fun deleteAccount(): Response<Unit>

    // ── Sightings ───────────────────────────────────────────────────────────

    /** `201` on create, `200` on replay. The client treats them identically. */
    @POST("v1/sightings")
    suspend fun createSighting(@Body body: CreateSightingRequest): Response<SightingDto>

    /** Automatically scoped to the caller's own sightings; pass no contributor filter. */
    @GET("v1/sightings")
    suspend fun listSightings(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<SightingPageDto>

    /**
     * The reconciliation primitive.
     *
     * `404` means the server has nothing under this id, so everything still needs
     * sending. `200` carries `photos[]`, whose ids are the client's own — so diffing
     * against the local photo ids gives the exact set still missing, not an estimate.
     */
    @GET("v1/sightings/{id}")
    suspend fun sighting(@Path("id") id: String): Response<SightingDetailDto>

    /** One call per photo, idempotent on `photoId`. */
    @Multipart
    @POST("v1/sightings/{id}/photos")
    suspend fun uploadPhoto(
        @Path("id") sightingId: String,
        @Part("photoId") photoId: RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<PhotoUploadResponse>

    /**
     * Photograph bytes. **Requires the bearer token** — this is not a public URL, so it
     * cannot be handed to a stock image loader without an auth header or it renders
     * nothing and returns 401.
     */
    @Streaming
    @GET("v1/photos/{id}/image")
    suspend fun photoImage(@Path("id") photoId: String): Response<ResponseBody>

    // ── Reference data ──────────────────────────────────────────────────────

    @GET("v1/atolls")
    suspend fun atolls(): Response<List<AtollDto>>
}
