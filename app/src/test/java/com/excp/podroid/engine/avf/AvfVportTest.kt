package com.excp.podroid.engine.avf

import com.excp.podroid.data.repository.PortForwardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AvfVportTest {
    @Test fun tcp_vport_equals_host_port() {
        assertEquals(9922, AvfVport.forRule(PortForwardRule(9922, 22, "tcp")))
    }

    @Test fun udp_vport_is_offset() {
        assertEquals(5353 + 0x10000, AvfVport.forRule(PortForwardRule(5353, 5353, "udp")))
    }

    @Test fun tcp_and_udp_on_same_host_port_do_not_collide() {
        val tcp = AvfVport.forRule(PortForwardRule(5353, 5353, "tcp"))
        val udp = AvfVport.forRule(PortForwardRule(5353, 5353, "udp"))
        assertNotEquals(tcp, udp)
    }

    @Test fun udp_range_never_overlaps_tcp_range() {
        // Max TCP vport (65535) stays below the min UDP vport (1 + 0x10000).
        assertEquals(65535, AvfVport.forRule(PortForwardRule(65535, 1, "tcp")))
        assertEquals(1 + 0x10000, AvfVport.forRule(PortForwardRule(1, 1, "udp")))
    }
}
