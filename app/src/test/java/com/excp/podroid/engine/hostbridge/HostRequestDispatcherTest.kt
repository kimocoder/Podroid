package com.excp.podroid.engine.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostProtocolTest {
    @Test fun base64RoundTripsUtf8() {
        // Non-ASCII (CJK) + spaces + newline must survive.
        val original = "构建完成 build done\nline2"
        assertEquals(original, HostProtocol.dec(HostProtocol.enc(original)))
    }

    @Test fun decReturnsNullOnGarbage() {
        assertNull(HostProtocol.dec("!!!not base64!!!"))
    }
}
