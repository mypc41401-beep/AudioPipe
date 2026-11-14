package com.asdfg.soundmaster.adb

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** Direct local ADB; it deliberately does not consume Shizuku's private key or binder. */
class ShellExecutor(private val context: Context) {
    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }
    data class ConnectionState(val message: String, val connected: Boolean = false, val busy: Boolean = false)

    private val prefs = context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
    private var key: AdbKey? = null
    private fun identity(): AdbKey = key ?: AdbKey(
        PreferenceAdbKeyStore(prefs), "AudioPipe@${Build.MODEL}"
    ).also { key = it }

    private val client = AtomicReference<AdbClient?>()
    private val pairing = AtomicReference<AdbPairingClient?>()
    private val operations = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutableState = MutableStateFlow(ConnectionState("Disconnected. Tap Setup / Pair to authorize AudioPipe."))
    val state: StateFlow<ConnectionState> = mutableState
    val isSetUp: Boolean
        get() = prefs.getBoolean("adb_paired", false) || prefs.getBoolean("adb_imported", false)
    val isConnected: Boolean get() = client.get()?.isConnected == true
    fun getCachedPort(): Int = prefs.getInt("last_port", -1)

    suspend fun pair(port: Int, code: String): Result<Unit> = operation("Pairing with this phone…") {
        require(port in 1..65535) { "Enter the pairing port (1–65535)" }
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code" }
        closeClient()
        val attempt = AdbPairingClient(port, code, identity())
        pairing.set(attempt)
        try {
            attempt.pair()
            // Never save success merely because a code was entered or TLS connected.
            prefs.edit().putBoolean("adb_paired", true).remove("last_port").apply()
            mutableState.value = ConnectionState("Paired. Now connect using the port on the main Wireless debugging page.")
        } finally {
            attempt.close()
            pairing.compareAndSet(attempt, null)
        }
    }

    suspend fun discoverPairingPort(): Result<Int> = operation("Searching for the pairing port…") {
        AdbMdns.discover(context, AdbMdns.TLS_PAIRING).also {
            mutableState.value = ConnectionState("Pairing port found. Enter the current six-digit code.", isConnected)
        }
    }

    suspend fun connect(port: Int = getCachedPort()): Boolean = connectResult(port).isSuccess

    suspend fun connectResult(port: Int): Result<Unit> = operation("Authenticating ADB connection…") {
        connectLocked(port)
    }

    suspend fun autoConnect(): Result<Unit> = operation("Looking for this phone's ADB connection…") {
        if (isConnected) {
            // A shell probe, rather than a stale boolean, validates the live connection.
            validateShell(client.get()!!)
            mutableState.value = ConnectionState("Connected — ADB shell verified", true)
            return@operation
        }
        if (!isSetUp) throw IOException("AudioPipe is not paired. Tap Setup / Pair. Shizuku Running does not authorize this app.")
        val cached = getCachedPort()
        if (cached in 1..65535) {
            try {
                connectLocked(cached)
                return@operation
            } catch (_: Exception) {
                closeClient()
            }
        }
        mutableState.value = ConnectionState("Discovering the current connection port…", busy = true)
        connectLocked(AdbMdns.discover(context, AdbMdns.TLS_CONNECT))
    }

    private fun connectLocked(port: Int) {
        require(port in 1..65535) { "Enter the connection port from the main Wireless debugging page" }
        closeClient()
        val attempt = AdbClient("127.0.0.1", port, identity())
        client.set(attempt) // allows Cancel to close a blocked socket
        try {
            attempt.connect()
            validateShell(attempt)
            prefs.edit().putInt("last_port", port).apply()
            mutableState.value = ConnectionState("Connected — ADB shell verified", true)
        } catch (e: Exception) {
            attempt.close()
            client.compareAndSet(attempt, null)
            throw e
        }
    }

    private fun validateShell(attempt: AdbClient) {
        val uid = attempt.shellCommand("id -u").trim()
        if (uid != "2000" && uid != "0") throw IOException("ADB connected, but a shell identity could not be verified")
    }

    suspend fun importKey(bytes: ByteArray): Result<Unit> = operation("Importing an existing ADB key…") {
        require(bytes.size in 1..65536) { "Choose an ADB private key smaller than 64 KB" }
        closeClient()
        identity().importPrivateKey(bytes)
        key = null // reload both certificate and TLS context for the imported identity
        prefs.edit().putBoolean("adb_imported", true).remove("adb_paired").remove("last_port").apply()
        mutableState.value = ConnectionState("Key imported. Connect using the current connection port; the key must already be authorized.")
    }

    fun command(command: String, listener: CommandResultListener) {
        scope.launch {
            val result = commandSync(command)
            withContext(Dispatchers.Main) {
                result.fold({ listener.onCommandResult(it, true) }, {
                    listener.onCommandError(describeError(it))
                })
            }
        }
    }

    suspend fun commandSync(command: String): Result<String> = operation("", showBusy = false) {
        if (!isConnected) {
            val port = getCachedPort()
            if (port !in 1..65535) throw IOException("ADB disconnected. Open Setup / Pair and connect first.")
            connectLocked(port)
        }
        client.get()!!.shellCommand(command)
    }

    private suspend fun <T> operation(
        progress: String, showBusy: Boolean = true, block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        operations.withLock {
            if (showBusy) mutableState.value = ConnectionState(progress, busy = true)
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                mutableState.value = ConnectionState("Cancelled. You can retry Setup / Pair.", isConnected)
                throw e
            } catch (e: Exception) {
                if (!isConnected) closeClient()
                mutableState.value = ConnectionState(describeError(e), isConnected)
                Result.failure(e)
            }
        }
    }

    private fun closeClient() { client.getAndSet(null)?.close() }

    fun cancelCurrentOperation() {
        pairing.getAndSet(null)?.close()
        closeClient()
        mutableState.value = ConnectionState("Cancelled. You can retry Setup / Pair.")
    }

    fun disconnect() {
        cancelCurrentOperation()
        mutableState.value = ConnectionState("Disconnected. Tap Setup / Pair to reconnect.")
    }

    companion object {
        @Volatile private var instance: ShellExecutor? = null
        fun getInstance(context: Context): ShellExecutor = instance ?: synchronized(this) {
            instance ?: ShellExecutor(context.applicationContext).also { instance = it }
        }

        fun describeError(error: Throwable): String = when (error) {
            is SocketTimeoutException -> "ADB timed out. Keep Wireless debugging enabled and check the port. If pairing, use a new code."
            is ConnectException -> "Connection refused. The port changed or Wireless debugging is off. Pairing and connection use different ports."
            is SSLException -> "TLS authentication failed. Forget AudioPipe in Wireless debugging, then pair this app again."
            else -> error.message?.take(240) ?: "ADB connection failed. Open Setup / Pair and try again."
        }
    }
}
