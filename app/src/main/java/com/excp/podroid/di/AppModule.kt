/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Hilt module for dependency injection.
 */
package com.excp.podroid.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the application-lifetime [CoroutineScope] — created once and never
 * cancelled while the process lives. Use it for fire-and-forget persistence that
 * must finish even when the caller's own scope is torn down mid-write (e.g. a
 * ViewModel cleared on back-navigation cancelling its viewModelScope before a
 * DataStore write commits — see issue #46).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * SupervisorJob so one failed write doesn't cancel the scope for every other
     * consumer; Dispatchers.IO because every consumer here persists to DataStore
     * or disk.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
