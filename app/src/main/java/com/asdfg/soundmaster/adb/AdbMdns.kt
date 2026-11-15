package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Local-device-only, cancellable discovery. API 34 keeps service information up to date. */
object AdbMdns {
    const val TLS_CONNECT = "_adb-tls-connect._tcp."
    const val TLS_PAIRING = "_adb-tls-pairing._tcp."
    private val direct = Executor { it.run() }

    suspend fun discover(context: Context, type: String, timeoutMs: Long = 5_000): Int {
        var watch: Closeable? = null
        var detail = "no local service was found"
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val delivered = AtomicBoolean(false)
                    watch = watch(context, type, { port ->
                        if (continuation.isActive && delivered.compareAndSet(false, true)) continuation.resume(port)
                    }, {}, { problem ->
                        detail = problem
                        if (problem.startsWith("mDNS could not start") && continuation.isActive && delivered.compareAndSet(false, true))
                            continuation.resumeWithException(IOException("$problem. Enter the current connection port manually."))
                    })
                    continuation.invokeOnCancellation { watch?.close() }
                    if (!continuation.isActive) watch?.close()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("mDNS timed out: $detail. Keep Wi-Fi and Wireless debugging on, or enter the current connection port manually. Pairing is saved.", e)
        } finally { watch?.close() }
    }

    fun watch(context: Context, type: String, found: (Int) -> Unit, lost: (Int) -> Unit,
        issue: (String) -> Unit): Closeable {
        val manager = context.getSystemService(NsdManager::class.java)
            ?: throw IOException("Network service discovery is unavailable; enter the connection port manually")
        return Watch(manager, type, found, lost, issue).also { it.start() }
    }
    private fun sameType(actual: String?, expected: String): Boolean {
        fun normalized(s: String) = s.trimEnd('.').removeSuffix(".local")
        return actual.isNullOrBlank() || normalized(actual) == normalized(expected)
    }
    private fun local(info: NsdServiceInfo): Boolean = runCatching {
        val addresses = if (Build.VERSION.SDK_INT >= 34) Api34.addresses(info) else listOfNotNull(info.host)
        addresses.any { host -> host.isLoopbackAddress || NetworkInterface.getNetworkInterfaces()
            .asSequence().any { network -> network.inetAddresses.asSequence().any { it == host } } }
    }.getOrDefault(false)

    private class Watch(val manager: NsdManager, val type: String, val found: (Int) -> Unit,
        val lost: (Int) -> Unit, val issue: (String) -> Unit) : Closeable {
        val lock = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var closed = false
        var started = false
        var requested = false
        val ports = mutableMapOf<String, Int>()
        val pending = linkedMapOf<String, NsdServiceInfo>()
        var modern: Api34? = null
        var legacyBusy = false
        var legacyToken = 0
        var legacyTimeout: Job? = null
        val listener: NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String): Unit = synchronized(lock) {
                started = true
                if (closed) runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int): Unit = synchronized(lock) {
                if (!closed) issue("mDNS could not start (error $errorCode)")
                close()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo): Unit = synchronized(lock) {
                if (closed || !sameType(info.serviceType, type)) return@synchronized
                val key = key(info)
                if ((Build.VERSION.SDK_INT >= 34 && modern?.contains(key) == true) || pending.containsKey(key)) return@synchronized
                // Bound callback registrations and metadata from a busy local network.
                if (pending.size < 64) pending[key] = info
                pump()
            }
            override fun onServiceLost(info: NsdServiceInfo): Unit = synchronized(lock) {
                if (closed) return@synchronized
                val key = key(info)
                pending.remove(key)
                if (Build.VERSION.SDK_INT >= 34) modern?.remove(key)
                ports.remove(key)?.let(lost)
            }
        }
        fun key(info: NsdServiceInfo): String = "${info.serviceName ?: System.identityHashCode(info)}|${info.serviceType}|" +
            if (Build.VERSION.SDK_INT >= 33) info.network.toString() else "legacy"
        fun start(): Unit = synchronized(lock) {
            if (Build.VERSION.SDK_INT >= 34) modern = Api34(this)
            requested = true
            try {
                if (Build.VERSION.SDK_INT >= 33) manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD,
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), direct, listener)
                else manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) { issue("mDNS could not start (${e.javaClass.simpleName})"); close() }
        }
        fun resolved(key: String, info: NsdServiceInfo) {
            if (closed || !sameType(info.serviceType, type) || info.port !in 1..65535 || !local(info)) return
            if (ports.put(key, info.port) != info.port) found(info.port)
        }
        fun pump() {
            if (closed) return
            if (Build.VERSION.SDK_INT >= 34) modern?.let { it.pump(); return }
            if (legacyBusy || pending.isEmpty()) return
            val (key, info) = pending.entries.first().let { it.key to it.value }
            pending.remove(key)
            legacyBusy = true
            val token = ++legacyToken
            fun finished() { if (token == legacyToken) { legacyBusy = false; legacyTimeout?.cancel(); pump() } }
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int): Unit = synchronized(lock) {
                    if (closed || token != legacyToken) return@synchronized
                    if (!closed) issue("service resolution failed ($errorCode)")
                    finished()
                }
                override fun onServiceResolved(info: NsdServiceInfo): Unit = synchronized(lock) {
                    if (!closed && token == legacyToken) { resolved(key, info); finished() }
                }
            }
            legacyTimeout = scope.launch {
                delay(2_000)
                synchronized(lock) { if (!closed && token == legacyToken) { legacyBusy = false; legacyToken++; pump() } }
            }
            try { manager.resolveService(info, callback) }
            catch (e: Exception) { issue("service resolution failed (${e.javaClass.simpleName})"); finished() }
        }
        override fun close(): Unit = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            if (Build.VERSION.SDK_INT >= 34) modern?.close()
            scope.cancel()
            pending.clear(); ports.clear()
            if (requested) runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    @RequiresApi(34)
    private class Api34(val owner: Watch) {
        private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        private val deadlines = mutableMapOf<String, Job>()
        fun contains(key: String) = callbacks.containsKey(key)
        fun pump() {
            while (!owner.closed && callbacks.size < 4 && owner.pending.isNotEmpty()) {
                val (key, info) = owner.pending.entries.first().let { it.key to it.value }
                owner.pending.remove(key)
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int): Unit = synchronized(owner.lock) {
                        if (callbacks[key] !== this) return@synchronized
                        if (!owner.closed) owner.issue("service updates unavailable ($errorCode)")
                        remove(key)
                    }
                    override fun onServiceUpdated(info: NsdServiceInfo): Unit = synchronized(owner.lock) {
                        if (owner.closed || callbacks[key] !== this) return@synchronized
                        if (local(info)) {
                            deadlines.remove(key)?.cancel()
                            owner.resolved(key, info)
                        } else if (addresses(info).isNotEmpty()) remove(key)
                    }
                    override fun onServiceLost(): Unit = synchronized(owner.lock) {
                        if (callbacks[key] !== this) return@synchronized
                        owner.ports.remove(key)?.let(owner.lost)
                        remove(key)
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                callbacks[key] = callback
                deadlines[key] = owner.scope.launch { delay(2_000); synchronized(owner.lock) { if (callbacks[key] === callback) remove(key) } }
                try { owner.manager.registerServiceInfoCallback(info, direct, callback) }
                catch (e: Exception) { owner.issue("service updates unavailable (${e.javaClass.simpleName})"); remove(key) }
            }
        }
        fun remove(key: String) {
            deadlines.remove(key)?.cancel()
            callbacks.remove(key)?.let { runCatching { owner.manager.unregisterServiceInfoCallback(it) } }
            if (!owner.closed) pump()
        }
        fun close() { callbacks.keys.toList().forEach(::remove) }
        companion object {
            fun addresses(info: NsdServiceInfo): List<InetAddress> = info.hostAddresses.ifEmpty { listOfNotNull(info.host) }
        }
    }
}
