/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Reads raw signed-16-bit-LE stereo PCM from PulseAudio's
 * module-simple-protocol-tcp source and pumps it into AudioTrack.
 * Auto-reconnects on socket drop with 1 s backoff.
 */
package com.excp.podroid.x11

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

class AudioStreamer(private val host: String = "127.0.0.1") {

    companion object {
        private const val TAG = "AudioStreamer"
        // 16-bit stereo PCM = 4 bytes / frame; 4096 is a multiple of 4 so
        // readFully(buf, 0, BUF_BYTES) always yields whole frames. Mis-
        // aligned reads (raw `read()` returning e.g. 4093 bytes) feed
        // AudioTrack a half-frame, after which every subsequent sample is
        // shifted by 1–3 bytes — the audible result was the clicking the
        // user reported in Firefox video playback.
        private const val BUF_BYTES = 4096
        // 16-bit stereo = 4 bytes/frame. Any partial-frame write desyncs L/R
        // for the rest of the stream, so the write loop must never abandon a
        // chunk mid-frame.
        private const val FRAME_BYTES = 4
    }

    private var job: Job? = null
    // Held so stop() can close it: readFully blocks indefinitely when the audio
    // source is quiet, so cancelling the job alone leaves the socket + thread
    // leaked on screen exit. Closing the socket unblocks readFully with an
    // exception, letting the loop observe cancellation and exit.
    @Volatile private var socket: Socket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private suspend fun runLoop() {
        // Nullable so a failed (re)build leaves no reference to a released track.
        // The write loop only signals "rebuild needed" by nulling this; the actual
        // (re)build happens at the top of each connection attempt, where a throw
        // propagates to the outer catch with `track` already null — the loop can
        // never write to a released AudioTrack, and finally never double-releases.
        var track: AudioTrack? = null
        try {
            while (coroutineContext.isActive) {
                try {
                    // Ensure a live track before this connection's write loop. If a
                    // prior iteration nulled `track` after releasing it, build a fresh
                    // one here. Build into a local and only publish to `track` after
                    // play() succeeds; if build/play throws, release the half-built
                    // local and propagate with `track` still null, so the reconnect
                    // path retries cleanly without ever writing to a released track.
                    val activeTrack = track ?: run {
                        val fresh = buildTrack()
                        try {
                            fresh.play()
                        } catch (e: Throwable) {
                            fresh.release()
                            throw e
                        }
                        track = fresh
                        fresh
                    }
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress(host, X11Constants.AUDIO_PORT), 2000)
                        // Disable Nagle — audio frames are tiny and latency-
                        // sensitive; coalescing them adds jitter that AudioTrack
                        // resamples to fill, producing audible artefacts.
                        s.tcpNoDelay = true
                        val din = DataInputStream(s.getInputStream())
                        val buf = ByteArray(BUF_BYTES)
                        while (coroutineContext.isActive) {
                            // readFully → exactly BUF_BYTES (= integer frames).
                            // Throws EOFException on socket close → catch outer
                            // reconnects.
                            din.readFully(buf, 0, BUF_BYTES)
                            // Loop on write: AudioTrack may accept fewer than
                            // BUF_BYTES per call. A negative return is an
                            // ERROR_* (e.g. ERROR_DEAD_OBJECT after a route
                            // change) → release this track, null the field so a
                            // failed rebuild can't leave a released track in use,
                            // and bail to the outer loop, which rebuilds cleanly.
                            // A return of exactly 0 is transient (buffer full);
                            // back off briefly rather than spinning without advancing.
                            var off = 0
                            var zeroRetries = 0
                            while (off < BUF_BYTES) {
                                val n = activeTrack.write(buf, off, BUF_BYTES - off)
                                when {
                                    n < 0 -> {
                                        Log.v(TAG, "AudioTrack.write error $n; rebuilding")
                                        track = null
                                        activeTrack.stop(); activeTrack.release()
                                        throw java.io.IOException("AudioTrack.write error $n")
                                    }
                                    n == 0 -> {
                                        if (++zeroRetries > 8) {
                                            // Give up this chunk — but only at a frame
                                            // boundary. Breaking mid-frame would shift
                                            // every later sample by 1-3 bytes (L/R swap /
                                            // clicking). If we're mid-frame, rebuild the
                                            // track instead so the stream realigns cleanly.
                                            if (off % FRAME_BYTES != 0) {
                                                Log.v(TAG, "AudioTrack stalled mid-frame; rebuilding to keep alignment")
                                                track = null
                                                activeTrack.stop(); activeTrack.release()
                                                throw java.io.IOException("AudioTrack stalled mid-frame")
                                            }
                                            break
                                        }
                                        delay(5)
                                    }
                                    else -> { off += n; zeroRetries = 0 }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.v(TAG, "audio reconnect: ${e.message}")
                    socket = null
                    delay(1000)
                }
            }
        } finally {
            socket = null
            track?.stop()
            track?.release()
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            X11Constants.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Target ~100 ms — 4410 frames @ 44.1 kHz * 2 channels * 2 bytes/sample
        // ≈ 17.6 KB. Floor at minBuf so AudioTrack never refuses to build.
        // Previously we used minBuf * 4 which gave ~170 ms one-way latency —
        // audible lag on click feedback in X11.
        val targetBuf = (X11Constants.AUDIO_SAMPLE_RATE / 10 *
            X11Constants.AUDIO_CHANNELS * 2).coerceAtLeast(minBuf)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(X11Constants.AUDIO_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(1.0f) }
    }
}
