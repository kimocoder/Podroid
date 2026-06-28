/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BootStageDetector is shared by BOTH the QEMU and AVF backends, so every
 * marker must keep firing for a normal boot stream. These tests pin:
 *   (a) a marker inside a single oversized (>1024-char) chunk is still seen,
 *   (b) a marker split across two feeds is still seen,
 *   (c) a normal small-chunk boot sequence still advances every stage in order
 *       and flips state to Running on "Ready!".
 */
class BootStageDetectorTest {

    private fun newDetector(): Triple<BootStageDetector, MutableStateFlow<String>, MutableStateFlow<VmState>> {
        val stage = MutableStateFlow("")
        val state = MutableStateFlow<VmState>(VmState.Idle)
        val detector = BootStageDetector(stage, state) { /* onReady */ }
        return Triple(detector, stage, state)
    }

    private fun BootStageDetector.feed(s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        feed(bytes, bytes.size)
    }

    @Test
    fun `marker inside a single oversized chunk is detected`() {
        val (detector, stage, _) = newDetector()
        // One feed larger than the old 1024 scan window, with the marker buried
        // well before the last 1024 chars so a fixed-tail scan would miss it.
        val chunk = "x".repeat(1500) + "Almost ready..." + "y".repeat(1500)
        detector.feed(chunk)
        assertEquals("Almost ready...", stage.value)
    }

    @Test
    fun `Ready split across two feeds still fires Running`() {
        val (detector, stage, state) = newDetector()
        detector.feed("boot output ...Rea")
        // First feed: no full marker yet.
        assertTrue(state.value is VmState.Idle)
        detector.feed("dy! more output")
        assertEquals("Ready", stage.value)
        assertTrue(state.value is VmState.Running)
    }

    @Test
    fun `normal small-chunk boot sequence advances every stage in order`() {
        val (detector, stage, state) = newDetector()

        detector.feed("Booting kernel...\n")
        assertEquals("Booting kernel...", stage.value)

        detector.feed("Mounting storage...\n")
        assertEquals("Mounting storage...", stage.value)

        detector.feed("Loading kernel modules...\n")
        assertEquals("Loading kernel modules...", stage.value)

        detector.feed("Network found\n")
        assertEquals("Network found", stage.value)

        detector.feed("Configuring containers...\n")
        assertEquals("Configuring containers...", stage.value)

        detector.feed("Starting SSH...\n")
        assertEquals("Starting SSH...", stage.value)

        detector.feed("Almost ready...\n")
        assertEquals("Almost ready...", stage.value)

        assertTrue(state.value is VmState.Idle)
        detector.feed("Ready!\n")
        assertEquals("Ready", stage.value)
        assertTrue(state.value is VmState.Running)
    }

    @Test
    fun `a fresh detector instance re-detects a new boot (per-run allocation)`() {
        // QEMU and AVF both allocate a new detector per run rather than resetting
        // one in place; a brand-new instance must detect a boot stream from clean.
        val (first, _, firstState) = newDetector()
        first.feed("Ready!\n")
        assertTrue(firstState.value is VmState.Running)

        val (second, secondStage, secondState) = newDetector()
        second.feed("Booting kernel...\n")
        assertEquals("Booting kernel...", secondStage.value)
        second.feed("Ready!\n")
        assertTrue(secondState.value is VmState.Running)
    }
}
