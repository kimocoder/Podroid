/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.x11.AudioStreamer
import com.excp.podroid.x11.ResolutionMode
import com.excp.podroid.x11.ResolutionPolicy
import com.excp.podroid.x11.ResolutionPreset
import com.excp.podroid.x11.RotationLock
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient
import com.excp.podroid.x11.VncRect
import com.excp.podroid.x11.VncSize
import com.excp.podroid.x11.X11Constants
import com.excp.podroid.x11.X11Settings
import com.excp.podroid.x11.ZrleDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

sealed interface X11ConnectionState {
    object Disconnected : X11ConnectionState
    object Connecting : X11ConnectionState
    object Connected : X11ConnectionState
    data class Failed(val message: String) : X11ConnectionState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class X11ViewModel @Inject constructor(
    val engine: VmEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state

    private val _connection = MutableStateFlow<X11ConnectionState>(X11ConnectionState.Disconnected)
    val connection: StateFlow<X11ConnectionState> = _connection.asStateFlow()

    val x11Settings = settings.x11Settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), X11Settings())

    private val _fbSize = MutableStateFlow(VncSize(X11Constants.FB_WIDTH, X11Constants.FB_HEIGHT))
    val fbSize: StateFlow<VncSize> = _fbSize.asStateFlow()

    val cursor = MutableStateFlow(android.graphics.Point(X11Constants.FB_WIDTH / 2, X11Constants.FB_HEIGHT / 2))

    @Volatile private var fbW = X11Constants.FB_WIDTH
    @Volatile private var fbH = X11Constants.FB_HEIGHT
    @Volatile var framebuffer: IntArray = IntArray(fbW * fbH); private set
    // Dedicated lock object so synchronized() is never on the reassigned framebuffer field.
    val fbLock = Any()
    @Volatile private var scratch: IntArray = IntArray(fbW * fbH)
    private val zrle = ZrleDecoder()
    @Volatile private var screenId = 0
    @Volatile private var desiredW = 0; @Volatile private var desiredH = 0
    @Volatile var lastDamage: List<VncRect> = emptyList(); private set

    private val _frameCounter = MutableStateFlow(0)
    val frameCounter: StateFlow<Int> = _frameCounter.asStateFlow()

    private val audio = AudioStreamer()
    private var sessionJob: Job? = null
    @Volatile private var rfbOut: OutputStream? = null
    @Volatile private var rfbSocket: Socket? = null

    // All post-handshake RFB output (pointer, key, SetDesktopSize, and the
    // recurring FramebufferUpdateRequest from the read loop) flows through this
    // single-parallelism dispatcher. limitedParallelism(1) runs each launched
    // body one-at-a-time in dispatch (submission) order; because each body is a
    // non-suspending blocking write+flush, every RFB message is written
    // atomically and messages keep submission order (key-down before key-up,
    // press before release). Without this, writes on the multi-thread IO pool
    // and the read coroutine interleaved at byte granularity, desyncing the
    // VNC server. (The initial handshake/negotiate/first-update writes run
    // directly before Connected, where no concurrent write is possible yet.)
    private val rfbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Submit a blocking RFB write onto the serialized writer (never blocks the caller). */
    private fun submitRfb(block: (OutputStream) -> Unit) {
        val out = rfbOut ?: return
        viewModelScope.launch(rfbDispatcher) { runCatching { block(out) } }
    }

    fun connect() {
        if (sessionJob?.isActive == true) return
        _connection.value = X11ConnectionState.Connecting
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            val sock = Socket()
            try {
                rfbSocket = sock
                sock.connect(InetSocketAddress("127.0.0.1", X11Constants.VNC_PORT), 2000)
                val inp = sock.getInputStream()
                val out = sock.getOutputStream()
                rfbOut = out
                // Each RFB session is a fresh zlib stream; reset the ZRLE inflater
                // before the read loop so a reconnect doesn't feed a finished/leftover
                // inflater (which yields corrupt output or DataFormatException).
                zrle.reset()

                VncClient.handshake(inp, out)
                VncClient.negotiatePixelFormat(out)
                if (desiredW > 0) VncClient.requestDesktopSize(out, screenId, desiredW, desiredH)
                VncClient.requestFramebufferUpdate(out, w = fbW, h = fbH, incremental = false)
                _connection.value = X11ConnectionState.Connected
                audio.start(viewModelScope)
                while (isActive) {
                    val upd = VncClient.readFramebufferUpdate(inp, scratch, fbW, zrle)
                    val ns = upd.newSize
                    if (ns != null && (ns.w != fbW || ns.h != fbH)) {
                        fbW = ns.w; fbH = ns.h
                        val fresh = IntArray(fbW * fbH)
                        // Clear damage in the same critical section that swaps the
                        // framebuffer so a recomposition between resize and the next
                        // full frame can't blit stale damage rects against the new size.
                        synchronized(fbLock) { framebuffer = fresh; lastDamage = emptyList() }
                        scratch = IntArray(fbW * fbH)
                        _fbSize.value = ns
                        cursor.value = android.graphics.Point(fbW / 2, fbH / 2)
                        // Route through the serialized writer so this full-update
                        // request can't byte-interleave with a concurrent input
                        // write. Capture the just-resized dimensions explicitly.
                        val rw = fbW; val rh = fbH
                        submitRfb { VncClient.requestFramebufferUpdate(it, w = rw, h = rh, incremental = false) }
                        // Skip the rest of this iteration: the old code used
                        // return@let here, which only exited the let lambda and
                        // then fell through to overwrite lastDamage with rects
                        // measured against the OLD geometry (the exact race the
                        // synchronized block above prevents) and fire a spurious
                        // incremental request.
                        continue
                    }
                    synchronized(fbLock) {
                        System.arraycopy(scratch, 0, framebuffer, 0, framebuffer.size)
                        lastDamage = upd.damage
                    }
                    _frameCounter.value = _frameCounter.value + 1
                    // Same serialized path as input writes: queued FIFO behind any
                    // in-flight pointer/key message rather than colliding with it on
                    // the socket. Cadence is unchanged (one request per frame); the
                    // next read() blocks until this flushes and the server responds.
                    submitRfb { VncClient.requestFramebufferUpdate(it, w = fbW, h = fbH, incremental = true) }
                }
            } catch (e: Exception) {
                _connection.value = X11ConnectionState.Failed(e.message ?: "unknown")
            } finally {
                rfbOut = null
                rfbSocket = null
                // Close the socket on the serialized writer, queued AFTER any pending
                // RFB writes (e.g. the button-up from disconnect()), so a teardown
                // can't tear the socket down before a final message has flushed.
                viewModelScope.launch(rfbDispatcher) { runCatching { sock.close() } }
                audio.stop()
                if (_connection.value !is X11ConnectionState.Failed) {
                    _connection.value = X11ConnectionState.Disconnected
                }
            }
        }
    }

    fun disconnect() {
        // If a button is still held (e.g. leaving mid-drag-lock), tell the server
        // to release it BEFORE the session/socket is torn down, otherwise the
        // guest X server keeps the button held forever. This button-up is queued
        // on the serialized writer; the socket close (in connect()'s finally) is
        // queued after it, so the up flushes before the socket goes away.
        if (heldButtons != 0) {
            val c = cursor.value
            submitRfb { VncClient.sendPointer(it, c.x, c.y, 0) }
        }
        heldButtons = 0
        sessionJob?.cancel()
        sessionJob = null
    }

    @Volatile private var lastViewportW = 0
    @Volatile private var lastViewportH = 0

    fun requestResolution(viewportW: Int, viewportH: Int) {
        lastViewportW = viewportW
        lastViewportH = viewportH
        val s = x11Settings.value
        val t = ResolutionPolicy.target(s, viewportW, viewportH)
        desiredW = t.w; desiredH = t.h
        submitRfb { VncClient.requestDesktopSize(it, screenId, t.w, t.h) }
    }

    fun setResolutionMode(m: ResolutionMode) {
        viewModelScope.launch { settings.setX11ResolutionMode(m.name) }
        val explicit = x11Settings.value.copy(resolutionMode = m)
        reapplyResolution(explicit)
    }

    fun setPreset(p: ResolutionPreset) {
        viewModelScope.launch { settings.setX11Preset(p.name) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.PRESET, preset = p)
        reapplyResolution(explicit)
    }

    fun setCustom(w: Int, h: Int) {
        // Clamp to a sane desktop max that also stays within the 16-bit field
        // requestDesktopSize truncates to, so a value entered in the sheet can
        // never wrap (e.g. 70000 -> 4464). Lower bound 1 avoids a 0-sized desktop.
        val cw = w.coerceIn(1, MAX_RESOLUTION)
        val ch = h.coerceIn(1, MAX_RESOLUTION)
        viewModelScope.launch { settings.setX11Custom(cw, ch) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.CUSTOM, customW = cw, customH = ch)
        reapplyResolution(explicit)
    }

    fun setRotation(r: RotationLock) {
        viewModelScope.launch { settings.setX11Rotation(r.name) }
    }

    fun setTouchMode(m: TouchMode) {
        viewModelScope.launch { settings.setX11TouchMode(m.name) }
    }

    fun setTrackpadSensitivity(v: Float) {
        viewModelScope.launch { settings.setX11TrackpadSensitivity(v) }
    }

    fun setTrackpadAccel(v: Boolean) {
        viewModelScope.launch { settings.setX11TrackpadAccel(v) }
    }

    fun setShowExtraKeys(v: Boolean) {
        viewModelScope.launch { settings.setX11ShowExtraKeys(v) }
    }

    fun setFullscreenDefault(v: Boolean) {
        viewModelScope.launch { settings.setX11Fullscreen(v) }
    }

    fun setDpi(v: Int) {
        viewModelScope.launch { settings.setX11Dpi(v) }
    }

    private fun reapplyResolution(explicit: X11Settings) {
        if (lastViewportW <= 0) return
        val t = ResolutionPolicy.target(explicit, lastViewportW, lastViewportH)
        desiredW = t.w; desiredH = t.h
        submitRfb { VncClient.requestDesktopSize(it, screenId, t.w, t.h) }
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        submitRfb { VncClient.sendPointer(it, x, y, buttonMask) }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        submitRfb { VncClient.sendKey(it, keysym, down) }
    }

    fun moveTo(x: Int, y: Int) { cursor.value = android.graphics.Point(x.coerceIn(0, fbW - 1), y.coerceIn(0, fbH - 1)); sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    @Volatile private var heldButtons = 0
    fun press(button: Int) { heldButtons = heldButtons or button; sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    fun release(button: Int) { heldButtons = heldButtons and button.inv(); sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    fun click(button: Int) { press(button); release(button) }
    /** Physical-mouse update: absolute position + the full button mask in one event.
     *  Mouse is authoritative for the button mask (mask=0 on release must clear bits).
     *  A simultaneous touch drag-lock + physical mouse is a rare mixed-input edge left
     *  as-is; OR-ing the mask here would make mouse buttons un-releasable. */
    fun mouseUpdate(x: Int, y: Int, mask: Int) {
        val nx = x.coerceIn(0, fbW - 1); val ny = y.coerceIn(0, fbH - 1)
        cursor.value = android.graphics.Point(nx, ny)
        heldButtons = mask
        sendPointer(nx, ny, heldButtons)
    }
    fun scroll(up: Boolean, ticks: Int = 1) { val b = if (up) VncClient.BTN_WHEEL_UP else VncClient.BTN_WHEEL_DOWN; repeat(ticks) { sendPointer(cursor.value.x, cursor.value.y, heldButtons or b); sendPointer(cursor.value.x, cursor.value.y, heldButtons) } }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        // Sane desktop ceiling; also <= 0xFFFF so a custom value never wraps the
        // 16-bit width/height fields of the SetDesktopSize wire message.
        const val MAX_RESOLUTION = 7680
    }
}
