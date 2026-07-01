/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Deflater

class ZrleDecoderTest {
    /** Encode 3-byte CPIXEL from an ARGB int: order is B, G, R (little-endian channels). */
    private fun cpixel(argb: Int) = byteArrayOf(
        (argb and 0xFF).toByte(),           // B
        ((argb shr 8) and 0xFF).toByte(),   // G
        ((argb shr 16) and 0xFF).toByte()   // R
    )

    /** Compress plain bytes with zlib (nowrap=false = standard zlib header) and prepend 4-byte big-endian length. */
    private fun zrleRect(plain: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/false)
        def.setInput(plain); def.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); out.write(buf, 0, n) }
        val z = out.toByteArray()
        val hdr = java.nio.ByteBuffer.allocate(4).putInt(z.size).array()
        return hdr + z
    }

    @Test fun `solid tile fills with its color`() {
        val red = 0xFFFF0000.toInt()
        val plain = byteArrayOf(1) + cpixel(red)                 // subencoding 1 = solid
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(4 * 4)
        ZrleDecoder().decode(din, 0, 0, 4, 4, target, 4)
        assertEquals(red, target[0]); assertEquals(red, target[15])
    }

    @Test fun `raw tile copies pixels`() {
        val a = 0xFF010203.toInt(); val b = 0xFF040506.toInt()
        val plain = byteArrayOf(0) + cpixel(a) + cpixel(b) + cpixel(a) + cpixel(b) // 2x2 raw
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(2 * 2)
        ZrleDecoder().decode(din, 0, 0, 2, 2, target, 2)
        assertEquals(a, target[0]); assertEquals(b, target[1]); assertEquals(a, target[2]); assertEquals(b, target[3])
    }

    /**
     * Marquee fix A: a rect whose compressed zlib block exceeds the 4096-byte
     * inputScratch must decode correctly. High-entropy raw tiles across a wide
     * rect compress to >4 KB, so the old pre-load loop dropped all but the last
     * 4 KB chunk and zlib reported "invalid distance code".
     */
    @Test fun `wide rect with compressed block over 4KB decodes correctly`() {
        // 320x64 rect = 5 raw tiles of 64x64. Pseudo-random colors so zlib can't
        // shrink it below 4096 bytes compressed.
        val w = 320; val h = 64
        val src = IntArray(w * h)
        var seed = 0x12345678
        for (i in src.indices) {
            // xorshift PRNG → high-entropy, deterministic
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            src[i] = (0xFF shl 24) or (seed and 0xFFFFFF)
        }
        // Build the ZRLE tile stream: row-major 64x64 raw tiles.
        val plain = java.io.ByteArrayOutputStream()
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                plain.write(0) // subencoding 0 = raw
                for (row in 0 until th) for (col in 0 until tw) {
                    plain.write(cpixel(src[(ty + row) * w + (tx + col)]))
                }
                tx += 64
            }
            ty += 64
        }
        val rect = zrleRect(plain.toByteArray())
        // Sanity: the compressed block must actually exceed inputScratch (4096).
        val compLen = java.nio.ByteBuffer.wrap(rect, 0, 4).int
        org.junit.Assert.assertTrue("compressed block must exceed 4096 (was $compLen)", compLen > 4096)

        val din = DataInputStream(ByteArrayInputStream(rect))
        val target = IntArray(w * h)
        ZrleDecoder().decode(din, 0, 0, w, h, target, w)
        org.junit.Assert.assertArrayEquals(src, target)
    }

    /** High: a packed-palette tile whose index exceeds the palette size → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `packed palette index out of range throws IOException`() {
        // Palette of 2 entries (subenc 2, 1 bit/index). Feed a row byte 0b1000_0000:
        // first index = 1 (valid). Use a 1-entry palette via subenc would not be 2..16;
        // instead force overflow with n=2 but a single-color palette is fine — to
        // overflow we use n that makes idx exceed: build subenc=3 (n=3, 2 bits/idx),
        // so idx can be 0..3 but palette only has 3 entries; idx=3 overflows.
        val n = 3
        val plain = java.io.ByteArrayOutputStream()
        plain.write(n) // subencoding 3 = packed palette, 3 entries
        repeat(n) { plain.write(cpixel(0xFF000000.toInt() or it)) }
        // 1x1 tile: one packed byte. 2 bits/index, top 2 bits = 0b11 = idx 3 (out of range).
        plain.write(0b1100_0000)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 1, 1, IntArray(1), 1)
    }

    /** High: a plain-RLE run that overruns the tile total → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `plain RLE run overrun throws IOException`() {
        // 2x2 tile (total=4). One run of length 5 (> 4) must be rejected.
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE
        plain.write(cpixel(0xFFAABBCC.toInt()))
        // run-length encoding: sum-of-bytes + 1 == 5 → sum 4 → single byte 0x04
        plain.write(0x04)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 2, 2, IntArray(4), 2)
    }

    // Fix 3: a run-length whose accumulated sum exceeds the maximum tile size
    // (4096 pixels for a 64x64 tile) must be rejected inside readRunLength itself,
    // by the explicit accumulator cap, BEFORE the sum can be used. Without the cap
    // a sufficiently long 0xFF run overflows Int and wraps the sum negative,
    // defeating the caller's post-hoc (filled + runLen > total) overrun guard.
    //
    // The fixture (17 x 0xFF + 0x00 → run = 4336) is caught on BOTH paths, so the
    // exception type alone proves nothing: pre-fix the caller's overrun guard fires
    // ("plain RLE run overruns tile"), post-fix the cap fires ("exceeds max tile
    // size"). Asserting on the message pins detection to the cap — RED pre-fix
    // (message mismatch), GREEN post-fix.
    @Test
    fun `ZRLE run length exceeding tile size throws from readRunLength cap`() {
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE
        plain.write(cpixel(0xFFAABBCC.toInt()))
        // 17 x 0xFF: running sum = 17*255 = 4335, exceeds the 4096-pixel cap.
        repeat(17) { plain.write(0xFF) }
        plain.write(0x00)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        val ex = assertThrows(IOException::class.java) {
            ZrleDecoder().decode(din, 0, 0, 64, 64, IntArray(64 * 64), 64)
        }
        assertTrue(
            "expected the readRunLength cap to fire, was: ${ex.message}",
            ex.message?.contains("exceeds max tile size") == true,
        )
    }

    /** High: a palette-RLE single-pixel index beyond the palette → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `palette RLE index out of range throws IOException`() {
        // Palette of 2 entries (subenc 130). A single-pixel index byte 0x05 (bit7=0,
        // index 5) exceeds the 2-entry palette.
        val plain = java.io.ByteArrayOutputStream()
        plain.write(130) // palette RLE, n = 2
        plain.write(cpixel(0xFF111111.toInt())); plain.write(cpixel(0xFF222222.toInt()))
        plain.write(0x05) // single pixel, index 5 → out of range
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 2, 2, IntArray(4), 2)
    }

    @Test
    fun `plain RLE multi-row run decodes correctly`() {
        // 4x4 tile, one run of 6 pixels (spans row 0 and part of row 1)
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE
        plain.write(cpixel(blue))
        plain.write(0x05) // run-length 6
        plain.write(cpixel(green))
        plain.write(0x09) // run-length 10 (rest of the 4x4 tile)

        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        val target = IntArray(4 * 4)
        ZrleDecoder().decode(din, 0, 0, 4, 4, target, 4)

        for (i in 0 until 6) assertEquals("Pixel $i should be blue", blue, target[i])
        for (i in 6 until 16) assertEquals("Pixel $i should be green", green, target[i])
    }

    @Test
    fun `palette RLE multi-row run decodes correctly`() {
        // 4x4 tile, palette of 2 colors
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        val plain = java.io.ByteArrayOutputStream()
        plain.write(130) // palette RLE, n=2
        plain.write(cpixel(blue))
        plain.write(cpixel(green))
        // Run of palette[0] (blue), length 6
        plain.write(0x80 or 0)
        plain.write(0x05)
        // Run of palette[1] (green), length 10
        plain.write(0x80 or 1)
        plain.write(0x09)

        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        val target = IntArray(4 * 4)
        ZrleDecoder().decode(din, 0, 0, 4, 4, target, 4)

        for (i in 0 until 6) assertEquals("Pixel $i should be blue", blue, target[i])
        for (i in 6 until 16) assertEquals("Pixel $i should be green", green, target[i])
    }
}
