/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VncClientTest {

    @Test
    fun `handshake replies with RFB 003 008 and selects None auth`() {
        // Server greeting: "RFB 003.008\n" then 1 security type + [None=1]
        val serverBytes = byteArrayOf(
            // 12-byte version greeting
            'R'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '8'.code.toByte(), '\n'.code.toByte(),
            // Security types: count=1, [None=1]
            0x01, 0x01,
            // SecurityResult: OK=0
            0x00, 0x00, 0x00, 0x00,
            // ServerInit: width=1280, height=720, pixel-format (16 bytes), name-len=0
            0x05, 0x00,                                         // width 1280
            0x02, 0xD0.toByte(),                                // height 720
            32, 24, 0, 1,                                       // bpp, depth, big-endian, true-color
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                           // shifts (ARGB)
            0, 0, 0,                                            // padding
            0, 0, 0, 0,                                         // name length 0
        )
        val out = ByteArrayOutputStream()

        val info = VncClient.handshake(ByteArrayInputStream(serverBytes), out)

        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        // Client must have sent: version "RFB 003.008\n", then sec-type 1, then shared=1
        val sent = out.toByteArray()
        // First 12 bytes: client version
        assertArrayEquals("RFB 003.008\n".toByteArray(), sent.copyOfRange(0, 12))
        // Byte 12: chosen security type = 1 (None)
        assertEquals(1, sent[12].toInt())
        // Byte 13: ClientInit shared flag = 1
        assertEquals(1, sent[13].toInt())
    }

    @Test
    fun `Raw rectangle update writes pixels into target ARGB buffer`() {
        // Pre-built FramebufferUpdate message:
        //   msg-type=0, padding, num-rects=1
        //   rect: x=0, y=0, w=2, h=1, encoding=0 (Raw)
        //   pixels: 2 BGRA pixels = red, green
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x02, 0x00, 0x01, // w=2, h=1
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            // Pixel 0: BGRA = (0, 0, 0xFF, 0xFF) -> red
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            // Pixel 1: BGRA = (0, 0xFF, 0, 0xFF) -> green
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
        )
        val target = IntArray(2)

        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = target,
            stride = 2,
        )

        // ARGB packed: 0xAARRGGBB
        assertEquals(0xFFFF0000.toInt(), target[0])  // red
        assertEquals(0xFF00FF00.toInt(), target[1])  // green
    }
}
