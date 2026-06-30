/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal RFB 3.8 client. Supports SecurityType None, Raw + CopyRect +
 * ExtendedDesktopSize + ZRLE encodings. Designed for loopback (SLIRP).
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

data class VncServerInfo(val width: Int, val height: Int, val name: String)

object VncClient {
    private const val PROTOCOL_VERSION = "RFB 003.008\n"
    private const val SEC_TYPE_NONE: Byte = 1

    /**
     * Performs the RFB 3.8 handshake. Reads the server greeting from `inp`,
     * writes our responses to `out`, and returns the framebuffer dimensions
     * (the only ServerInit fields we need for v1 — pixel format is fixed
     * 32-bit BGRA via SetPixelFormat sent later by the caller).
     *
     * Throws IOException on protocol mismatch.
     */
    fun handshake(inp: InputStream, out: OutputStream): VncServerInfo {
        val din = DataInputStream(inp)

        // 1. Read 12-byte version "RFB xxx.yyy\n"
        val serverVersion = ByteArray(12).also { din.readFully(it) }
        require(serverVersion[0] == 'R'.code.toByte()) { "not RFB greeting" }

        // 2. Send our version (always 003.008)
        out.write(PROTOCOL_VERSION.toByteArray())
        out.flush()

        // 3. Read security types. 0 => failure (not handled here)
        val numTypes = din.readUnsignedByte()
        require(numTypes > 0) { "server reported zero security types" }
        val types = ByteArray(numTypes).also { din.readFully(it) }
        require(types.any { it == SEC_TYPE_NONE }) { "server has no None auth" }

        // 4. Choose None
        out.write(byteArrayOf(SEC_TYPE_NONE))
        out.flush()

        // 5. Read SecurityResult (4 bytes; 0 = OK)
        val secResult = din.readInt()
        require(secResult == 0) { "security result $secResult" }

        // 6. Send ClientInit (1 byte: shared = 1)
        out.write(byteArrayOf(1))
        out.flush()

        // 7. Read ServerInit
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        skipFully(din, 16) // pixel format we'll override
        val nameLen = din.readInt()
        require(nameLen in 0..(1 shl 20)) { "RFB name length $nameLen" }
        val name = ByteArray(nameLen).also { din.readFully(it) }.toString(Charsets.UTF_8)

        return VncServerInfo(w, h, name)
    }

    /**
     * Reads and discards exactly [n] bytes from [din]. DataInputStream.skipBytes
     * may skip fewer than requested over a socket when not all bytes have
     * arrived; a short skip would leave unconsumed bytes and desync RFB framing.
     * No behavior change when bytes are already buffered.
     */
    private fun skipFully(din: DataInputStream, n: Int) {
        var left = n
        val scratch = ByteArray(minOf(n, 4096).coerceAtLeast(1))
        while (left > 0) {
            val r = din.read(scratch, 0, minOf(left, scratch.size))
            if (r < 0) throw java.io.IOException("RFB: EOF skipping $n bytes ($left remaining)")
            left -= r
        }
    }

