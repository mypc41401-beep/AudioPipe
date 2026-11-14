package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One-shot, local-device-only discovery, stopped on success, failure, timeout or cancellation. */
object AdbMdns {
    const val TLS_CONNECT = "_adb-tls-connect._tcp."
    const val TLS_PAIRING = "_adb-tls-pairing._tcp."

    suspend fun discover(context: Context, type: String, timeoutMs: Long = 5_000): Int {
        val manager = context.getSystemService(NsdManager::class.java)
            ?: throw IOException("Network service discovery is unavailable; enter the port manually")
        var detail = "no local service was found"
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val delivered = AtomicBoolean(false)
                    val discovery = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {
                            if (!continuation.isActive) runCatching { manager.stopServiceDiscovery(this) }
                        }
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (continuation.isActive && delivered.compareAndSet(false, true)) continuation.resumeWithException(
                                IOException("mDNS could not start (error $errorCode). Enter the port manually.")
                            )
                        }
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (!continuation.isActive) return
                            try {
                                manager.resolveService(info, object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                        detail = "service resolution failed ($errorCode)"
                                    }
                                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                                        if (!continuation.isActive || resolved.port !in 1..65535) return
                                        val local = runCatching {
                                            val host = resolved.host ?: return@runCatching false
                                            host.isLoopbackAddress || NetworkInterface.getNetworkInterfaces()
                                                .asSequence().any { network -> network.inetAddresses.asSequence()
                                                    .any { it == host } }
                                        }.getOrDefault(false)
                                        if (local && continuation.isActive && delivered.compareAndSet(false, true)) {
                                            continuation.resume(resolved.port)
                                        }
                                    }
                                })
                            } catch (e: Exception) {
                                detail = e.message ?: "service resolution failed"
                            }
                        }
                    }
                    listener = discovery
                    continuation.invokeOnCancellation {
                        runCatching { manager.stopServiceDiscovery(discovery) }
                    }
                    try {
                        manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
                    } catch (e: Exception) {
                        if (continuation.isActive && delivered.compareAndSet(false, true)) continuation.resumeWithException(
                            IOException("mDNS discovery is unavailable. Enter the port manually.", e)
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("mDNS timed out: $detail. Enter the port from Wireless debugging manually.", e)
        } finally {
            listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        }
    }
}
