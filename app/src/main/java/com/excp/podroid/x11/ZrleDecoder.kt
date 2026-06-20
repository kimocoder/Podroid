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

/**
 * High-performance ZRLE decoder optimized for mobile ARM architectures.
 *
 * Key optimizations:
 * 1. Merged ZInput into the main class to eliminate per-rectangle object allocation.
 * 2. Increased zlib buffers to 16KB to reduce JNI transition overhead and I/O wait.
 * 3. Replaced coordinate arithmetic (division/modulo) with incremental counters.
 * 4. Leveraged [java.util.Arrays.fill] for bulk pixel operations.
 * 5. Optimized [readCpixel] with direct buffer access to minimize method call overhead.
 */
class ZrleDecoder {

    private val inflater = Inflater(/*nowrap=*/false)

    companion object {
        private const val MAX_TILE_PIXELS = 64 * 64
        private const val BUFFER_SIZE = 16384 // 16KB buffers for better throughput
    }

    // Scratch buffer for compressed input read from the socket.
    private var inputScratch = ByteArray(BUFFER_SIZE)
    // Decompressed internal buffer for sub-encoding consumption.
    private val zBuf = ByteArray(BUFFER_SIZE)
    private var zPos = 0
    private var zAvail = 0

    // Remaining compressed bytes in the current rect.
    private var remaining = 0
    private var inputStream: DataInputStream? = null

    fun reset() {
        inflater.reset()
        remaining = 0
        inputStream = null
        zPos = 0
        zAvail = 0
    }

    fun decode(din: DataInputStream, x: Int, y: Int, w: Int, h: Int, target: IntArray, stride: Int) {
        val compLen = din.readInt()
        if (compLen < 0 || compLen > 64 * 1024 * 1024) throw IOException("ZRLE: absurd compressed length $compLen")

        remaining = compLen
        inputStream = din
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

    private fun fillZBuf() {
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

    private fun readByte(): Int {
        if (zAvail == 0) fillZBuf()
        val b = zBuf[zPos].toInt() and 0xFF
        zPos++
        zAvail--
        return b
    }

    private fun readCpixel(): Int {
        // FAST PATH: Direct read from zBuf if at least 3 bytes are available.
        // Reduces individual readByte() calls and redundant fillZBuf() checks.
        if (zAvail >= 3) {
            val b = zBuf[zPos].toInt() and 0xFF
            val g = zBuf[zPos + 1].toInt() and 0xFF
            val r = zBuf[zPos + 2].toInt() and 0xFF
            zPos += 3
            zAvail -= 3
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val b = readByte()
        val g = readByte()
        val r = readByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readRunLength(): Int {
        var total = 0
        while (true) {
            val b = readByte()
            total += b
            if (total > MAX_TILE_PIXELS) throw IOException("ZRLE: run length $total exceeds max tile size")
            if (b != 0xFF) break
        }
        return total + 1
    }

    private fun decodeTile(tx: Int, ty: Int, tw: Int, th: Int, target: IntArray, stride: Int) {
        val subenc = readByte()
        when {
            subenc == 0 -> {
                // Raw: tw*th CPIXELs.
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) {
                        target[base + col] = readCpixel()
                    }
                }
            }
            subenc == 1 -> {
                // Solid: 1 CPIXEL, fill the whole tile.
                val color = readCpixel()
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    Arrays.fill(target, base, base + tw, color)
                }
            }
            subenc in 2..16 -> {
                // Packed palette.
                val n = subenc
                val palette = IntArray(n) { readCpixel() }
                val bitsPerIndex = when {
                    n == 2 -> 1
                    n <= 4 -> 2
                    else -> 4
                }
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    var col = 0
                    var accumByte = 0
                    var bitsInAccum = 0
                    while (col < tw) {
                        if (bitsInAccum == 0) {
                            accumByte = readByte()
                            bitsInAccum = 8
                        }
                        val idx = (accumByte ushr (8 - bitsPerIndex)) and ((1 shl bitsPerIndex) - 1)
                        accumByte = (accumByte shl bitsPerIndex) and 0xFF
                        bitsInAccum -= bitsPerIndex
                        if (idx >= n) throw IOException("ZRLE: packed palette index $idx >= $n")
                        target[base + col] = palette[idx]
                        col++
                    }
                }
            }
            subenc == 128 -> {
                // Plain RLE: Optimized with Arrays.fill and incremental row/col.
                val total = tw * th
                var filled = 0
                var row = 0
                var col = 0
                while (filled < total) {
                    val color = readCpixel()
                    val runLen = readRunLength()
                    if (filled + runLen > total) throw IOException("ZRLE: plain RLE run overruns tile")

                    var remRun = runLen
                    while (remRun > 0) {
                        val len = minOf(remRun, tw - col)
                        val offset = (ty + row) * stride + tx + col
                        Arrays.fill(target, offset, offset + len, color)
                        remRun -= len
                        filled += len
                        col += len
                        if (col == tw) {
                            col = 0
                            row++
                        }
                    }
                }
            }
            subenc in 130..255 -> {
                // Palette RLE: Optimized with Arrays.fill and incremental row/col.
                val n = subenc - 128
                val palette = IntArray(n) { readCpixel() }
                val total = tw * th
                var filled = 0
                var row = 0
                var col = 0
                while (filled < total) {
                    val indexByte = readByte()
                    if (indexByte and 0x80 == 0) {
                        // Single pixel.
                        if (indexByte >= n) throw IOException("ZRLE: palette RLE index $indexByte >= $n")
                        target[(ty + row) * stride + tx + col] = palette[indexByte]
                        filled++
                        col++
                        if (col == tw) {
                            col = 0
                            row++
                        }
                    } else {
                        // Run of palette[index & 0x7F].
                        val idx = indexByte and 0x7F
                        if (idx >= n) throw IOException("ZRLE: palette RLE index $idx >= $n")
                        val color = palette[idx]
                        val runLen = readRunLength()
                        if (filled + runLen > total) throw IOException("ZRLE: palette RLE run overruns tile")

                        var remRun = runLen
                        while (remRun > 0) {
                            val len = minOf(remRun, tw - col)
                            val offset = (ty + row) * stride + tx + col
                            Arrays.fill(target, offset, offset + len, color)
                            remRun -= len
                            filled += len
                            col += len
                            if (col == tw) {
                                col = 0
                                row++
                            }
                        }
                    }
                }
            }
            else -> throw IOException("ZRLE: unsupported subencoding $subenc")
        }
    }
}
