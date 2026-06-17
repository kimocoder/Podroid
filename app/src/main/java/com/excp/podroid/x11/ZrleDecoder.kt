/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * ZRLE (RFB encoding 16) decoder.
 *
 * Wire format per rect:
 *   4 bytes big-endian compressed-data length
 *   N bytes zlib-compressed tile stream (standard zlib header, nowrap=false)
 *
 * The zlib stream is continuous across rects for the lifetime of the RFB session,
 * so a single Inflater is held as instance state and must NOT be re-created per call.
 *
 * Tiles are 64x64 max, row-major (left to right, top to bottom) covering the w*h rect.
 * Each tile begins with 1 subencoding byte:
 *   0        raw:          tw*th CPIXELs in raster order
 *   1        solid:        1 CPIXEL fills the whole tile
 *   2..16    packed-palette: N CPIXELs palette, then packed indices (1/2/4 bpp), row-aligned
 *   128      plain RLE:    sequence of (CPIXEL, run-length); run-length = sum-of-bytes+1
 *                          (each 0xFF means +255 and continue; final <0xFF byte ends the run)
 *   130..255 palette RLE:  palette of (subenc-128) CPIXELs; then index bytes:
 *                          bit7=0 -> single pixel palette[index]; bit7=1 -> palette[index&0x7F]
 *                          repeated run-length times (same sum-of-bytes+1 encoding)
 *
 * CPIXEL = 3 bytes in wire order B, G, R (matching the negotiated 32bpp/depth-24/LE/R16-G8-B0
 * pixel format). Decoded to ARGB: 0xFF_000000 | R<<16 | G<<8 | B.
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.IOException
import java.util.Arrays
import java.util.zip.Inflater

class ZrleDecoder {

    private val inflater = Inflater(/*nowrap=*/false)

    companion object {
        // Maximum pixels in a single ZRLE tile (64x64). Run-length accumulators
        // are capped at this value to prevent Int overflow from wrapping the sum
        // to a large negative number that defeats the overrun guard.
        private const val MAX_TILE_PIXELS = 64 * 64

        // 16KB buffers to minimize JNI transition overhead between JVM and native Inflater.
        private const val BUFFER_SIZE = 16384
    }

    // Scratch buffer for compressed input read from the socket.
    private var inputScratch = ByteArray(BUFFER_SIZE)

    // Decompressed output buffer (internal to ZInput logic).
    private var zBuf = ByteArray(BUFFER_SIZE)
    private var zPos = 0
    private var zAvail = 0

    // Remaining compressed bytes in the current rect that have not yet been fed to the inflater.
    private var remaining = 0
    private var inputStream: DataInputStream? = null

    /**
     * Resets decoder state between RFB sessions.
     *
     * The zlib stream is continuous within a single RFB session, so the [Inflater]
     * must NOT be reset between rects. It MUST be reset on reconnect: a new RFB
     * session starts a fresh zlib stream, and feeding it into a finished or leftover
     * inflater produces corrupt output or a DataFormatException.
     */
    fun reset() {
        inflater.reset()
        remaining = 0
        inputStream = null
        zPos = 0
        zAvail = 0
    }

