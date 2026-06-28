/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Per-rule UDP forwarder for the AVF backend. Binds 0.0.0.0:hostPort as a
 * DatagramSocket and, per distinct client source address ("flow"), opens one
 * vsock stream to the guest agent's udp listener. Datagrams are length-framed
 * (DatagramFraming) over the stream; the guest relays them to a guest-local UDP
 * socket. Flows are reaped after idle and capped to bound the blast radius from
 * spoofed-source UDP floods (UDP source addresses are trivially forged on a LAN).
 *
 * Mirrors VsockPortForwarder's resource discipline: SupervisorJob parent for
 * per-flow coroutines, force-close the socket in close() to unblock receive(),
 * and AutoClose streams that each own exactly one fd (pfd / its dup).
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class VsockUdpForwarder(
    private val hostPort: Int,
    private val guestVsockPort: Int,
    private val vm: Any,
    private val scope: CoroutineScope,
    // 127.0.0.1 for loopback-only rules; 0.0.0.0 for user rules (see VsockPortForwarder).
    private val bindAddress: String = "0.0.0.0",
) : Forwarder {
    companion object {
        private const val TAG = "VsockUdpForwarder"
        private const val CONNECT_ATTEMPTS = 6
        private const val CONNECT_RETRY_MS = 250L
        // Bounds fds/coroutines/guest processes against a spoofed-source flood.
        private const val MAX_FLOWS = 128
        // A flow idle this long is reaped (closing it makes the guest child EOF).
        private const val IDLE_MS = 60_000L
        private const val REAP_INTERVAL_MS = 15_000L
        private const val UDP_MAX = 65535
    }

    private class Flow(
        val out: OutputStream,
        val input: InputStream,
    ) {
        lateinit var readerJob: Job
        @Volatile var lastActivityMs = System.currentTimeMillis()
    }

    private var socket: DatagramSocket? = null
    private val flows = java.util.concurrent.ConcurrentHashMap<SocketAddress, Flow>()
    private val children = SupervisorJob()
    private var recvJob: Job? = null
    private var reaperJob: Job? = null
    @Volatile private var closed = false

    override fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(InetAddress.getByName(bindAddress), hostPort))
        } catch (e: Throwable) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        Log.d(TAG, "listening udp $bindAddress:$hostPort → vsock:$guestVsockPort")
        recvJob = scope.launch(Dispatchers.IO + children) { receiveLoop(s) }
        reaperJob = scope.launch(Dispatchers.IO + children) { reapLoop() }
    }

    private suspend fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(UDP_MAX)
        while (!closed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (_: SocketException) {
                break // socket closed by close()
            } catch (_: IOException) {
                continue
            }
            val src = pkt.socketAddress
            val payload = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val flow = flows[src] ?: openFlow(src, s) ?: continue
            flow.lastActivityMs = System.currentTimeMillis()
            try {
                flow.out.write(DatagramFraming.encode(payload, payload.size))
                flow.out.flush()
            } catch (_: IOException) {
                closeFlow(src)
            }
        }
    }

    /** Opens a vsock flow for [src]. Returns null if capped or the connect failed. */
    private suspend fun openFlow(src: SocketAddress, s: DatagramSocket): Flow? {
        if (flows.size >= MAX_FLOWS) {
            Log.w(TAG, "flow cap ($MAX_FLOWS) reached on udp:$hostPort; dropping $src")
            return null
        }
        val pfd = connectVsockWithRetry() ?: return null
        val pfdOut = runCatching { pfd.dup() }.getOrNull()
        if (pfdOut == null) {
            runCatching { pfd.close() }
            return null
        }
        // read side owns `pfd`; write side owns its dup. Each AutoClose stream
        // closes exactly one fd.
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfdOut)
        val flow = Flow(out, input)
        flow.readerJob = scope.launch(Dispatchers.IO + children) {
            try {
                while (!closed) {
                    val reply = DatagramFraming.readFrame(input) ?: break
                    flows[src]?.lastActivityMs = System.currentTimeMillis()
                    runCatching { s.send(DatagramPacket(reply, reply.size, src)) }
                }
            } catch (_: IOException) {
                // guest closed the flow / torn down — normal
            } finally {
                // Remove + close only if we still own the map entry, so a
                // cancelled loser-reader (lost putIfAbsent) can never evict the
                // winner's flow. closeFlow() is the authoritative key-based path.
                if (flows.remove(src, flow)) {
                    runCatching { out.close() }
                    runCatching { input.close() }
                }
            }
        }
        val existing = flows.putIfAbsent(src, flow)
        if (existing != null) {
            // Lost the race; tear ours down. The reader's finally will no-op its
            // value-matched remove (winner owns the entry), so close here.
            flow.readerJob.cancel()
            runCatching { out.close() }
            runCatching { input.close() }
            return existing
        }
        return flow
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
        val cause = lastCause
        Log.w(TAG, "connectVsock($guestVsockPort) failed after $CONNECT_ATTEMPTS attempts: " +
            "${cause?.javaClass?.simpleName}: ${cause?.message ?: "(no message)"}")
        return null
    }

    private fun closeFlow(src: SocketAddress) {
        val flow = flows.remove(src) ?: return
        flow.readerJob.cancel()
        runCatching { flow.out.close() }
        runCatching { flow.input.close() }
    }

    private suspend fun reapLoop() {
        while (!closed) {
            delay(REAP_INTERVAL_MS)
            val now = System.currentTimeMillis()
            flows.entries
                .filter { now - it.value.lastActivityMs > IDLE_MS }
                .forEach { closeFlow(it.key) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() } // unblocks receive()
        runCatching { recvJob?.cancel() }
        runCatching { reaperJob?.cancel() }
        flows.keys.toList().forEach { closeFlow(it) }
        runCatching { children.cancel() }
    }
}
