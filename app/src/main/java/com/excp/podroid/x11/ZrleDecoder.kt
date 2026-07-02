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
/**
 * PERFORMANCE NOTE: Tight loops in this decoder are optimized by:
 * 1. Strength reduction: replacing division (/) and modulo (%) with incremental
 *    counters (currentRowBase, currCol). On ARM, division can take 20-80 cycles
 *    vs 1 cycle for addition.
 * 2. Inlining: reducing function call overhead in hot paths (e.g., readByte).
 * 3. Bulk operations: leveraging java.util.Arrays.fill for contiguous pixel spans.
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Inflater

class ZrleDecoder {

    private val inflater = Inflater(/*nowrap=*/false)

    companion object {
        // Maximum pixels in a single ZRLE tile (64x64). Run-length accumulators
        // are capped at this value to prevent Int overflow from wrapping the sum
        // to a large negative number that defeats the overrun guard.
        private const val MAX_TILE_PIXELS = 64 * 64
    }

    // Scratch buffer for compressed input read from the socket.
    // 16KB reduces JNI transitions between JVM and native Inflater on large rects.
    private var inputScratch = ByteArray(16384)

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
     *
     * Call this from the session-level connect path (X11ViewModel.connect()), not
     * from the per-rect decode path.
     */
    fun reset() {
        inflater.reset()
        remaining = 0
        inputStream = null
    }

    /**
     * Decodes one ZRLE-encoded rectangle into [target].
     *
     * @param din    source stream positioned at the 4-byte length prefix of the ZRLE data
     * @param x      left edge of the rectangle in the framebuffer
     * @param y      top edge of the rectangle in the framebuffer
     * @param w      rectangle width
     * @param h      rectangle height
     * @param target ARGB framebuffer array
     * @param stride row stride of [target] (pixels per row)
     */
    fun decode(din: DataInputStream, x: Int, y: Int, w: Int, h: Int, target: IntArray, stride: Int) {
        // Read and decompress the rect's zlib block.
        val compLen = din.readInt()
        if (compLen < 0 || compLen > 64 * 1024 * 1024) throw IOException("ZRLE: absurd compressed length $compLen")

        // Stream the compressed block on demand. setInput() does not copy/append —
        // it holds inputScratch by reference and is consumed lazily by inflate(),
        // so pre-loading multiple chunks into one buffer would drop all but the
        // last. Instead ZInput.fill() reads the next chunk only once the inflater
        // has consumed the previous one, bounded by `remaining` (this rect's
        // compLen) so it never crosses the rect boundary.
        remaining = compLen
        inputStream = din

        // Wrap the inflater so tile-level code just calls readByte()/readBytes().
        val zi = ZInput(inflater)

        // Tile loop: 64x64 tiles in row-major order.
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            val rowBase = (y + ty) * stride + x
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                decodeTile(zi, rowBase + tx, tw, th, target, stride)
                tx += 64
            }
            ty += 64
        }
    }

    private fun decodeTile(zi: ZInput, tileRowBase: Int, tw: Int, th: Int, target: IntArray, stride: Int) {
        val subenc = zi.readByte()
        when {
            subenc == 0 -> {
                // Raw: tw*th CPIXELs.
                // Optimization: replace row index multiplication with incremental currentRowBase.
                var currentRowBase = tileRowBase
                for (row in 0 until th) {
                    for (col in 0 until tw) {
                        target[currentRowBase + col] = zi.readCpixel()
                    }
                    currentRowBase += stride
                }
            }
            subenc == 1 -> {
                // Solid: 1 CPIXEL, fill the whole tile.
                val color = zi.readCpixel()
                // Optimization: replace row index multiplication with incremental currentRowBase.
                var currentRowBase = tileRowBase
                for (row in 0 until th) {
                    java.util.Arrays.fill(target, currentRowBase, currentRowBase + tw, color)
                    currentRowBase += stride
                }
            }
            subenc in 2..16 -> {
                // Packed palette.
                val n = subenc
                val palette = IntArray(n) { zi.readCpixel() }
                val bitsPerIndex = when {
                    n == 2 -> 1
                    n <= 4 -> 2
                    else -> 4
                }
                // Optimization: replace row index multiplication with incremental currentRowBase.
                var currentRowBase = tileRowBase
                for (row in 0 until th) {
                    // Each row is bit-packed, byte-aligned.
                    var col = 0
                    var accumByte = 0
                    var bitsInAccum = 0
                    while (col < tw) {
                        if (bitsInAccum == 0) {
                            accumByte = zi.readByte()
                            bitsInAccum = 8
                        }
                        val idx = (accumByte ushr (8 - bitsPerIndex)) and ((1 shl bitsPerIndex) - 1)
                        accumByte = (accumByte shl bitsPerIndex) and 0xFF
                        bitsInAccum -= bitsPerIndex
                        // bitsPerIndex rounds up, so the index space can exceed n
                        // when n is not a power of two.
                        if (idx >= n) throw IOException("ZRLE: packed palette index $idx >= $n")
                        target[currentRowBase + col] = palette[idx]
                        col++
                    }
                    // Discard any padding bits at end of row (bitsInAccum may be > 0 but
                    // we already read the full byte; nothing extra to consume).
                    currentRowBase += stride
                }
            }
            subenc == 128 -> {
                // Plain RLE: sequence of runs until tile is full.
                val total = tw * th
                var filled = 0
                // Optimization: track current row/column manually to eliminate expensive
                // division (filled / tw) and modulo (filled % tw) from the inner loop.
                var currRow = 0
                var currCol = 0
                while (filled < total) {
                    val color = zi.readCpixel()
                    var runLen = zi.readRunLength()
                    if (filled + runLen > total) throw IOException("ZRLE: plain RLE run overruns tile ($filled+$runLen > $total)")
                    filled += runLen
                    while (runLen > 0) {
                        val n = minOf(runLen, tw - currCol)
                        java.util.Arrays.fill(target, tileRowBase + currRow * stride + currCol, tileRowBase + currRow * stride + currCol + n, color)
                        runLen -= n
                        currCol += n
                        if (currCol == tw) {
                            currRow++
                            currCol = 0
                        }
                    }
                }
            }
            subenc in 130..255 -> {
                // Palette RLE.
                val n = subenc - 128
                val palette = IntArray(n) { zi.readCpixel() }
                val total = tw * th
                var filled = 0
                // Optimization: track current row/column manually to eliminate expensive
                // division (filled / tw) and modulo (filled % tw) from the inner loop.
                var currRow = 0
                var currCol = 0
                while (filled < total) {
                    val indexByte = zi.readByte()
                    if (indexByte and 0x80 == 0) {
                        // Single pixel.
                        if (indexByte >= n) throw IOException("ZRLE: palette RLE index $indexByte >= $n")
                        target[tileRowBase + currRow * stride + currCol] = palette[indexByte]
                        filled++
                        currCol++
                        if (currCol == tw) {
                            currRow++
                            currCol = 0
                        }
                    } else {
                        // Run of palette[index & 0x7F].
                        val idx = indexByte and 0x7F
                        if (idx >= n) throw IOException("ZRLE: palette RLE index $idx >= $n")
                        val color = palette[idx]
                        var runLen = zi.readRunLength()
                        if (filled + runLen > total) throw IOException("ZRLE: palette RLE run overruns tile ($filled+$runLen > $total)")
                        filled += runLen
                        while (runLen > 0) {
                            val chunk = minOf(runLen, tw - currCol)
                            java.util.Arrays.fill(target, tileRowBase + currRow * stride + currCol, tileRowBase + currRow * stride + currCol + chunk, color)
                            runLen -= chunk
                            currCol += chunk
                            if (currCol == tw) {
                                currRow++
                                currCol = 0
                            }
                        }
                    }
                }
            }
            else -> throw IOException("ZRLE: unsupported subencoding $subenc")
        }
    }

    /**
     * Thin wrapper around [Inflater] that provides byte-level and CPIXEL reads.
     * The inflater's input was already loaded by [decode]; this just drains output.
     */
    private inner class ZInput(private val inf: Inflater) {
        // 16KB decompressed output buffer reduces JNI transition overhead.
        private val buf = ByteArray(16384)
        private var pos = 0
        private var avail = 0

        private fun fill() {
            while (avail == 0) {
                if (inf.finished()) throw IOException("ZRLE: inflater finished early")
                // Feed the next compressed chunk on demand, bounded by this rect's
                // remaining budget. Each chunk is fully consumed before its buffer
                // is reused, and we never read past compLen.
                if (inf.needsInput() && remaining > 0) {
                    val nIn = minOf(remaining, inputScratch.size)
                    inputStream!!.readFully(inputScratch, 0, nIn)
                    inf.setInput(inputScratch, 0, nIn)
                    remaining -= nIn
                }
                val n = inf.inflate(buf)
                if (n > 0) { pos = 0; avail = n }
                // n == 0 with needsInput means all input was consumed; if finished() is false
                // but no output and needsInput, the caller overfed or the stream is malformed.
                else if (inf.needsInput()) throw IOException("ZRLE: inflater needs more input but none queued")
                // n == 0, not finished, not needsInput → needsDictionary or a stuck
                // stream: bail rather than spin forever.
                else throw IOException("ZRLE: inflater made no progress")
            }
        }

        fun readByte(): Int {
            // Optimization: inline check for buffer availability to reduce function call
            // overhead in the common case where data is already buffered.
            if (avail == 0) fill()
            return buf[pos++].toInt().also { avail-- } and 0xFF
        }

        /** Read 3-byte CPIXEL (B, G, R) and return as ARGB int. */
        fun readCpixel(): Int {
            if (avail >= 3) {
                // Optimization: direct read from buffer when enough bytes are available.
                val b = buf[pos].toInt() and 0xFF
                val g = buf[pos + 1].toInt() and 0xFF
                val r = buf[pos + 2].toInt() and 0xFF
                pos += 3
                avail -= 3
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val b = readByte()
            val g = readByte()
            val r = readByte()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Read a ZRLE run-length (sum-of-bytes + 1).
         * Each byte 0xFF contributes 255 and reading continues;
         * the first byte < 0xFF ends the sequence, contributing its value.
         * The actual run count = sum + 1.
         *
         * The accumulator is capped at MAX_TILE_PIXELS (4096, the pixel count of
         * the largest possible 64x64 tile) to prevent Int overflow: without the cap,
         * enough 0xFF bytes would wrap the sum to a large negative number, defeating
         * the caller's post-hoc (filled + runLen > total) overrun check.
         */
        fun readRunLength(): Int {
            var total = 0
            while (true) {
                val b = readByte()
                total += b
                if (total > MAX_TILE_PIXELS) throw IOException("ZRLE: run length $total exceeds max tile size")
                if (b != 0xFF) break
            }
            return total + 1
        }
    }
}
