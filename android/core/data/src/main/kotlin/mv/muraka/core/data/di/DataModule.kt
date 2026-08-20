package mv.muraka.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.common.StandardDispatcherProvider
import mv.muraka.core.data.repository.AuthRepositoryImpl
import mv.muraka.core.data.repository.NetworkMonitorImpl
import mv.muraka.core.data.repository.OutboxRepositoryImpl
import mv.muraka.core.data.repository.SightingRepositoryImpl
import mv.muraka.core.data.sync.SyncEngineImpl
import mv.muraka.core.domain.AuthRepository
import mv.muraka.core.domain.NetworkMonitor
import mv.muraka.core.domain.OutboxRepository
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.domain.SyncEngine
import javax.inject.Singleton

/**
 * Binds each domain interface to its implementation.
 *
 * `@Binds` rather than `@Provides` throughout: the implementations already have `@Inject`
 * constructors, so this only records which one satisfies which interface. Nothing above
 * `:core:data` ever names an `*Impl` type, which is what lets a view-model test hand in a
 * fake with no DI involved at all.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun authRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    fun sightingRepository(impl: SightingRepositoryImpl): SightingRepository

    @Binds
    @Singleton
    fun outboxRepository(impl: OutboxRepositoryImpl): OutboxRepository

    @Binds
    @Singleton
    fun networkMonitor(impl: NetworkMonitorImpl): NetworkMonitor

    @Binds
    @Singleton
    fun syncEngine(impl: SyncEngineImpl): SyncEngine

    @Binds
    @Singleton
    fun dispatcherProvider(impl: StandardDispatcherProvider): DispatcherProvider
}