    /**
     * Validates a server-supplied rect against the framebuffer before any pixel
     * access. Reachable on a resolution-change race where the server sends a
     * rect sized to a different desktop than the client's current buffer.
     * No-op for in-range rects.
     */
    private fun requireInBounds(x: Int, y: Int, w: Int, h: Int, stride: Int, size: Int) {
        if (stride <= 0) throw java.io.IOException("RFB: zero or negative stride $stride")
        val rows = size / stride
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > stride || y + h > rows)
            throw java.io.IOException("RFB rect out of bounds: x=$x y=$y w=$w h=$h stride=$stride size=$size")
    }

    private const val MSG_FRAMEBUFFER_UPDATE: Int = 0
    private const val ENC_RAW: Int = 0
    private const val ENC_COPY_RECT: Int = 1
    private const val ENC_ZRLE = 16
    private const val ENC_EXTENDED_DESKTOP_SIZE = -308

    /**
     * Sends SetPixelFormat to lock the server to 32-bit BGRA, then SetEncodings
     * to advertise Raw + CopyRect. Call once after handshake before requesting
     * any framebuffer update.
     */
    fun negotiatePixelFormat(out: OutputStream) {
        // SetPixelFormat (msg=0): pad[3] + 16-byte PixelFormat
        val pf = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,                         // msg + 3 pad
            32, 24, 0, 1,                                   // bpp, depth, big-endian=0, true-color=1
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                       // shifts: R=16, G=8, B=0 (=> ARGB packed)
            0, 0, 0,                                        // padding
        )
        out.write(pf)

        // SetEncodings (msg=2): CopyRect, Raw, ExtendedDesktopSize(-308).
        // ZRLE is still NOT advertised, but the historical desync cause is now
        // fixed: ZrleDecoder fed the inflater in chunks without draining, dropping
        // all but the last 4 KB of any block >4096 bytes ("invalid distance code").
        // It now feeds on demand and bounds-checks palette indices and run lengths.
        // Re-enabling ZRLE changes what the server streams, so it stays gated until
        // validated on-device against real Firefox/xfce tiles (a separate change).
        val se = java.nio.ByteBuffer.allocate(4 + 3 * 4)
        se.put(2.toByte()); se.put(0.toByte()); se.putShort(3)
        se.putInt(1)      // CopyRect
        se.putInt(0)      // Raw
        se.putInt(-308)   // ExtendedDesktopSize
        out.write(se.array()); out.flush()
    }

    /**
     * Send a FramebufferUpdateRequest. `incremental=false` forces the server
     * to send a full refresh (use after first connect or on reconnect).
     */
    fun requestFramebufferUpdate(
        out: OutputStream,
        x: Int = 0, y: Int = 0,
        w: Int = X11Constants.FB_WIDTH,
        h: Int = X11Constants.FB_HEIGHT,
        incremental: Boolean = true,
    ) {
        val buf = java.nio.ByteBuffer.allocate(10)
        buf.put(3.toByte())                                 // msg-type
        buf.put(if (incremental) 1.toByte() else 0)
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        out.write(buf.array())
        out.flush()
    }

    data class RfbUpdate(val newSize: VncSize?, val damage: List<VncRect>)

    fun readFramebufferUpdate(inp: InputStream, targetArgb: IntArray, stride: Int, zrle: ZrleDecoder): RfbUpdate {
        val din = DataInputStream(inp)
        var msgType: Int
        while (true) {
            msgType = din.readUnsignedByte()
            when (msgType) {
                MSG_FRAMEBUFFER_UPDATE -> break
                1 -> { skipFully(din, 1); din.readUnsignedShort(); val n = din.readUnsignedShort(); skipFully(din, n * 6) }
                2 -> { }
                3 -> { skipFully(din, 3); val len = din.readInt(); if (len in 0..(1 shl 20)) skipFully(din, len) else throw java.io.IOException("ServerCutText absurd length=$len") }
                else -> throw java.io.IOException("unexpected RFB server msg type $msgType")
            }
        }
        skipFully(din, 1)
        val numRects = din.readUnsignedShort()
        var rowBuf: ByteArray? = null
        var newSize: VncSize? = null
        val damage = ArrayList<VncRect>(numRects)

        repeat(numRects) {
            val x = din.readUnsignedShort(); val y = din.readUnsignedShort()
            val w = din.readUnsignedShort(); val h = din.readUnsignedShort()
            val enc = din.readInt()
            when (enc) {
                ENC_EXTENDED_DESKTOP_SIZE -> {       // -308: w/h are the new fb dims
                    val screens = din.readUnsignedByte(); skipFully(din, 3)
                    skipFully(din, screens * 16)     // we use a single-screen model; dims come from w/h
                    if (w <= 0 || h <= 0) throw java.io.IOException("RFB ExtendedDesktopSize: degenerate geometry w=$w h=$h")
                    newSize = VncSize(w, h)
                }
                ENC_RAW -> {
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    val needed = w * 4
                    val rowPixels = rowBuf?.takeIf { it.size >= needed } ?: ByteArray(needed).also { rowBuf = it }
                    var rowBase = y * stride + x
                    for (row in 0 until h) {
                        din.readFully(rowPixels, 0, needed)
                        var off = 0
                        for (col in 0 until w) {
                            val b = rowPixels[off].toInt() and 0xFF
                            val g = rowPixels[off + 1].toInt() and 0xFF
                            val r = rowPixels[off + 2].toInt() and 0xFF
                            targetArgb[rowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            off += 4
                        }
                        rowBase += stride
                    }
                    damage.add(VncRect(x, y, w, h))
                }
                ENC_COPY_RECT -> {
                    val srcX = din.readUnsignedShort(); val srcY = din.readUnsignedShort()
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    requireInBounds(srcX, srcY, w, h, stride, targetArgb.size)
                    if (srcY < y) {
                        var srcRowBase = (srcY + h - 1) * stride + srcX
                        var rowBase = (y + h - 1) * stride + x
                        for (row in h - 1 downTo 0) {
                            System.arraycopy(targetArgb, srcRowBase, targetArgb, rowBase, w)
                            srcRowBase -= stride
                            rowBase -= stride
                        }
                    } else {
                        var srcRowBase = srcY * stride + srcX
                        var rowBase = y * stride + x
                        for (row in 0 until h) {
                            System.arraycopy(targetArgb, srcRowBase, targetArgb, rowBase, w)
                            srcRowBase += stride
                            rowBase += stride
                        }
                    }
                    damage.add(VncRect(x, y, w, h))
                }
                ENC_ZRLE -> {
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    zrle.decode(din, x, y, w, h, targetArgb, stride)
                    damage.add(VncRect(x, y, w, h))
                }
                else -> throw java.io.IOException("unsupported encoding $enc")
            }
        }
        return RfbUpdate(newSize, damage)
    }

    const val BTN_LEFT = 1; const val BTN_MIDDLE = 2; const val BTN_RIGHT = 4
    const val BTN_WHEEL_UP = 8; const val BTN_WHEEL_DOWN = 16

    /** SetDesktopSize (msg 251): request a single-screen desktop of width x height. */
    fun requestDesktopSize(out: OutputStream, screenId: Int, width: Int, height: Int) {
        val buf = java.nio.ByteBuffer.allocate(24)   // 8 header + 16 screen
        buf.put(251.toByte()); buf.put(0.toByte())
        buf.putShort(width.toShort()); buf.putShort(height.toShort())
        buf.put(1.toByte()); buf.put(0.toByte())     // number-of-screens=1, pad
        buf.putInt(screenId)                         // id
        buf.putShort(0); buf.putShort(0)             // x, y
        buf.putShort(width.toShort()); buf.putShort(height.toShort())
        buf.putInt(0)                                // flags
        out.write(buf.array()); out.flush()
    }

    private const val MSG_KEY_EVENT: Byte = 4
    private const val MSG_POINTER_EVENT: Byte = 5

    /**
     * Sends a PointerEvent. `buttonMask` bit i = button (i+1) pressed.
     * Bit 0 = left, 1 = middle, 2 = right, 3 = scroll-up, 4 = scroll-down.
     */
    fun sendPointer(out: OutputStream, x: Int, y: Int, buttonMask: Int) {
        val buf = java.nio.ByteBuffer.allocate(6)
        buf.put(MSG_POINTER_EVENT)
        buf.put(buttonMask.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        out.write(buf.array())
        out.flush()
    }

    /**
     * Sends a KeyEvent. `keysym` is an X11 keysym (e.g. 0x61 for 'a').
     */
    fun sendKey(out: OutputStream, keysym: Int, down: Boolean) {
        val buf = java.nio.ByteBuffer.allocate(8)
        buf.put(MSG_KEY_EVENT)
        buf.put(if (down) 1.toByte() else 0)
        buf.putShort(0)
        buf.putInt(keysym)
        out.write(buf.array())
        out.flush()
    }
}
