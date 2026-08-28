package mv.muraka.core.network

import kotlinx.serialization.json.Json
import mv.muraka.core.common.ApiError
import mv.muraka.core.network.dto.ErrorDto
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns an HTTP response or a thrown exception into an [ApiError].
 *
 * This is the only place status codes appear. Everything above it asks
 * [ApiError.isRetryable] and [ApiError.outcomeIsUnknown] instead, which is what keeps the
 * drain loop from having to know that `413` is terminal and `503` is not.
 *
 * The catalogue is `mobile-shared/integration.md`.
 */
object ErrorMapper {

    private val json = Json { ignoreUnknownKeys = true }

    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val CONFLICT = 409
    private const val PAYLOAD_TOO_LARGE = 413
    private const val UNPROCESSABLE = 422
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_ERROR_FLOOR = 500

    /** Maps a non-2xx response. The body is read once, because it is a one-shot stream. */
    fun from(response: Response<*>): ApiError {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val dto = body?.let { runCatching { json.decodeFromString<ErrorDto>(it) }.getOrNull() }
        return fromStatus(response.code(), dto)
    }

    /**
     * Split three ways, by what the client should DO about each group.
     *
     * That is the same grouping `ApiError` itself uses, and keeping the mapper in the
     * same shape means a new status code has one obvious home rather than being wedged
     * into a `when` that already had seventeen branches.
     */
    fun fromStatus(status: Int, dto: ErrorDto?): ApiError {
        val code = dto?.error.orEmpty()
        return terminal(status, code, dto)
            ?: recoverable(status)
            ?: transient(status)
            ?: ApiError.BadRequest(code.ifBlank { "http_$status" })
    }

    /** Never retry. Surface something the contributor can act on. */
    private fun terminal(status: Int, code: String, dto: ErrorDto?): ApiError? = when {
        status == UNPROCESSABLE -> ApiError.Validation(
            // A 422 with no parseable body would otherwise be an empty terminal failure
            // with nothing to show the contributor.
            dto?.fields?.takeIf { it.isNotEmpty() }
                ?: mapOf("request" to dto?.message.orEmpty().ifBlank { "was rejected" }),
        )

        status == CONFLICT && code == "email_taken" -> ApiError.EmailTaken
        status == CONFLICT -> ApiError.IdOwnedByAnotherUser
        status == PAYLOAD_TOO_LARGE -> ApiError.UploadTooLarge
        status == NOT_FOUND -> ApiError.NotFound
        status == FORBIDDEN && code == "account_disabled" -> ApiError.AccountDisabled
        status == FORBIDDEN -> ApiError.Forbidden

        // A wrong password, not an expired token. Refreshing would be pointless and
        // would burn the single-use refresh token for nothing.
        status == UNAUTHORIZED && code == "invalid_credentials" -> ApiError.InvalidCredentials

        else -> null
    }

    /** Refresh once, then retry. */
    private fun recoverable(status: Int): ApiError? = if (status == UNAUTHORIZED) ApiError.Unauthorized else null

    /** Retry with backoff - the outcome is unknown. */
    private fun transient(status: Int): ApiError? = when {
        status == TOO_MANY_REQUESTS -> ApiError.RateLimited
        status >= SERVER_ERROR_FLOOR -> ApiError.Server(status)
        else -> null
    }

    /**
     * Maps a thrown exception.
     *
     * The distinction that matters is [ApiError.Offline] versus [ApiError.Timeout]: an
     * unreachable host means the request never left, so nothing can have happened
     * server-side, while a timeout means the outcome is genuinely unknown and the row
     * must go to `IN_DOUBT` rather than straight back to the queue.
     */
    fun from(throwable: Throwable): ApiError = when (throwable) {
        is ApiError -> throwable
        is UnknownHostException, is ConnectException -> ApiError.Offline
        is SocketTimeoutException -> ApiError.Timeout
        is SSLException -> ApiError.Unexpected("tls: ${throwable.message}", throwable)
        is IOException -> ApiError.Timeout
        else -> ApiError.Unexpected(throwable.message ?: throwable::class.java.simpleName, throwable)
    }
}
