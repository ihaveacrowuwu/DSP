package mv.muraka.core.data.sync

import mv.muraka.core.network.MurakaApi
import mv.muraka.core.network.dto.AtollDto
import mv.muraka.core.network.dto.CreateSightingRequest
import mv.muraka.core.network.dto.LoginRequest
import mv.muraka.core.network.dto.MeDto
import mv.muraka.core.network.dto.PhotoDto
import mv.muraka.core.network.dto.PhotoUploadResponse
import mv.muraka.core.network.dto.PointDto
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
import java.net.UnknownHostException
import java.time.Instant

/**
 * A server the test controls.
 *
 * Not a mock framework: the point of these tests is what the sync engine does when the
 * server's state and the client's disagree, so the fake keeps **real state** — a map of
 * the sightings and photo ids it has actually received — and answers from it. An
 * expectation-based mock would let a test pass while describing a server that could not
 * exist.
 *
 * [offline] is the whole airplane-mode scenario, and the exception type matters more
 * than it looks: `ErrorMapper` maps `UnknownHostException` and `ConnectException` to
 * `ApiError.Offline` and a **plain `IOException` to `ApiError.Timeout`**. The first
 * version of this fake threw `IOException`, so every offline test exercised the
 * retryable-failure path instead — burning attempt counters and never reporting itself
 * offline. A device in aeroplane mode fails to resolve the host, so
 * `UnknownHostException` is both the faithful choice and the one the engine reads as
 * "there is no network".
 */
class FakeMurakaApi : MurakaApi {

    /** When true, every call fails the way a missing network fails. */
    var offline: Boolean = false

    /** Sighting ids the server has stored, to the photo ids stored against each. */
    val stored: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Every call the engine made, in order, for asserting what was *not* re-sent. */
    val calls: MutableList<String> = mutableListOf()

    /**
     * Called before each photo upload is recorded, with the 1-based index of that
     * upload. Throw from it to break the connection at a chosen point — which is how
     * "killed mid-upload" is expressed when a sighting has several photographs.
     */
    var onPhotoUpload: ((Int) -> Unit)? = null

    private var photoUploadCount = 0

    /** Status to return from `sighting()` instead of answering from [stored]. */
    var forceSightingStatus: Int? = null

    fun uploadsOf(sightingId: String): Int = calls.count { it == "uploadPhoto:$sightingId" }

    private fun guard() {
        if (offline) throw UnknownHostException("muraka.test: no address (fake aeroplane mode)")
    }

    override suspend fun createSighting(body: CreateSightingRequest): Response<SightingDto> {
        guard()
        calls += "createSighting:${body.id}"
        val fresh = stored.putIfAbsent(body.id, mutableSetOf()) == null
        val dto = sightingDto(body.id)
        // 201 when it is new, 200 when it is a replay — the server's real behaviour, and
        // the protocol tells clients to treat them identically.
        return if (fresh) Response.success(201, dto) else Response.success(200, dto)
    }

    override suspend fun uploadPhoto(
        sightingId: String,
        photoId: RequestBody,
        file: MultipartBody.Part,
    ): Response<PhotoUploadResponse> {
        guard()
        calls += "uploadPhoto:$sightingId"
        photoUploadCount += 1
        onPhotoUpload?.invoke(photoUploadCount)
        // The engine sends the id as a form part; reading it back out of the RequestBody
        // is how the fake learns which photo this was.
        val id = photoId.readUtf8()
        val alreadyHad = stored.getOrPut(sightingId) { mutableSetOf() }.add(id).not()
        return Response.success(
            PhotoUploadResponse(photoId = id, sightingId = sightingId, queued = !alreadyHad),
        )
    }

    override suspend fun sighting(id: String): Response<SightingDetailDto> {
        guard()
        calls += "sighting:$id"
        forceSightingStatus?.let { status ->
            return Response.error(status, ResponseBody.create(null, ""))
        }
        val photos = stored[id]
            ?: return Response.error(404, ResponseBody.create(null, """{"error":"not_found"}"""))
        return Response.success(
            SightingDetailDto(
                sighting = sightingDto(id, photoCount = photos.size),
                photos = photos.map { photoId ->
                    PhotoDto(id = photoId, sightingId = id, createdAt = Instant.now())
                },
            ),
        )
    }

    private fun sightingDto(id: String, photoCount: Int = 0) = SightingDto(
        id = id,
        location = PointDto(),
        capturedAt = Instant.now(),
        createdAt = Instant.now(),
        photoCount = photoCount,
        status = if (photoCount == 0) "pending_photos" else "awaiting_verification",
    )

    // ── Not exercised by these tests ────────────────────────────────────────
    // Left as failures rather than empty successes: a test that accidentally depends on
    // one of these should say so loudly instead of quietly asserting against a stub.

    private fun <T> unused(name: String): Response<T> =
        throw UnsupportedOperationException("$name is not part of the sync engine's path")

    override suspend fun register(body: RegisterRequest): Response<SessionDto> = unused("register")
    override suspend fun login(body: LoginRequest): Response<SessionDto> = unused("login")
    override suspend fun refresh(body: RefreshRequest): Response<SessionDto> = unused("refresh")
    override suspend fun logout(body: RefreshRequest): Response<Unit> = unused("logout")
    override suspend fun me(): Response<MeDto> = unused("me")
    override suspend fun deleteAccount(): Response<Unit> = unused("deleteAccount")
    override suspend fun listSightings(limit: Int, offset: Int): Response<SightingPageDto> = unused("listSightings")

    override suspend fun photoImage(photoId: String): Response<ResponseBody> = unused("photoImage")
    override suspend fun atolls(): Response<List<AtollDto>> = unused("atolls")
}

/** Reads a small `RequestBody` back as text. */
private fun RequestBody.readUtf8(): String {
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
