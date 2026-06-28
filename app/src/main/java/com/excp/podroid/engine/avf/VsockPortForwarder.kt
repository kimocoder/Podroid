/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Per-rule TCP listener that bridges Android-side connections to a vsock port
 * on the guest. Listens on 0.0.0.0:hostPort so LAN devices (`ssh root@<phone-IP>
 * -p 9922`, `vncviewer <phone-IP>:5900`) can reach the VM without going through
 * 127.0.0.1.
 *
 * Lifecycle is bounded by the caller's scope: cancelling the scope tears down
 * the accept loop and every per-connection pump. Use [close] for the explicit
 * "remove this rule" path so the inner `accept()` blocking call returns via
 * SocketException instead of hanging until scope cancellation.
 */
package com.excp.podroid.engine.avf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class VsockPortForwarder(
    private val hostPort: Int,
    private val guestVsockPort: Int,
    private val vm: Any,
    private val scope: CoroutineScope,
    // 127.0.0.1 for the implicit VNC/audio forwards (off the network);
    // 0.0.0.0 for user rules so a PC on the LAN can reach them.
    private val bindAddress: String = "0.0.0.0",
) : Forwarder {
    companion object {
        private const val TAG = "VsockPortForwarder"
        // A runtime-added rule sends the control ADD then immediately starts the
        // listener; the guest agent forks its vsock listener asynchronously, so
        // the very first connection can land in the gap and get ECONNREFUSED.
        // Mirror the control channel's retry, but briefly (per-connection).
        private const val CONNECT_ATTEMPTS = 6
        private const val CONNECT_RETRY_MS = 250L
        // Hard ceiling on simultaneous in-flight proxy connections. A burst
        // against 0.0.0.0:hostPort while the guest agent isn't up would otherwise
        // launch one coroutine per inbound TCP connection, each holding a socket
        // for up to ~1.5s of connect retries — an unbounded fd/coroutine spike.
        private const val MAX_INFLIGHT = 64
    }

    private var server: ServerSocket? = null
    // Parent of every per-connection coroutine so close() cancels them all at
    // once and completed connections don't accumulate Job references for the
    // forwarder's lifetime (the old plain list never removed finished jobs).
    private val connections = SupervisorJob()
    private var acceptJob: Job? = null
    // Live client sockets — force-closed in close() so a pump blocked in a
    // native read() unblocks (cancel() alone can't interrupt a blocking read).
    private val openSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    // Caps concurrent in-flight proxy() coroutines so an accept burst can't
    // exhaust fds/coroutines. tryAcquire (non-suspending) keeps the accept loop
    // hot: when the cap is hit we drop the new connection immediately rather than
    // parking coroutines that would each pin a socket while waiting for a permit.
    private val inflight = Semaphore(MAX_INFLIGHT)
    @Volatile private var closed = false

    override fun start() {
        val s = ServerSocket(hostPort, /* backlog */ 16, InetAddress.getByName(bindAddress))
        server = s
        Log.d(TAG, "listening on $bindAddress:$hostPort → vsock:$guestVsockPort")
        acceptJob = scope.launch(Dispatchers.IO) {
            while (!closed) {
                val client = try { s.accept() } catch (_: SocketException) { break }
                if (!inflight.tryAcquire()) {
                    Log.w(TAG, "inflight cap ($MAX_INFLIGHT) reached on :$hostPort; dropping connection")
                    runCatching { client.close() }
                    continue
                }
                openSockets.add(client)
                scope.launch(Dispatchers.IO + connections) {
                    try { proxy(client) } finally { inflight.release() }
                }
            }
        }
    }

    private suspend fun proxy(tcp: Socket) = coroutineScope {
        val pfd = connectVsockWithRetry()
        if (pfd == null) {
            openSockets.remove(tcp)
            runCatching { tcp.close() }
            return@coroutineScope
        }
        // FD ownership: the read side owns `pfd`; the write side owns a dup of
        // it. Each AutoClose stream closes exactly ONE descriptor, and there is
        // no explicit pfd.close() in the finally. The previous code wrapped the
        // SAME pfd in both an AutoCloseInputStream and an AutoCloseOutputStream
        // AND closed it again in finally — three closers of one descriptor, a
        // classic double-close / FD-reuse cross-talk hazard.
        val pfdOut = runCatching { pfd.dup() }.getOrNull()
        if (pfdOut == null) {
            openSockets.remove(tcp)
            runCatching { pfd.close() }
            runCatching { tcp.close() }
            return@coroutineScope
        }
        val vsockIn  = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val vsockOut = ParcelFileDescriptor.AutoCloseOutputStream(pfdOut)
        val tcpIn  = tcp.getInputStream()
        val tcpOut = tcp.getOutputStream()
        try {
            val a = launch(Dispatchers.IO) { copyUntilEof(tcpIn, vsockOut) }
            val b = launch(Dispatchers.IO) { copyUntilEof(vsockIn, tcpOut) }
            // Whichever direction EOFs first cancels its sibling so the
            // socket closes cleanly on both halves.
            select<Unit> {
                a.onJoin { b.cancel() }
                b.onJoin { a.cancel() }
            }
        } finally {
            openSockets.remove(tcp)
            runCatching { tcp.close() }
            // Close both stream owners; each owns a distinct fd (pfd / its dup).
            runCatching { vsockIn.close() }
            runCatching { vsockOut.close() }
        }
    }

    private suspend fun connectVsockWithRetry(): ParcelFileDescriptor? {
        var lastCause: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (closed) return null
            val pfd = runCatching { AvfReflect.connectVsock(vm, guestVsockPort.toLong()) }
                .getOrElse { lastCause = it.cause ?: it; null }
            if (pfd != null) return pfd
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_MS)
        }
        // Surface the underlying ErrnoException class — e.message alone is null
        // for many ECONNREFUSED/EAFNOSUPPORT paths, so the bare "${e.message}"
        // gave "failed: null" with zero diagnostic value.
        val cause = lastCause
        Log.w(TAG, "connectVsock($guestVsockPort) failed after $CONNECT_ATTEMPTS attempts: " +
            "${cause?.javaClass?.simpleName}: ${cause?.message ?: "(no message)"}")
        return null
    }

    private fun copyUntilEof(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: java.io.IOException) { /* peer closed — normal exit */ }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server?.close() }            // unblocks accept()
        runCatching { acceptJob?.cancel() }
        // Force-close live sockets so pumps blocked in native read() throw + exit;
        // cancelling the coroutines alone wouldn't interrupt a blocking read.
        synchronized(openSockets) {
            openSockets.forEach { runCatching { it.close() } }
            openSockets.clear()
        }
        runCatching { connections.cancel() }
    }
}
