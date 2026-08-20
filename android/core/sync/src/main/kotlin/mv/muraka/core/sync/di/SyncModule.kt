package mv.muraka.core.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mv.muraka.core.domain.SyncScheduler
import mv.muraka.core.sync.SyncSchedulerImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
    @Binds
    @Singleton
    fun syncScheduler(impl: SyncSchedulerImpl): SyncScheduler
}
