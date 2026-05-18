/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * The single Hilt binding for VmEngine is EngineHolder. The holder picks the
 * concrete engine (QemuEngine vs AvfEngine) at construction, watches Settings
 * for backend changes, and routes Imperative calls + flow access to whichever
 * is current. Provider<QemuEngine>/Provider<AvfEngine> let the holder lazy-
 * instantiate either side so we don't pay AvfEngine's reflection cost on
 * QEMU-only devices.
 */
package com.excp.podroid.engine

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideVmEngine(holder: EngineHolder): VmEngine = holder
}
