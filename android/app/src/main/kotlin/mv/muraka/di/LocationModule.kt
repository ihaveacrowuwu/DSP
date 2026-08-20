package mv.muraka.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mv.muraka.core.domain.LocationProvider
import mv.muraka.location.PlatformLocationProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface LocationModule {
    @Binds
    @Singleton
    fun locationProvider(impl: PlatformLocationProvider): LocationProvider
}
