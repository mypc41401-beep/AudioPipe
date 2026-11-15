package com.asdfg.soundmaster.adb

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.ContinuationInterceptor

internal interface AdbConnection : Closeable {
    val isConnected: Boolean
    fun connect()
    fun shell(command: String): String
}
internal data class ReconnectHooks(
    val discover: suspend () -> Int,
    val create: (Int, AdbIdentity) -> AdbConnection,
    val identity: () -> AdbIdentity,
    val scope: CoroutineScope
)

/** Direct local ADB. Pairing identity and connection-port hints have separate lifetimes. */
class ShellExecutor internal constructor(private val context: Context, private val hooks: ReconnectHooks? = null) {
    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }
    data class ConnectionState(val message: String, val connected: Boolean = false,
        val busy: Boolean = false, val recovering: Boolean = false)

    private val prefs = context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
    private var key: AdbKey? = null
    private fun realIdentity(): AdbKey = key ?: AdbKey(PreferenceAdbKeyStore(prefs),
        "AudioPipe@${Build.MODEL}").also { key = it }
    private fun identity(): AdbIdentity = hooks?.identity?.invoke() ?: realIdentity()
    private val client = AtomicReference<AdbConnection?>()
    private val pairing = AtomicReference<AdbPairingClient?>()
    private val operations = Mutex()
    private val scope = hooks?.scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutableState = MutableStateFlow(ConnectionState("Disconnected. Tap Setup / Pair to authorize AudioPipe."))
    val state: StateFlow<ConnectionState> = mutableState
    val isSetUp: Boolean get() = prefs.getBoolean("adb_paired", false) || prefs.getBoolean("adb_imported", false)
    val isConnected: Boolean get() = state.value.connected && client.get()?.isConnected == true
    fun getCachedPort(): Int = prefs.getInt("last_port", -1)

    private val ownerLock = Any()
    private val owners = mutableSetOf<Any>()
    private var networkObserver: AdbNetworkObserver? = null
    private var serviceWatch: Closeable? = null
    @Volatile private var recovery: AdbRecoveryLoop? = null
    private val hint = AtomicInteger(-1)
    private val automaticAttempt = AtomicBoolean(false)
    @Volatile private var wifiAvailable = true
    @Volatile private var automaticPaused = false

    /** Only a visible Activity or the existing routing FGS owns background recovery. */
    fun setAutoReconnectOwner(owner: Any, active: Boolean): Unit = synchronized(ownerLock) {
        if (active) {
            owners.add(owner)
            automaticPaused = false
            startMonitoringLocked()
            recovery?.wake()
        } else {
            owners.remove(owner)
            if (owners.isEmpty()) stopMonitoringLocked()
        }
    }
    private fun startMonitoringLocked() {
        if (recovery != null || owners.isEmpty() || automaticPaused || !isSetUp) return
        val loop = AdbRecoveryLoop(scope, { wifiAvailable }, {
            synchronized(ownerLock) { startServiceWatchLocked() }
            recoverConnection(automatic = true)
        }, ::waiting)
        recovery = loop
        if (hooks == null) {
            networkObserver = AdbNetworkObserver(context) { ready ->
                synchronized(ownerLock) { if (recovery === loop) wifiChanged(ready) }
            }
            startServiceWatchLocked()
        }
        loop.start()
    }
    private fun startServiceWatchLocked() {
        if (serviceWatch != null || !wifiAvailable || recovery == null) return
        serviceWatch = runCatching {
            AdbMdns.watch(context, AdbMdns.TLS_CONNECT, ::connectionPortFound,
                { port -> hint.compareAndSet(port, -1); recovery?.wake() }, { /* bounded discovery reports details */ })
        }.getOrNull()
    }
    private fun stopMonitoringLocked() {
        recovery?.stop(); recovery = null
        serviceWatch?.close(); serviceWatch = null
        networkObserver?.close(); networkObserver = null
        if (automaticAttempt.get()) closeClient()
    }
    internal fun connectionPortFound(port: Int) {
        if (port in 1..65535 && hint.getAndSet(port) != port) recovery?.wake()
    }
    internal fun wifiChanged(available: Boolean) = synchronized(ownerLock) {
        wifiAvailable = available
        if (!available) {
            hint.set(-1)
            closeClient()
            serviceWatch?.close(); serviceWatch = null
            mutableState.value = ConnectionState("Paired identity saved. Waiting for Wi-Fi; then enable Wireless debugging. No new pairing code is needed.")
        } else startServiceWatchLocked()
        recovery?.wake()
    }
    private fun waiting(error: Throwable?, retryMs: Long?) {
        val message = when {
            !wifiAvailable -> "Paired identity saved. Waiting for Wi-Fi; then enable Wireless debugging."
            retryMs != null -> "${describeError(error!!)} Retrying discovery in ${retryMs / 1000}s; pairing is saved."
            error is SavedAdbIdentityException -> describeError(error)
            else -> "${error?.let(::describeError).orEmpty()} Automatic retries paused. Waiting for a Wireless debugging service/network change. You can enter the current connection port in Setup; pairing is saved."
        }
        mutableState.value = ConnectionState(message, recovering = retryMs != null)
    }
    private fun resumeMonitoring() = synchronized(ownerLock) {
        automaticPaused = false
        startMonitoringLocked()
        recovery?.wake()
    }
    private fun pauseAutomatic() = synchronized(ownerLock) {
        automaticPaused = true
        stopMonitoringLocked()
    }

    suspend fun pair(port: Int, code: String): Result<Unit> {
        pauseAutomatic()
        return operation("Pairing with this phone…") {
            require(port in 1..65535) { "Enter the pairing port (1–65535)" }
            require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code" }
            closeClient()
            val attempt = AdbPairingClient(port, code, realIdentity())
            pairing.set(attempt)
            try {
                attempt.pair()
                prefs.edit().putBoolean("adb_paired", true).remove("last_port").apply()
                mutableState.value = ConnectionState("Paired. Connect using the port on the main Wireless debugging page, or automatic discovery.")
            } finally { attempt.close(); pairing.compareAndSet(attempt, null) }
        }
    }
    suspend fun discoverPairingPort(): Result<Int> = operation("Searching for the pairing port…") {
        AdbMdns.discover(context, AdbMdns.TLS_PAIRING).also {
            mutableState.value = ConnectionState("Pairing port found. Enter the current six-digit code.")
        }
    }
    suspend fun connect(port: Int = getCachedPort()): Boolean = connectResult(port).isSuccess
    suspend fun connectResult(port: Int): Result<Unit> {
        pauseAutomatic()
        val result = operation("Authenticating ADB connection…") { connectLocked(port) }
        resumeMonitoring()
        return result
    }
    suspend fun autoConnect(): Result<Unit> {
        pauseAutomatic()
        val result = recoverConnection(false)
        resumeMonitoring()
        return result
    }
    private suspend fun recoverConnection(automatic: Boolean): Result<Unit> =
        operation("Checking this phone's ADB connection…", automatic = automatic) { recoverLocked(automatic) }

    private suspend fun recoverLocked(automatic: Boolean) {
        val current = client.get()
        if (current?.isConnected == true) {
            try {
                validateShell(current)
                currentCoroutineContext().ensureActive()
                verified()
                return
            } catch (e: SavedAdbIdentityException) { throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); closeClient() }
        }
        if (!isSetUp) throw IOException("AudioPipe is not paired. Tap Setup / Pair. Shizuku Running does not authorize this app.")
        val tried = mutableSetOf<Int>()
        for (port in listOf(hint.get(), getCachedPort())) {
            if (port !in 1..65535 || !tried.add(port)) continue
            try { connectLocked(port); return }
            catch (e: SavedAdbIdentityException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                closeClient()
                if (e is ConnectException) {
                    hint.compareAndSet(port, -1)
                    if (getCachedPort() == port) prefs.edit().remove("last_port").apply()
                }
            }
        }
        mutableState.value = ConnectionState("Paired identity saved. Discovering the current Wireless debugging connection port…",
            busy = true, recovering = automatic)
        val port = hooks?.discover?.invoke() ?: AdbMdns.discover(context, AdbMdns.TLS_CONNECT)
        currentCoroutineContext().ensureActive()
        connectLocked(port)
    }
    private suspend fun connectLocked(port: Int) {
        require(port in 1..65535) { "Enter the connection port from the main Wireless debugging page" }
        currentCoroutineContext().ensureActive()
        closeClient()
        val identity = identity()
        val attempt = hooks?.create?.invoke(port, identity) ?: run {
            val delegate = AdbClient("127.0.0.1", port, identity)
            object : AdbConnection {
                override val isConnected: Boolean get() = delegate.isConnected
                override fun connect() = delegate.connect()
                override fun shell(command: String) = delegate.shellCommand(command)
                override fun close() = delegate.close()
            }
        }
        client.set(attempt)
        try {
            attempt.connect()
            validateShell(attempt)
            currentCoroutineContext().ensureActive()
            prefs.edit().putInt("last_port", port).apply()
            verified()
        } catch (e: Exception) { attempt.close(); client.compareAndSet(attempt, null); throw e }
    }
    private fun verified() { mutableState.value = ConnectionState("Connected — ADB shell verified", true) }
    private fun validateShell(attempt: AdbConnection) {
        val uid = attempt.shell("id -u").trim()
        if (uid != "2000" && uid != "0") throw IOException("ADB connected, but a shell identity could not be verified")
    }
    suspend fun importKey(bytes: ByteArray): Result<Unit> {
        pauseAutomatic()
        return operation("Importing an existing ADB key…") {
            require(bytes.size in 1..65536) { "Choose an ADB private key smaller than 64 KB" }
            closeClient()
            realIdentity().importPrivateKey(bytes)
            key = null
            prefs.edit().putBoolean("adb_imported", true).remove("adb_paired").remove("last_port").apply()
            mutableState.value = ConnectionState("Key imported. Connect using the current port; the key must already be authorized.")
        }
    }
    fun command(command: String, listener: CommandResultListener) {
        scope.launch {
            val result = commandSync(command)
            withContext(Dispatchers.Main) {
                result.fold({ listener.onCommandResult(it, true) }, { listener.onCommandError(describeError(it)) })
            }
        }
    }
    suspend fun commandSync(command: String): Result<String> {
        val result = operation("", showBusy = false) {
            // A Socket's historical connected flag cannot prove that adbd survived a Wi-Fi toggle.
            recoverLocked(false)
            try { client.get()!!.shell(command) }
            catch (e: Exception) {
                closeClient()
                throw IOException("ADB disconnected during the command. Its completion is unknown; it was not replayed. Wait for reconnect before retrying.", e)
            }
        }
        if (result.isFailure && isSetUp) { resumeMonitoring(); recovery?.wake() }
        return result
    }
    private suspend fun <T> operation(progress: String, showBusy: Boolean = true, automatic: Boolean = false,
        block: suspend () -> T): Result<T> = withContext((hooks?.scope?.coroutineContext?.get(ContinuationInterceptor) as? CoroutineDispatcher) ?: Dispatchers.IO) {
        operations.withLock {
            if (automatic) automaticAttempt.set(true)
            // A healthy background probe must not flash red or temporarily block Start.
            if (showBusy && (!automatic || !isConnected))
                mutableState.value = ConnectionState(progress, busy = true, recovering = automatic)
            try { Result.success(block()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (client.get()?.isConnected != true) closeClient()
                mutableState.value = ConnectionState(describeError(e))
                Result.failure(e)
            } catch (e: LinkageError) {
                closeClient()
                Result.failure<T>(IOException("ADB crypto library could not load on this CPU/Android version.", e)).also {
                    mutableState.value = ConnectionState(it.exceptionOrNull()!!.message!!)
                }
            } finally { if (automatic) automaticAttempt.set(false) }
        }
    }
    private fun closeClient() { client.getAndSet(null)?.close() }
    fun cancelCurrentOperation() {
        pauseAutomatic()
        pairing.getAndSet(null)?.close()
        closeClient()
        mutableState.value = ConnectionState(if (isSetUp)
            "Cancelled. Pairing identity is saved; use Connect / verify or reopen AudioPipe to retry."
            else "Cancelled. AudioPipe is not paired; open Setup / Pair to authorize it.")
    }
    fun disconnect() { cancelCurrentOperation() }

    companion object {
        @Volatile private var instance: ShellExecutor? = null
        fun getInstance(context: Context): ShellExecutor = instance ?: synchronized(this) {
            instance ?: ShellExecutor(context.applicationContext).also { instance = it }
        }
        fun describeError(error: Throwable): String = when (error) {
            is SavedAdbIdentityException -> "Saved ADB identity could not be unlocked. It has NOT been replaced. Unlock/restart the phone and retry; do not clear app data."
            is SocketTimeoutException -> "ADB timed out. Keep Wi-Fi and Wireless debugging on; the saved pairing is retained."
            is ConnectException -> "ADB port is unavailable. Checking the current Wireless debugging service; no new pairing code is needed."
            is SSLException -> "The saved ADB identity was not accepted on this connection. Wait for Wireless debugging to finish restarting, then retry. Pair again only if AudioPipe's authorization was actually removed or remains rejected."
            else -> error.message?.take(300) ?: "ADB connection failed. Keep Wireless debugging on or enter its current connection port."
        }
    }
}
