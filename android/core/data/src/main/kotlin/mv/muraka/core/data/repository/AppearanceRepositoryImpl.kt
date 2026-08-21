package mv.muraka.core.data.repository

import kotlinx.coroutines.flow.Flow
import mv.muraka.core.datastore.AppearanceStore
import mv.muraka.core.domain.AppearanceRepository
import mv.muraka.core.model.ThemePreference
import javax.inject.Inject
import javax.inject.Singleton

/** A thin pass-through, so the view model depends on the domain rather than on DataStore. */
@Singleton
class AppearanceRepositoryImpl @Inject constructor(private val store: AppearanceStore) : AppearanceRepository {

    override val themePreference: Flow<ThemePreference> = store.preference

    override suspend fun setThemePreference(preference: ThemePreference) = store.set(preference)

    override val showPatchGrid: Flow<Boolean> = store.showPatchGrid

    override suspend fun setShowPatchGrid(visible: Boolean) = store.setShowPatchGrid(visible)
}
