/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure logic for the AVF multi-vCPU boot policy. AVF's public Builder API only
 * exposes ONE_CPU (1 vCPU) or MATCH_HOST (all host cores) - see
 * AvfReflect.setNumCpus. MATCH_HOST clones the host's heterogeneous big.LITTLE
 * topology into the guest device tree, which makes the guest reset in early
 * boot on some SoCs (Tensor G3/G4: STOP_REASON_REBOOT, no panic - issue #29).
 * 1 vCPU always boots because none of that topology is emitted.
 *
 * Two tiers request multi-core (see AvfEngine.buildConfig):
 *  - Android 16+: an exact homogeneous vCPU count via the raw AIDL
 *    (AvfReflect.installExplicitCpuCount) - no heterogeneous topology at all.
 *  - Otherwise: MATCH_HOST bounded by `nr_cpus=N` on the kernel cmdline
 *    (all vCPUs still exist; the kernel onlines only N).
 *
 * This object decides the effective core count and the descending fallback
 * ladder shared by both tiers, adaptively and without any per-device model
 * hardcoding: a normal launch tries the user's requested count; an early-boot
 * reset steps the count down and the result is persisted per device so later
 * launches go straight to the value that boots.
 *
 * Pure (no Android deps) so it unit-tests on the JVM.
 */
package com.excp.podroid.engine.avf

object AvfCpuPolicy {

    /** Sentinel for "no cap recorded yet" (persisted default). */
    const val NO_CAP: Int = 0

    /**
     * The vCPU count to actually launch with, given the user's [requested] count
     * and the per-device [cap] discovered by prior boots ([NO_CAP] = none yet).
     * Never returns below 1.
     */
    fun effectiveCpus(requested: Int, cap: Int): Int {
        val r = requested.coerceAtLeast(1)
        return if (cap > NO_CAP) minOf(r, cap).coerceAtLeast(1) else r
    }

    /**
     * After an early-boot reset while attempting [current] vCPUs, the next lower
     * count to try, or null to stop laddering (a single-vCPU boot that still
     * resets is a genuine failure, not a topology cap).
     *
     * Ladder: anything above 2 collapses straight to 2 (the highest value proven
     * to boot on an affected SoC), then 2 -> 1 (always boots). Monotonic and
     * finite, so the retry path can never loop.
     */
    fun nextRungDown(current: Int): Int? = when {
        current > 2 -> 2
        current == 2 -> 1
        else -> null
    }
}
