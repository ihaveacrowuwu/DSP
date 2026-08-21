package mv.muraka.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        .map { ThemePreference.fromWire(it[THEME]) }

    /**
     * Whether the patch lattice is drawn over photographs.
     *
     * Defaults to **on**: it is the point of the detail screen, and a contributor who has
     * never seen it cannot know to turn it on. Remembered rather than reset per screen,
     * because somebody comparing several sightings against the raw photographs should not
     * have to turn it off once per sighting.
     */
    val showPatchGrid: Flow<Boolean> = context.appearanceDataStore.data
        .map { it[SHOW_PATCH_GRID] ?: true }

    suspend fun set(preference: ThemePreference) {
        context.appearanceDataStore.edit { it[THEME] = preference.wire }
    }

    suspend fun setShowPatchGrid(visible: Boolean) {
        context.appearanceDataStore.edit { it[SHOW_PATCH_GRID] = visible }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val SHOW_PATCH_GRID = booleanPreferencesKey("show_patch_grid")
    }
}
