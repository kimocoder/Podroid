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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
) {
    companion object { private const val TAG = "VsockPortForwarder" }

    private var server: ServerSocket? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var closed = false

    fun start() {
        val s = ServerSocket(hostPort, /* backlog */ 16, InetAddress.getByName("0.0.0.0"))
        server = s
        Log.d(TAG, "listening on 0.0.0.0:$hostPort → vsock:$guestVsockPort")
        jobs += scope.launch(Dispatchers.IO) {
            while (!closed) {
                val client = try { s.accept() } catch (_: SocketException) { break }
                jobs += scope.launch(Dispatchers.IO) { proxy(client) }
            }
        }
    }

    private suspend fun proxy(tcp: Socket) = coroutineScope {
        val pfd = try {
            AvfReflect.connectVsock(vm, guestVsockPort.toLong())
        } catch (e: Throwable) {
            // Surface the underlying ErrnoException class — e.message alone is
            // null for many ECONNREFUSED/EAFNOSUPPORT paths, so the bare
            // "${e.message}" gave "failed: null" with zero diagnostic value.
            val cause = e.cause ?: e
            Log.w(TAG, "connectVsock($guestVsockPort) failed: " +
                "${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}")
            runCatching { tcp.close() }
            return@coroutineScope
        }
        val vsockIn  = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val vsockOut = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
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
            runCatching { tcp.close() }
            runCatching { pfd.close() }
        }
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

    fun close() {
        if (closed) return
        closed = true
        runCatching { server?.close() }
        jobs.forEach { runCatching { it.cancel() } }
        jobs.clear()
    }
}