    /**
     * Decodes one ZRLE-encoded rectangle into [target].
     */
    fun decode(din: DataInputStream, x: Int, y: Int, w: Int, h: Int, target: IntArray, stride: Int) {
        val compLen = din.readInt()
        if (compLen < 0 || compLen > 64 * 1024 * 1024) throw IOException("ZRLE: absurd compressed length $compLen")

        remaining = compLen
        inputStream = din

        // Reset decompression buffer state for the new rectangle.
        zPos = 0
        zAvail = 0

        // Tile loop: 64x64 tiles in row-major order.
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                decodeTile(x + tx, y + ty, tw, th, target, stride)
                tx += 64
            }
            ty += 64
        }
    }

    private fun decodeTile(tx: Int, ty: Int, tw: Int, th: Int, target: IntArray, stride: Int) {
        val subenc = zReadByte()
        when {
            subenc == 0 -> {
                // Raw: tw*th CPIXELs.
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) {
                        target[base + col] = zReadCpixel()
                    }
                }
            }
            subenc == 1 -> {
                // Solid: 1 CPIXEL, fill the whole tile.
                val color = zReadCpixel()
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    Arrays.fill(target, base, base + tw, color)
                }
            }
            subenc in 2..16 -> {
                // Packed palette.
                val n = subenc
                val palette = IntArray(n) { zReadCpixel() }
                val bitsPerIndex = when {
                    n == 2 -> 1
                    n <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bitsPerIndex) - 1
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    var col = 0
                    var accumByte = 0
                    var bitsInAccum = 0
                    while (col < tw) {
                        if (bitsInAccum == 0) {
                            accumByte = zReadByte()
                            bitsInAccum = 8
                        }
                        val idx = (accumByte ushr (8 - bitsPerIndex)) and mask
                        accumByte = (accumByte shl bitsPerIndex) and 0xFF
                        bitsInAccum -= bitsPerIndex
                        if (idx >= n) throw IOException("ZRLE: packed palette index $idx >= $n")
                        target[base + col] = palette[idx]
                        col++
                    }
                }
            }
            subenc == 128 -> {
                // Plain RLE: sequence of runs until tile is full.
                val total = tw * th
                var filled = 0
                var currRow = 0
                var currCol = 0
                while (filled < total) {
                    val color = zReadCpixel()
                    val runLen = zReadRunLength()
                    if (filled + runLen > total) throw IOException("ZRLE: plain RLE run overruns tile ($filled+$runLen > $total)")

                    var remRun = runLen
                    while (remRun > 0) {
                        val canDo = minOf(remRun, tw - currCol)
                        val base = (ty + currRow) * stride + tx + currCol
                        if (canDo > 1) {
                            Arrays.fill(target, base, base + canDo, color)
                        } else {
                            target[base] = color
                        }
                        remRun -= canDo
                        currCol += canDo
                        if (currCol == tw) {
                            currCol = 0
                            currRow++
                        }
                    }
                    filled += runLen
                }
            }
            subenc in 130..255 -> {
                // Palette RLE.
                val n = subenc - 128
                val palette = IntArray(n) { zReadCpixel() }
                val total = tw * th
                var filled = 0
                var currRow = 0
                var currCol = 0
                while (filled < total) {
                    val indexByte = zReadByte()
                    if (indexByte and 0x80 == 0) {
                        // Single pixel.
                        if (indexByte >= n) throw IOException("ZRLE: palette RLE index $indexByte >= $n")
                        target[(ty + currRow) * stride + (tx + currCol)] = palette[indexByte]
                        filled++
                        currCol++
                        if (currCol == tw) {
                            currCol = 0
                            currRow++
                        }
                    } else {
                        // Run of palette[index & 0x7F].
                        val idx = indexByte and 0x7F
                        if (idx >= n) throw IOException("ZRLE: palette RLE index $idx >= $n")
                        val color = palette[idx]
                        val runLen = zReadRunLength()
                        if (filled + runLen > total) throw IOException("ZRLE: palette RLE run overruns tile ($filled+$runLen > $total)")

                        var remRun = runLen
                        while (remRun > 0) {
                            val canDo = minOf(remRun, tw - currCol)
                            val base = (ty + currRow) * stride + tx + currCol
                            if (canDo > 1) {
                                Arrays.fill(target, base, base + canDo, color)
                            } else {
                                target[base] = color
                            }
                            remRun -= canDo
                            currCol += canDo
                            if (currCol == tw) {
                                currCol = 0
                                currRow++
                            }
                        }
                        filled += runLen
                    }
                }
            }
            else -> throw IOException("ZRLE: unsupported subencoding $subenc")
        }
    }

    private fun zFill() {
        while (zAvail == 0) {
            if (inflater.finished()) throw IOException("ZRLE: inflater finished early")
            if (inflater.needsInput() && remaining > 0) {
                val nIn = minOf(remaining, inputScratch.size)
                inputStream!!.readFully(inputScratch, 0, nIn)
                inflater.setInput(inputScratch, 0, nIn)
                remaining -= nIn
            }
            val n = inflater.inflate(zBuf)
            if (n > 0) {
                zPos = 0
                zAvail = n
            } else if (inflater.needsInput()) {
                throw IOException("ZRLE: inflater needs more input but none queued")
            } else {
                throw IOException("ZRLE: inflater made no progress")
            }
        }
    }

    private fun zReadByte(): Int {
        if (zAvail == 0) zFill()
        zAvail--
        return zBuf[zPos++].toInt() and 0xFF
    }

    /** Read 3-byte CPIXEL (B, G, R) and return as ARGB int. */
    private fun zReadCpixel(): Int {
        // Fast-path: read directly from buffer if enough bytes are available.
        if (zAvail >= 3) {
            val b = zBuf[zPos++].toInt() and 0xFF
            val g = zBuf[zPos++].toInt() and 0xFF
            val r = zBuf[zPos++].toInt() and 0xFF
            zAvail -= 3
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // Slow-path: multi-call readByte.
        val b = zReadByte()
        val g = zReadByte()
        val r = zReadByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun zReadRunLength(): Int {
        var total = 0
        while (true) {
            val b = zReadByte()
            total += b
            if (total > MAX_TILE_PIXELS) throw IOException("ZRLE: run length $total exceeds max tile size")
            if (b != 0xFF) break
        }
        return total + 1
    }
}
