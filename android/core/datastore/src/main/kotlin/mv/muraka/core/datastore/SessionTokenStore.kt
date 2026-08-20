package mv.muraka.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "muraka_session")

/** A session as it is held on disk. Deliberately not the domain [mv.muraka.core.model.Session]. */
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val userId: String,
)

/**
 * Where session tokens live.
 *
 * Two rules from `sync-protocol.md` are load-bearing here, and both are easy to get wrong:
 *
 * 1. **Persist the new refresh token immediately.** Refresh tokens are single-use — the
 *    old one is dead the moment the server answers — so both tokens are written in a
 *    single `edit {}`, which DataStore commits atomically. Writing them separately leaves
 *    a window where a crash loses the new refresh token and signs the contributor out for
 *    no reason.
 * 2. **The queue is not part of the session.** [clear] deletes tokens and nothing else.
 *    Queued sightings belong to the account that captured them and wait for that account
 *    to come back.
 *
 * Values are encrypted with [KeystoreCipher] before they reach the file, so a device with
 * developer options on does not hand over a working bearer token.
 */
@Singleton
class SessionTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {
    /** Emits the stored session, or null when there is none or it cannot be decrypted. */
    val session: Flow<StoredSession?> = context.sessionDataStore.data.map { it.toSession() }

    /** A one-shot read, for the OkHttp authenticator, which is not a coroutine world. */
    suspend fun current(): StoredSession? = context.sessionDataStore.data.first().toSession()

    /** Writes both tokens atomically. */
    suspend fun save(session: StoredSession) {
        context.sessionDataStore.edit { prefs ->
            prefs[ACCESS] = cipher.encrypt(session.accessToken)
            prefs[REFRESH] = cipher.encrypt(session.refreshToken)
            prefs[EXPIRES_AT] = session.expiresAtEpochMs
            prefs[USER_ID] = session.userId
        }
    }

    /** Forgets the session. Leaves the outbox completely alone. */
    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }

    /**
     * Forgets the session and destroys the key that could read it.
     *
     * Only for account deletion, where the tokens must be unrecoverable rather than
     * merely unreferenced.
     */
    suspend fun clearAndDestroyKey() {
        clear()
        cipher.destroyKey()
    }

    private fun Preferences.toSession(): StoredSession? {
        val access = this[ACCESS]?.let(cipher::decrypt) ?: return null
        val refresh = this[REFRESH]?.let(cipher::decrypt) ?: return null
        val userId = this[USER_ID] ?: return null
        return StoredSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = this[EXPIRES_AT] ?: 0L,
            userId = userId,
        )
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val USER_ID = stringPreferencesKey("user_id")
    }
}
