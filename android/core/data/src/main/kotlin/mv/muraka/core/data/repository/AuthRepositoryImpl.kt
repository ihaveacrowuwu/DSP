package mv.muraka.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mv.muraka.core.common.ApiError
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.data.mapper.toDomain
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.database.dao.CacheDao
import mv.muraka.core.database.dao.OutboxDao
import mv.muraka.core.database.entity.CachedProfileEntity
import mv.muraka.core.datastore.SessionTokenStore
import mv.muraka.core.datastore.StoredSession
import mv.muraka.core.domain.AuthRepository
import mv.muraka.core.model.Profile
import mv.muraka.core.model.Role
import mv.muraka.core.model.SessionState
import mv.muraka.core.model.User
import mv.muraka.core.network.ErrorMapper
import mv.muraka.core.network.MurakaApi
import mv.muraka.core.network.dto.LoginRequest
import mv.muraka.core.network.dto.MeDto
import mv.muraka.core.network.dto.RefreshRequest
import mv.muraka.core.network.dto.RegisterRequest
import mv.muraka.core.network.dto.SessionDto
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sessions and the account.
 *
 * The only screen in the app that requires connectivity is the one this backs (NFR7), so
 * everything here is allowed to fail on the network - and everything else in the app is
 * not.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: MurakaApi,
    private val tokens: SessionTokenStore,
    private val cache: CacheDao,
    private val outbox: OutboxDao,
    private val photos: PhotoStore,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : AuthRepository {

    override val sessionState: Flow<SessionState> =
        tokens.session.combine(cachedProfileFlow()) { session, profile ->
            when {
                session == null -> SessionState.SignedOut
                profile != null -> SessionState.SignedIn(profile.user)
                // Tokens but no cached profile: only reachable if app data was cleared
                // while offline. The app stays usable - NFR7 - on a stub the next
                // successful /v1/me replaces.
                else -> SessionState.SignedIn(stubUser(session.userId))
            }
        }

    override fun observeProfile(): Flow<Profile?> = cachedProfileFlow()

    override suspend fun register(email: String, password: String, displayName: String): Result<Profile> =
        withContext(dispatchers.io) {
            call { api.register(RegisterRequest(email.trim().lowercase(), password, displayName.trim())) }
                .mapCatching { startSession(it) }
        }

    override suspend fun signIn(email: String, password: String): Result<Profile> = withContext(dispatchers.io) {
        call { api.login(LoginRequest(email.trim().lowercase(), password)) }
            .mapCatching { startSession(it) }
    }

    override suspend fun signOut(): Result<Unit> = withContext(dispatchers.io) {
        val stored = tokens.current()
        // Best effort: revoking the refresh token server-side is courteous, but a failure
        // must not keep the contributor signed in against their wish.
        stored?.let { runCatching { api.logout(RefreshRequest(it.refreshToken)) } }

        tokens.clear()
        // The OUTBOX IS NOT TOUCHED. Queued rows belong to the account that captured them
        // and wait for that account to sign back in. Clearing here would throw away reef
        // data because somebody handed the phone to another diver.
        Result.success(Unit)
    }

    override suspend fun refreshProfile(): Result<Profile> = withContext(dispatchers.io) {
        val userId = tokens.current()?.userId
            ?: return@withContext Result.failure(ApiError.Unauthorized)

        call { api.me() }.map { me ->
            cacheProfile(userId, me)
            me.toDomain()
        }
    }

    override suspend fun deleteAccount(): Result<Unit> = withContext(dispatchers.io) {
        val userId = tokens.current()?.userId
            ?: return@withContext Result.failure(ApiError.Unauthorized)

        val response = runCatching { api.deleteAccount() }
            .getOrElse { return@withContext Result.failure(ErrorMapper.from(it)) }

        if (!response.isSuccessful) {
            return@withContext Result.failure(ErrorMapper.from(response))
        }

        // Only after the server confirms. The sightings themselves survive under a
        // tombstone owner - this deletes what is on the device, not the science.
        outbox.deleteAllFor(userId)
        cache.deleteAllFor(userId)
        cache.deleteProfileFor(userId)
        photos.deleteAll()
        tokens.clearAndDestroyKey()
        Result.success(Unit)
    }

    // -- Internals -----------------------------------------------------------

    /**
     * Stores the session and reads the profile back.
     *
     * Both tokens are written in one atomic operation before anything else happens: the
     * refresh token is single-use, and a crash between two separate writes would sign the
     * contributor out on their very next request.
     */
    private suspend fun startSession(session: SessionDto): Profile {
        tokens.save(
            StoredSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAtEpochMs = session.expiresAt.toEpochMilli(),
                userId = session.user.id,
            ),
        )

        // Totals come from /v1/me and nowhere else. If it cannot be reached, the session
        // is still good - the contributor gets an account with no totals yet rather than
        // a failed sign-in.
        val me = runCatching { api.me() }.getOrNull()?.body()
        return if (me != null) {
            cacheProfile(session.user.id, me)
            me.toDomain()
        } else {
            Profile(user = session.user.toDomain(), stats = mv.muraka.core.model.ContributorStats(0, 0, 0, 0))
        }
    }

    private suspend fun cacheProfile(userId: String, me: MeDto) {
        cache.upsertProfile(
            CachedProfileEntity(
                userId = userId,
                json = json.encodeToString(MeDto.serializer(), me),
                readAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    /**
     * The cached profile for whoever is signed in.
     *
     * Keyed on the session's user id rather than "the current profile", because a shared
     * phone has two: signing back in as the first diver must show their totals, not the
     * other diver's.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun cachedProfileFlow(): Flow<Profile?> = tokens.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            cache.observeProfile(session.userId).map { row ->
                row?.let {
                    runCatching { json.decodeFromString(MeDto.serializer(), it.json).toDomain() }
                        .getOrNull()
                }
            }
        }
    }

    private fun stubUser(userId: String) = User(
        id = userId,
        email = "",
        displayName = "",
        role = Role.CONTRIBUTOR,
        status = "active",
        createdAt = Instant.EPOCH,
    )

    private inline fun <T> call(block: () -> Response<T>): Result<T> = runCatching {
        val response = block()
        val body = response.body()
        when {
            response.isSuccessful && body != null -> body
            response.isSuccessful -> throw ApiError.Unexpected("empty body")
            else -> throw ErrorMapper.from(response)
        }
    }.recoverCatching { throw ErrorMapper.from(it) }
}
