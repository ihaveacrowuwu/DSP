package mv.muraka.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mv.muraka.core.model.ThemePreference
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appearanceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "muraka_appearance")

/**
 * Where the contributor's appearance choice lives.
 *
 * Deliberately a **separate** DataStore from the session, and deliberately not encrypted.
 * It is a display preference, not a credential: it should survive signing out, it should
 * survive a shared phone changing hands, and it has nothing to hide. Putting it alongside
 * the tokens would mean `clear()` on sign-out silently reset it.
 */
@Singleton
class AppearanceStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    val preference: Flow<ThemePreference> = context.appearanceDataStore.data
        .map { ThemePreference.fromWire(it[KEY]) }

    suspend fun set(preference: ThemePreference) {
        context.appearanceDataStore.edit { it[KEY] = preference.wire }
    }

    private companion object {
        val KEY = stringPreferencesKey("theme")
    }
}
