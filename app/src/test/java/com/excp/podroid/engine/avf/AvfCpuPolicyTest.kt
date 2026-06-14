package com.excp.podroid.engine.avf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvfCpuPolicyTest {

    // --- effectiveCpus ---

    @Test fun `no cap uses the requested count`() {
        assertEquals(8, AvfCpuPolicy.effectiveCpus(requested = 8, cap = AvfCpuPolicy.NO_CAP))
        assertEquals(4, AvfCpuPolicy.effectiveCpus(requested = 4, cap = 0))
    }

    @Test fun `cap clamps the requested count`() {
        assertEquals(2, AvfCpuPolicy.effectiveCpus(requested = 8, cap = 2))
        assertEquals(1, AvfCpuPolicy.effectiveCpus(requested = 8, cap = 1))
    }

    @Test fun `cap above request does not raise the count`() {
        assertEquals(2, AvfCpuPolicy.effectiveCpus(requested = 2, cap = 8))
    }

    @Test fun `result is never below one`() {
        assertEquals(1, AvfCpuPolicy.effectiveCpus(requested = 0, cap = AvfCpuPolicy.NO_CAP))
        assertEquals(1, AvfCpuPolicy.effectiveCpus(requested = -1, cap = 1))
    }

    @Test fun `a non-positive cap is treated as no cap`() {
        assertEquals(4, AvfCpuPolicy.effectiveCpus(requested = 4, cap = -3))
        assertEquals(4, AvfCpuPolicy.effectiveCpus(requested = 4, cap = 0))
    }

    // --- nextRungDown ---

    @Test fun `above two collapses to two`() {
        assertEquals(2, AvfCpuPolicy.nextRungDown(8))
        assertEquals(2, AvfCpuPolicy.nextRungDown(4))
        assertEquals(2, AvfCpuPolicy.nextRungDown(3))
    }

    @Test fun `two steps to one`() {
        assertEquals(1, AvfCpuPolicy.nextRungDown(2))
    }

    @Test fun `one or below stops laddering`() {
        assertNull(AvfCpuPolicy.nextRungDown(1))
        assertNull(AvfCpuPolicy.nextRungDown(0))
    }

    @Test fun `ladder is finite and monotonic from a high count`() {
        // 8 -> 2 -> 1 -> stop, never loops.
        val first = AvfCpuPolicy.nextRungDown(8)!!
        assertEquals(2, first)
        val second = AvfCpuPolicy.nextRungDown(first)!!
        assertEquals(1, second)
        assertNull(AvfCpuPolicy.nextRungDown(second))
    }
}
