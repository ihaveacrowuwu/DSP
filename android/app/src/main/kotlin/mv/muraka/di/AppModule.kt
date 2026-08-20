package mv.muraka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mv.muraka.BuildConfig
import mv.muraka.core.network.di.NetworkModule
import javax.inject.Named
import javax.inject.Singleton

/**
 * The handful of bindings that can only be made by the application module, because only
 * it has a `BuildConfig`.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Where the API lives.
     *
     * `10.0.2.2` from the emulator (the emulator's own `localhost` is the emulated
     * device), `https://` in release. Injected rather than hardcoded in `:core:network`
     * because a library module has no `BuildConfig` and should not care which host it is
     * pointed at.
     */
    @Provides
    @Singleton
    @Named(NetworkModule.BASE_URL)
    fun baseUrl(): String = BuildConfig.API_BASE_URL
}
