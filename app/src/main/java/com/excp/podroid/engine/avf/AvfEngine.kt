/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfEngine — VmEngine implementation backed by Android Virtualization Framework.
 *
 * Uses AvfReflect for all android.system.virtualmachine.* calls so the APK
 * compiles against the public SDK. On devices without pKVM the start() call
 * lands in the catch branch and transitions to VmState.Error.
 *
 * Terminal wiring: ConsoleFanout bridges AVF console streams to a filesystem
 * unix socket; libpodroid-bridge connects to that socket and splices PTY ↔
 * socket, same as the QEMU path. The ctrl socket is a dummy path (never bound)
 * — bridge tolerates the connect failure gracefully (no resize on AVF, MVP).
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.system.Os
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.BootStageDetector
import com.excp.podroid.engine.QmpClient
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvfEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("UnusedPrivateMember") private val settingsRepository: SettingsRepository,
) : VmEngine {

    companion object {
        private const val TAG = "AvfEngine"
        // Use "podroid" as the VM name. The smoke-test in AvfDiagnostics used
        // "podroid-avf-smoke" — a different name — so there is no config
        // conflict between the two paths.
        private const val VM_NAME = "podroid"
        // Mirrors QemuEngine's 60s boot-timeout fallback so a guest that never
        // emits "Ready!" (a console quirk, a lost marker, no onStopped/onDied)
        // doesn't strand the engine in Starting forever — EngineHolder.trySwap
        // waits on a terminal state, so a stuck Starting blocks backend switch.
        private const val BOOT_TIMEOUT_MS = 60_000L
        // Upper bound on the best-effort guest flush before stop. Bounded so a
        // hung guest can never block the stop path; the caller proceeds to kill
        // the VM if no SYNCED ack arrives.
        private const val SYNC_TIMEOUT_MS = 8_000L
    }

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    override var terminalSession: TerminalSession? = null
        private set

    // Wall-clock millis of the most recent →Running transition (both the
    // detector onReady path and the boot-timeout fallback set it), null until
    // running and after cleanup(). Drives the Home uptime readout. @Volatile:
    // set on the detector/timeout thread, read by the UI ticker on another.
    @Volatile private var _runningSinceMs: Long? = null
    override val runningSinceMs: Long? get() = _runningSinceMs

    override val backendId: String = "avf"

    /** AVF has no QMP socket; port forwarding is deferred to a future milestone. */
    override val qmpClient: QmpClient? = null

    override var sessionClientDelegate: TerminalSessionClient? = null

    private val proxySessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { sessionClientDelegate?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { sessionClientDelegate?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) { sessionClientDelegate?.onSessionFinished(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { sessionClientDelegate?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { sessionClientDelegate?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { sessionClientDelegate?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { sessionClientDelegate?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { sessionClientDelegate?.onTerminalCursorStateChange(state) }
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) { sessionClientDelegate?.setTerminalShellPid(s, pid) }
        override fun getTerminalCursorStyle(): Int = sessionClientDelegate?.terminalCursorStyle ?: 0
        override fun getTerminalVersionString(): String? = sessionClientDelegate?.terminalVersionString
        override fun logError(tag: String?, msg: String?) = LogProxy.error(tag, TAG, msg)
        override fun logWarn(tag: String?, msg: String?) = LogProxy.warn(tag, TAG, msg)
        override fun logInfo(tag: String?, msg: String?) = LogProxy.info(tag, TAG, msg)
        override fun logDebug(tag: String?, msg: String?) = LogProxy.debug(tag, TAG, msg)
        override fun logVerbose(tag: String?, msg: String?) = LogProxy.verbose(tag, TAG, msg)
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, msg, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // @Volatile: written on the start()/cleanup() thread, read from stop() and
    // addPortForward() on other threads. Every other shared lifecycle field here
    // (control, stopRequested, vmGeneration) is already @Volatile for the same
    // cross-thread visibility reason; vmHandle was the lone unsynchronized gap.
    @Volatile private var vmHandle: Any? = null
    /**
     * Monotonic per-start token. Captured in each VM's callback closures so a
     * late callback from a PREVIOUS VM (callbacks run on ForkJoinPool, so a fast
     * Stop → Start can leave the old VM's events in flight) can't clobber the new
     * VM's state. QemuEngine funnels all state through one exit thread for the
     * same reason; AVF has no single exit thread, so a generation token guards it.
     */
    @Volatile private var vmGeneration: Long = 0
    /**
     * Set by stop() before signalling the framework so a subsequent
     * onError/onStopped/onDied is treated as the expected consequence of a
     * user-initiated stop and never downgrades a clean Stopped to Error.
     */
    @Volatile private var stopRequested = false
    /** Runs cleanup() exactly once per VM lifetime (every terminal path routes through it). */
    private val cleanedUp = AtomicBoolean(true)
    /** Guards the StringBuilder mutated on the fanout pump thread vs cleared in start(). */
    private val consoleLock = Any()
    private var bootTimeoutJob: kotlinx.coroutines.Job? = null
    private var consoleStream: java.io.InputStream? = null
    private var consoleStreamInput: java.io.OutputStream? = null
    private var fanout: ConsoleFanout? = null
    // Console capture — matches QemuEngine's pattern. Without this AVF kernel
    // panics are invisible (issue #29): the boot bytes only went to the bridge
    // socket + boot detector, never to console.log or the consoleText flow
    // surfaced by the diagnostic exporter.
    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024
    private var logOut: java.io.FileOutputStream? = null
    @Volatile private var control: VsockControlChannel? = null
    /**
     * Initial rules captured from start()'s portForwards arg — includes the
     * auto-injected SSH/X11/audio rules that PodroidService adds in-memory
     * (not in DataStore), which EngineHolder's diff loop never sees. Replayed
     * via addPortForward() in the onReady callback. The putIfAbsent dedup in
     * addPortForward handles the race with EngineHolder's parallel dispatch
     * of DataStore rules — whichever path wins owns the forwarder.
     */
    private val initialRules = mutableListOf<com.excp.podroid.data.repository.PortForwardRule>()
    /** vport → forwarder. Written via addPortForward/removePortForward only. */
    private val forwarders = java.util.concurrent.ConcurrentHashMap<Int, VsockPortForwarder>()
    @Volatile private var lastSentRows = -1
    @Volatile private var lastSentCols = -1

    /** Backend-specific diagnostics for the export log (observational only). */
    @Volatile private var lastLifecycleEvent: String = "(no lifecycle callback yet)"
    @Volatile private var launchConfigSummary: String = "(VM not started this session)"
    private var resizeDebounceJob: kotlinx.coroutines.Job? = null

    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/avf-terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/avf-ctrl.sock"

    private val detector = BootStageDetector(_bootStage, _state) {
        // Detector already flipped _state to Running; stamp the uptime origin.
        _runningSinceMs = System.currentTimeMillis()
        Log.i(TAG, "boot Ready! detected — bridge connects via ConsoleFanout")
        // Wipe the boot log from the emulator so the user sees a clean login
        // prompt. AVF only exposes one captured console stream, so unlike
        // QEMU (separate serial.sock for boot) every kernel byte ends up in
        // the PTY scrollback by the time Open Terminal is tapped. ESC c =
        // RIS (full terminal reset); ESC [2J + ESC [H clears + homes the
        // cursor in case the emulator silently ignores RIS.
        val reset = byteArrayOf(0x1b, 'c'.code.toByte(),
            0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(),
            0x1b, '['.code.toByte(), 'H'.code.toByte())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { terminalSession?.emulator?.append(reset, reset.size) }
        }
        bringUpControlChannel()
    }

    /**
     * Bring up the vsock control channel and replay the initial port-forward
     * rules. EngineHolder's diff loop fires on state → Running and dispatches
     * DataStore rules; we replay the initialRules here too because PodroidService
     * auto-injects SSH/X11/audio rules in-memory (never in DataStore) and
     * EngineHolder never sees them. addPortForward's putIfAbsent dedups the
     * parallel path. Idempotent (guarded on a null `control`) so the detector's
     * onReady and the boot-timeout fallback can't both open a second channel.
     */
    @Synchronized
    private fun bringUpControlChannel() {
        if (control != null) return
        val vm = vmHandle ?: return
        val ctl = VsockControlChannel(vm, scope)
        // Assign `control` BEFORE replaying initialRules so each replayed
        // addPortForward sees a non-null channel and its ADD lands (the channel
        // buffers commands internally until the guest agent connects). A
        // DataStore rule that raced in ahead of this still has its host listener
        // bound; addPortForward logs a one-time warning for that narrow window.
        control = ctl
        ctl.open()
        scope.launch {
            for (rule in initialRules) addPortForward(rule)
        }
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        if (_state.value is VmState.Running || _state.value is VmState.Starting) return
        // New VM lifetime: bump the generation so any in-flight callback from a
        // prior VM is ignored, clear the user-stop flag, and arm cleanup() to run
        // once on this run's first terminal transition.
        val generation = ++vmGeneration
        stopRequested = false
        cleanedUp.set(false)
        // Reset console capture from any prior run before we start emitting.
        // Guarded against the prior run's fanout pump still appending (a fast
        // Stop → Start can leave the old vm→bridge pump alive a moment).
        synchronized(consoleLock) { consoleBuilder.clear() }
        _consoleText.value = ""
        initialRules.clear()
        initialRules.addAll(portForwards.filter { it.protocol == "tcp" })
        if (portForwards.any { it.protocol != "tcp" }) {
            Log.w(TAG, "AVF supports TCP only; UDP/non-TCP rules ignored: " +
                "${portForwards.filter { it.protocol != "tcp" }}")
        }
        _state.value = VmState.Starting
        _bootStage.value = "Initializing AVF..."
        // Re-arm the one-shot detector so a Stop → Start cycle's second boot
        // can fire onReady again. Without this the new boot's "Ready!" marker
        // is silently ignored and state stays Starting forever.
        detector.reset()

        try {
            val mgr = AvfReflect.manager(context)
            val vmConfigObj = buildConfig(mgr, config)

            // AOSP canonical pattern: get-or-create, then attempt to update the
            // config on the existing VM. If AVF rejects the new config as
            // incompatible (e.g. vmOutputCaptured changed from a stale record),
            // delete the old VM and create a fresh one.
            val vm: Any = run {
                val existing = AvfReflect.getOrCreate(mgr, VM_NAME, vmConfigObj)
                runCatching { AvfReflect.setConfig(existing, vmConfigObj) }.fold(
                    onSuccess = { existing },
                    onFailure = {
                        Log.w(TAG, "stale VM config detected (${it.message}); deleting + recreating")
                        AvfReflect.delete(mgr, VM_NAME)
                        AvfReflect.create(mgr, VM_NAME, vmConfigObj)
                    },
                )
            }
            vmHandle = vm

            val cb = AvfReflect.newVmCallback(
                onError = { code, msg ->
                    Log.e(TAG, "VM onError ${AvfReasonCodes.errorCode(code)} msg=$msg")
                    lastLifecycleEvent = "onError(${AvfReasonCodes.errorCode(code)}) msg=${msg ?: "no message"}"
                    // Funnel respects terminal states (won't clobber a user Stop)
                    // and the generation token (ignores a prior VM's late error).
                    onVmTerminal(generation, VmState.Error(
                        "AVF onError(${AvfReasonCodes.errorCode(code)}): ${msg ?: "no message"}"
                    ))
                },
                onStopped = { reason ->
                    Log.i(TAG, "VM onStopped ${AvfReasonCodes.stopReason(reason)}")
                    lastLifecycleEvent = "onStopped(${AvfReasonCodes.stopReason(reason)})"
                    // A stop while still Starting means the VM exited during boot;
                    // otherwise it's a clean stop. The funnel maps a user-stop to
                    // Stopped regardless and ignores stale/terminal transitions.
                    val target = if (_state.value is VmState.Starting && !stopRequested) {
                        VmState.Error("VM exited during boot (${AvfReasonCodes.stopReason(reason)})")
                    } else {
                        VmState.Stopped
                    }
                    onVmTerminal(generation, target)
                },
                onDied = { reason ->
                    // onDied fires on backend-level death (virtmgr/crosvm), often
                    // with no preceding onStopped. Previously it only logged, so a
                    // death while Starting stranded the engine in Starting forever
                    // (UI stuck, EngineHolder.trySwap never released). Drive a
                    // terminal transition + cleanup through the same funnel.
                    Log.w(TAG, "VM onDied ${AvfReasonCodes.stopReason(reason)}")
                    lastLifecycleEvent = "onDied(${AvfReasonCodes.stopReason(reason)})"
                    val target = if (stopRequested) {
                        VmState.Stopped
                    } else {
                        VmState.Error("VM died (${AvfReasonCodes.stopReason(reason)})")
                    }
                    onVmTerminal(generation, target)
                },
            )
            AvfReflect.setCallback(vm, java.util.concurrent.ForkJoinPool.commonPool(), cb)

            val inStream = AvfReflect.consoleOutput(vm)
            val outStream = AvfReflect.consoleInput(vm)
            consoleStream = inStream
            consoleStreamInput = outStream

            // Open console.log fresh (mirrors QemuEngine's monitorBootSerial).
            // The diagnostic exporter cats this path; on AVF it was previously
            // always empty, masking kernel panics like issue #29's reason=5
            // reboot on Pixel 8a. Tee inside ConsoleFanout's vm→bridge pump.
            val logFile = File(context.filesDir, "console.log")
            runCatching { logFile.delete() }
            val log = runCatching { java.io.FileOutputStream(logFile, false) }
                .onFailure { Log.w(TAG, "console.log open failed (continuing without capture)", it) }
                .getOrNull()
            logOut = log

            // Fan out: VM ↔ filesystem socket. The bridge subprocess connects to
            // that socket and splices PTY ↔ socket. BootStageDetector tees the
            // VM output to drive boot-stage + state transitions; onVmBytes tees
            // the boot-phase bytes (Starting only — see the privacy note there)
            // to console.log + the consoleText flow.
            val fo = ConsoleFanout(
                consoleOutput = inStream,
                consoleInput = outStream,
                socketPath = terminalSockPath,
                detector = detector,
                scope = scope,
                onVmBytes = { buf, n ->
                    // PRIVACY: on AVF the console rides hvc0 — the SAME device as the
                    // interactive getty/shell — so persist to console.log ONLY while
                    // booting. Once the VM is Running, hvc0 carries everything the user
                    // types and sees (login, shell I/O, file contents); writing that to
                    // console.log would leak the whole session into the exportable
                    // diagnostic log. Boot output (incl. boot-time kernel panics like
                    // issue #29) is still captured; a later death's reason still lands
                    // via the onStopped/onDied callbacks.
                    if (_state.value is VmState.Starting) {
                        runCatching {
                            log?.write(buf, 0, n)
                            log?.flush()
                        }
                        // UTF-8 decode best-effort (split multi-byte sequences may
                        // surface as U+FFFD here — acceptable for the diagnostic
                        // tail; the raw bytes hit disk faithfully above).
                        // Guard the StringBuilder: this runs on the fanout pump thread
                        // while start() may clear it on another (fast Stop → Start).
                        val snapshot = synchronized(consoleLock) {
                            consoleBuilder.append(String(buf, 0, n, Charsets.UTF_8))
                            if (consoleBuilder.length > maxConsoleSize) {
                                consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                            }
                            consoleBuilder.toString()
                        }
                        _consoleText.value = snapshot
                    }
                },
            )
            fanout = fo
            fo.start()

            AvfReflect.run(vm)
            val status = runCatching { AvfReflect.getStatus(vm) }.getOrDefault(-1)
            Log.i(TAG, "vm.run() returned — VM booting (status=$status)")
            spawnBridge()

            // Boot-timeout fallback (mirrors QemuEngine): the engine leaves
            // Starting only via the detector's "Ready!" or a VM callback. If a
            // console quirk or a lost marker keeps both from firing, promote a
            // still-Starting VM of THIS generation to Running and bring up the
            // control channel (idempotent) so EngineHolder.trySwap can release
            // and the bridge is wired. The generation/cleanedUp guard prevents
            // promoting a VM that already stopped in the delay window.
            bootTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(BOOT_TIMEOUT_MS)
                if (generation == vmGeneration && !cleanedUp.get() &&
                    _state.value is VmState.Starting) {
                    Log.w(TAG, "AVF boot timeout — forcing Running state")
                    _bootStage.value = "Ready"
                    _runningSinceMs = System.currentTimeMillis()
                    _state.value = VmState.Running
                    bringUpControlChannel()
                }
            }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.e(TAG, "AVF start failed", cause)
            _state.value = VmState.Error("AVF rejected: ${cause.javaClass.simpleName}: ${cause.message}")
            cleanup()
        }
    }

    /**
     * Spawn libpodroid-bridge.so unconditionally at VM start, NOT on demand.
     * The fanout's accept() loops until the bridge connects; without an early
     * bridge spawn, the VM's console output is never drained and BootStageDetector
     * never sees "Ready!". This matches QemuEngine's autoStartBridge pattern but
     * with inverse timing (QEMU spawns AFTER ready; AVF spawns BEFORE ready).
     */
    private fun spawnBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (terminalSession != null) return@post
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) {
                Log.e(TAG, "bridge missing at ${bridgeExe.absolutePath}")
                return@post
            }
            val sess = com.excp.podroid.engine.ResizeNotifyingSession(
                shellPath = bridgeExe.absolutePath,
                cwd = context.filesDir.absolutePath,
                args = arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
                env = null,
                transcriptRows = 2000,
                client = proxySessionClient,
                onResize = { rows, cols -> sendResizeDebounced(rows, cols) },
            )
            sess.updateSize(80, 24, 0, 0)
            terminalSession = sess
            Log.d(TAG, "AVF bridge auto-spawned (resize-notifying)")
        }
    }

    override fun stop() {
        val vm = vmHandle
        if (vm == null) {
            // Nothing running (or already torn down). Make sure state is terminal
            // so EngineHolder.trySwap can release, then run the idempotent cleanup.
            onVmTerminal(vmGeneration, VmState.Stopped)
            return
        }
        // Mark the stop as user-initiated so a resulting onStopped/onDied/onError
        // is mapped to Stopped (never downgraded to Error) by the funnel.
        stopRequested = true
        val generation = vmGeneration
        scope.launch {
            // Best-effort guest flush so unwritten ext4 data hits storage before
            // we kill the VM. Bounded so a hung guest can't block the stop.
            runCatching { control?.syncAndWait(SYNC_TIMEOUT_MS) }
            if (generation != vmGeneration || cleanedUp.get()) return@launch
            // Surface (don't swallow) a framework stop() failure: if rejected the
            // VM is still alive, and we must NOT null the handle.
            val requested = runCatching { AvfReflect.stop(vm) }
                .onFailure { Log.w(TAG, "AVF stop() request failed; VM may still be running", it) }
                .isSuccess
            // Drive the final Stopped + cleanup from onStopped/onDied. If no
            // terminal callback arrives (or stop() was rejected), a bounded
            // watchdog forces the transition so we never strand in Running.
            kotlinx.coroutines.delay(if (requested) 5_000L else 1_000L)
            if (generation == vmGeneration && !cleanedUp.get()) {
                Log.w(TAG, "AVF stop: no terminal callback within timeout — forcing Stopped")
                onVmTerminal(generation, VmState.Stopped)
            }
        }
    }

    override suspend fun addPortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val vm = vmHandle ?: return
        if (rule.protocol != "tcp") {
            Log.w(TAG, "addPortForward($rule) — AVF supports TCP only; rule ignored")
            return
        }
        try {
            // Convention: vsock port == hostPort. The guest agent listens on
            // vsock:hostPort and splices to tcp 127.0.0.1:guestPort. Using
            // hostPort (which is unique per Android-side rule) instead of
            // guestPort avoids vsock-port collisions when two rules target
            // the same service inside the VM (e.g. 8080→80 + 9090→80).
            val fw = VsockPortForwarder(
                hostPort = rule.hostPort,
                guestVsockPort = rule.hostPort,
                vm = vm,
                scope = scope,
            )
            // putIfAbsent races safely with another concurrent addPortForward
            // for the same hostPort — only one forwarder wins; the loser closes
            // immediately so we don't leak a bound ServerSocket.
            val existing = forwarders.putIfAbsent(rule.hostPort, fw)
            if (existing != null) {
                Log.d(TAG, "addPortForward($rule) — forwarder already exists for ${rule.hostPort}")
                return
            }
            // Re-check Running state before .start() so a concurrent cleanup()
            // doesn't orphan us. If state has slipped, drop the freshly-mapped
            // forwarder and don't bind the ServerSocket.
            if (_state.value !is VmState.Running) {
                forwarders.remove(rule.hostPort)
                return
            }
            fw.start()
            val ctl = control
            if (ctl != null) {
                ctl.addForward(rule.hostPort, "127.0.0.1", rule.guestPort)
            } else {
                // EngineHolder's diff loop can call addPortForward the instant
                // state flips to Running, a hair before bringUpControlChannel
                // sets `control`. The host listener is already bound; the guest
                // ADD is what's at risk here. Surface it rather than dropping
                // silently. The auto-injected rules are replayed by
                // bringUpControlChannel (which sets `control` before the replay,
                // so their ADDs land); a DataStore rule that lands in this narrow
                // window is re-dispatched by EngineHolder on the next sync.
                Log.w(TAG, "addPortForward(${rule.hostPort}): control channel not up yet; " +
                    "guest ADD skipped (host listener is bound)")
            }
            Log.i(TAG, "live forward up: 0.0.0.0:${rule.hostPort} → vsock:${rule.hostPort} → 127.0.0.1:${rule.guestPort}")
        } catch (e: Throwable) {
            // If we got past putIfAbsent before the throw, remove ourselves so
            // cleanup() doesn't try to close a half-built forwarder.
            forwarders.remove(rule.hostPort)
            Log.w(TAG, "addPortForward($rule) failed", e)
        }
    }

    override suspend fun removePortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        if (rule.protocol != "tcp") return
        val fw = forwarders.remove(rule.hostPort) ?: return
        runCatching { fw.close() }
        runCatching { control?.removeForward(rule.hostPort) }
        Log.i(TAG, "live forward down: 0.0.0.0:${rule.hostPort}")
    }

    /**
     * Coalesce SIGWINCH bursts (keyboard slide produces ~25 layout events) into
     * one RESIZE message. 80 ms matches the bridge's RESIZE_DEBOUNCE_MS constant
     * so the AVF behavior tracks the QEMU bridge's user-visible cadence.
     */
    private fun sendResizeDebounced(rows: Int, cols: Int) {
        if (rows == lastSentRows && cols == lastSentCols) return
        resizeDebounceJob?.cancel()
        resizeDebounceJob = scope.launch {
            kotlinx.coroutines.delay(80)
            val ctl = control ?: return@launch
            // sendResize queues if the connect retry hasn't completed yet —
            // no need to gate on isOpen.
            ctl.sendResize(rows, cols)
            lastSentRows = rows
            lastSentCols = cols
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client
        terminalSession?.let {
            Log.d(TAG, "Returning auto-spawned AVF terminal session")
            return it
        }
        // start() hasn't reached spawnBridge yet — spawn synchronously as fallback.
        Log.w(TAG, "createTerminalSession called before bridge auto-spawn; spawning now")
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }
        val sess = com.excp.podroid.engine.ResizeNotifyingSession(
            shellPath = bridgeExe.absolutePath,
            cwd = context.filesDir.absolutePath,
            args = arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            env = null,
            transcriptRows = 2000,
            client = proxySessionClient,
            onResize = { rows, cols -> sendResizeDebounced(rows, cols) },
        )
        sess.updateSize(80, 24, 0, 0)
        terminalSession = sess
        return sess
    }

    /**
     * Single synchronized funnel for the VM lifecycle callbacks (onError /
     * onStopped / onDied), which fire on ForkJoinPool threads and can interleave.
     * Drops events from a stale generation, never overwrites a terminal state
     * (Stopped/Error/Idle), suppresses an Error downgrade of a user-initiated
     * Stopped, and runs cleanup() once on the first terminal transition. A
     * successful start/run never calls this (only the detector flips to Running).
     */
    @Synchronized
    private fun onVmTerminal(generation: Long, newState: VmState) {
        if (generation != vmGeneration) {
            Log.d(TAG, "ignoring stale VM callback (gen $generation != current $vmGeneration)")
            return
        }
        val current = _state.value
        if (current is VmState.Stopped || current is VmState.Idle || current is VmState.Error) {
            // Already terminal; don't let a trailing onError clobber a clean stop.
            return
        }
        // A user-initiated stop already decided the outcome is Stopped; a late
        // framework Error/exit for that same teardown must not downgrade it.
        _state.value = if (stopRequested) VmState.Stopped else newState
        cleanup()
    }

    private fun cleanup() {
        if (cleanedUp.getAndSet(true)) return
        _runningSinceMs = null
        bootTimeoutJob?.cancel()
        bootTimeoutJob = null
        // Tear down forwarders + control before draining the fanout so the
        // VM doesn't see late vsock connect attempts after onStopped.
        forwarders.values.forEach { runCatching { it.close() } }
        forwarders.clear()
        lastSentRows = -1
        lastSentCols = -1
        resizeDebounceJob?.cancel()
        resizeDebounceJob = null
        runCatching { control?.close() }
        control = null
        // Close fanout next — drains its coroutines AND closes the console
        // streams (it is their single owner). We hold the same references only
        // to hand them to the fanout; closing them here too would double-close
        // the ParcelFileDescriptor-backed streams from another thread (FD-reuse
        // hazard). Only if the fanout was never constructed (an early start()
        // failure between obtaining the streams and building the fanout) do we
        // close them directly so they don't leak.
        val fo = fanout
        if (fo != null) {
            runCatching { fo.close() }
        } else {
            runCatching { consoleStreamInput?.close() }
            runCatching { consoleStream?.close() }
        }
        fanout = null
        consoleStreamInput = null
        consoleStream = null
        // Flush + close console.log; the tail stays on disk for the next
        // diagnostic export. consoleText flow keeps its last value (UI reads
        // it cached); start() clears both on the next run.
        runCatching {
            logOut?.flush()
            logOut?.close()
        }
        logOut = null
        // Tear down the old terminal session so the next start()'s spawnBridge
        // doesn't short-circuit on the stale reference and leave the fanout's
        // accept() blocked forever (visible as "stuck at Initializing AVF...").
        runCatching { terminalSession?.finishIfRunning() }
        terminalSession = null
        vmHandle = null
    }

    /**
     * Creates (or resizes) the persistent storage disk. Mirrors QemuEngine's
     * helper exactly so a VM can boot under either backend with the same
     * disk identity. The file is sparse — the actual disk allocation grows
     * as the guest writes blocks. Alpine's init formats it ext4 on first
     * boot via the standard initramfs path.
     */
    private fun ensureStorageImage(storageSizeGb: Int): File {
        val storageFile = File(context.filesDir, "storage.img")
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L
        if (storageFile.exists() && storageFile.length() == desiredBytes) return storageFile
        if (storageFile.exists()) {
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }
        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
        return storageFile
    }

    /**
     * crosvm requires the raw ARM64 Image (magic `ARM\x64` at offset 0x38), not
     * the gzip-compressed `vmlinuz` that QEMU happily auto-decompresses. We
     * gunzip on-demand to a sibling .raw file and feed THAT to AVF.
     *
     * Skips decompression if the .raw file's mtime is newer than the source.
     * Returns the decompressed file (or the source if already raw).
     */
    private fun ensureRawKernel(source: File): File {
        val magic = ByteArray(4)
        source.inputStream().use { it.read(magic) }
        val isGzip = magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        if (!isGzip) return source

        val raw = File(source.parentFile, "${source.name}.raw")
        if (raw.exists() && raw.lastModified() >= source.lastModified()) return raw

        Log.d(TAG, "Decompressing ${source.name} → ${raw.name}")
        java.util.zip.GZIPInputStream(source.inputStream().buffered()).use { gz ->
            raw.outputStream().buffered().use { out -> gz.copyTo(out) }
        }
        raw.setLastModified(System.currentTimeMillis())
        return raw
    }

    override fun diagnosticsReport(): String = buildString {
        appendLine("backend launch config: $launchConfigSummary")
        appendLine("last VM lifecycle callback: $lastLifecycleEvent")
    }

    private fun buildConfig(mgr: Any, config: VmConfig): Any {
        val kernelSrc = File(context.filesDir, "vmlinuz-virt").also {
            require(it.exists()) { "kernel missing at ${it.absolutePath}" }
        }
        val kernel = ensureRawKernel(kernelSrc)
        val initrd = File(context.filesDir, "initrd.img").also {
            require(it.exists()) { "initrd missing at ${it.absolutePath}" }
        }
        val storage = ensureStorageImage(config.storageSizeGb)
        val squashfs = File(context.filesDir, "alpine-rootfs.squashfs").also {
            require(it.exists()) { "rootfs missing at ${it.absolutePath}" }
        }

        val cb = AvfReflect.newCustomBuilder()
        AvfReflect.setName(cb, VM_NAME)
        AvfReflect.setKernelPath(cb, kernel.absolutePath)
        AvfReflect.setInitrdPath(cb, initrd.absolutePath)
        // Console wiring: hvc0 (virtio-console), NOT ttyS0 (PL011 UART).
        // crosvm's PL011 is rate-limited to ~14 KB/s — fine for boot logs,
        // catastrophic for TUI redraws (a btop frame is 8–16 KB, so each
        // refresh takes ~700 ms–1 s on ttyS0). virtio-console runs at host
        // memory speed. setConsoleInputDevice(hvc0) tells AVF to wire its
        // captured input/output streams to hvc0; `console=hvc0` in the
        // kernel cmdline routes kernel logs the same way; the guest's
        // /etc/inittab spawns the getty on the device named by the
        // `podroid.tty=` marker.
        //
        // `podroid.backend=avf` is a stable backend identifier — used by
        // guest OpenRC scripts (podroid-network, podroid-vsock) to pick
        // AVF-specific behaviour without coupling to the tty choice.
        //
        // `podroid.epoch=...` seeds the wall clock — AVF/crosvm doesn't
        // wire an RTC the way QEMU TCG does, so without this the guest
        // boots at 1970-01-01 and TLS fails on every cert.
        val epoch = System.currentTimeMillis() / 1000
        val resolvedCmdline = ("console=hvc0 root=/dev/ram0 mitigations=off elevator=mq-deadline " +
            "podroid.tty=hvc0 podroid.backend=avf podroid.epoch=$epoch " +
            "podroid.x11.dpi=${config.x11Dpi} " +
            config.kernelExtraCmdline).trim()
        AvfReflect.addParams(cb, resolvedCmdline)
        if (config.verboseLogging) {
            Log.i(TAG, "verbose: resolved cmdline = $resolvedCmdline")
            Log.i(TAG, "verbose: ramMb=${config.ramMb} cpus=${config.cpus} " +
                "storageAccess=${config.storageAccessEnabled}")
        }
        AvfReflect.addDisk(cb, storage.absolutePath, writable = true)
        AvfReflect.addDisk(cb, squashfs.absolutePath, writable = false)
        var shareSummary = if (config.storageAccessEnabled) "enabled" else "off"
        if (config.storageAccessEnabled) {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).absolutePath
            // External-storage paths (/storage/emulated/...) live outside the
            // app's SELinux domain. AOSP TerminalApp's ConfigJson.SharedPathJson
            // handles this by passing appDomain=false so crosvm spins up as a
            // child of virtmgr (system domain) instead of inheriting our
            // untrusted_app domain. Without that, crosvm gets EACCES on the
            // first read and the VM dies with reason=4 at start.
            //
            // On AVF revisions that don't ship the 10-param ctor with
            // appDomain, AvfReflect refuses the share (returns false) and we
            // boot without it — better than crashing the VM. The toggle in
            // Settings just becomes a no-op on those devices.
            val ok = runCatching {
                AvfReflect.addSharedPath(
                    customBuilder = cb,
                    sharedPath = downloads,
                    tag = "downloads",
                    hostUid = android.os.Process.myUid(),
                    hostGid = Os.getgid(),
                    guestUid = 1000,
                    guestGid = 100,
                    mask = 0x0007,
                    socket = "downloads",
                    socketPath = "",
                    appDomain = false,
                )
            }.getOrElse { e ->
                Log.w(TAG, "addSharedPath threw (continuing without share)", e); false
            }
            if (ok) {
                shareSummary = "added"
                Log.i(TAG, "downloads share added: $downloads")
            } else {
                shareSummary = "no-op (unsupported on this AVF revision)"
                Log.i(TAG, "downloads share NOT added — Settings toggle is a no-op on this AVF revision")
            }
        }
        AvfReflect.setNetworkSupported(cb, true)
        val customCfg = AvfReflect.build(cb)

        val vb = AvfReflect.newVmConfigBuilder(context)
        val protectedStr = when (val choice = AvfReflect.applyProtectedVm(mgr, vb)) {
            is AvfCapabilities.ProtectedVmChoice.Unsupported ->
                throw UnsupportedOperationException(choice.reason)
            is AvfCapabilities.ProtectedVmChoice.NonProtected -> {
                Log.i(TAG, "protectedVm=false (device supports non-protected VMs)")
                "false (non-protected supported)"
            }
            is AvfCapabilities.ProtectedVmChoice.Unknown -> {
                Log.w(TAG, "getCapabilities() unavailable; attempted protectedVm=false")
                "false (capabilities unknown)"
            }
        }
        AvfReflect.setMemoryBytes(vb, config.ramMb.toLong() * 1024 * 1024)
        AvfReflect.setNumCpus(vb, config.cpus)
        AvfReflect.setDebugLevel(vb, AvfReflect.DEBUG_LEVEL_FULL)
        AvfReflect.setConsoleInputDevice(vb, "hvc0")         // virtio-console (line-rate), NOT PL011
        AvfReflect.setConnectVmConsole(vb, false)            // false: avoid inheriting Activity FDs (SurfaceFlinger sync fences → SELinux denial)
        AvfReflect.setVmOutputCaptured(vb, true)
        AvfReflect.setVmConsoleInputSupported(vb, true)
        AvfReflect.setCustomImageConfig(vb, customCfg)
        launchConfigSummary = "vCPUs=${config.cpus}, memory=${config.ramMb}MB, console=hvc0, " +
            "protectedVm=$protectedStr, downloadsShare=$shareSummary, verboseLogging=${config.verboseLogging}"
        return AvfReflect.build(vb)
    }
}
