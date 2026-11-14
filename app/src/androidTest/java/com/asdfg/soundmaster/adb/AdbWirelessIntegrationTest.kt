package com.asdfg.soundmaster.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Real API-34 system pairing server, TLS exporter and adbd. CI starts the same service
 * used by Settings through shell-only IAdbManager transactions (AOSP android-14.0.0_r1).
 * Shell permission is dropped BEFORE invoking any production pairing/client code.
 * These fixture commands are in the test APK only, never in the application APK.
 */
@RunWith(AndroidJUnit4::class)
class AdbWirelessIntegrationTest {
    @Test fun pairWithSystemAdbdAndAuthenticateShell() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Real pairing requires secure adbd", "1",
            device.executeShellCommand("getprop ro.adb.secure").trim())
        device.executeShellCommand("svc wifi enable")
        var wifi = ""
        var bssid: String? = null
        repeat(50) {
            if (bssid == null) {
                wifi = device.executeShellCommand("cmd wifi status")
                bssid = Regex("(?i)BSSID: ([0-9a-f:]{17})").find(wifi)?.groupValues?.get(1)
                if (bssid == null) Thread.sleep(200)
            }
        }
        val currentBssid = bssid ?: error("Emulator has no connected Wi-Fi BSSID: $wifi")
        val allowed = device.executeShellCommand("service call adb 4 i32 1 s16 $currentBssid")
        check(!allowed.contains("Exception")) { "Could not authorize emulator Wi-Fi: $allowed" }
        device.executeShellCommand("settings put global adb_wifi_enabled 1")
        var connectionPort = -1
        var portReply = ""
        repeat(50) {
            if (connectionPort !in 1..65535) {
                // service.adb.tls.port is not readable by shell on every user image.
                // API-34 IAdbManager transaction 10 returns the port as a Parcel int.
                portReply = device.executeShellCommand("service call adb 10")
                val words = Regex("(?i)(?<![a-z0-9])[0-9a-f]{8}(?![a-z0-9])")
                    .findAll(portReply).map { it.value }.toList()
                connectionPort = words.getOrNull(1)?.toLong(16)?.toInt() ?: -1
                if (connectionPort !in 1..65535) Thread.sleep(200)
            }
        }
        check(connectionPort in 1..65535) { "Could not read secure adbd port: reply=$portReply, wifi=$wifi" }

        val ready = CountDownLatch(2)
        val code = AtomicReference<String?>()
        val pairingPort = AtomicInteger(-1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(unused: Context, intent: Intent) {
                when (intent.getIntExtra("status", -1)) {
                    3 -> intent.getStringExtra("pairing_code")?.let {
                        if (code.compareAndSet(null, it)) ready.countDown()
                    }
                    4 -> {
                        val port = intent.getIntExtra("adb_port", -1)
                        if (port in 1..65535 && pairingPort.compareAndSet(-1, port)) ready.countDown()
                    }
                }
            }
        }
        // The real system broadcasts the code/port to holders of MANAGE_DEBUGGING.
        // The permission is used only to observe fixture setup, not to authenticate ADB.
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DEBUGGING")
        try {
            context.registerReceiver(receiver,
                IntentFilter("com.android.server.adb.WIRELESS_DEBUG_PAIRING_RESULT"),
                Context.RECEIVER_EXPORTED)
            val started = device.executeShellCommand("service call adb 8")
            check(!started.contains("Exception")) { "System pairing server refused to start: $started" }
            assertTrue("System pairing code/port broadcast timed out", ready.await(15, TimeUnit.SECONDS))
        } finally {
            context.unregisterReceiver(receiver)
            automation.dropShellPermissionIdentity()
        }

        try {
            // All calls below execute as the ordinary application UID with manifest permissions.
            val shell = ShellExecutor.getInstance(context)
            shell.pair(pairingPort.get(), checkNotNull(code.get())).getOrThrow()
            assertTrue(context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE).getBoolean("adb_paired", false))
            assertNotEquals(pairingPort.get(), connectionPort)
            // The fixture already knows the port, so it can race adbd's asynchronous key
            // reload immediately after pairing. Only retry this specific readiness alert.
            var connected: Result<Unit> = Result.failure(IllegalStateException("ADB not attempted"))
            for (attempt in 0..3) {
                connected = shell.connectResult(connectionPort)
                if (connected.isSuccess) break
                val failure = connected.exceptionOrNull()!!
                if (failure !is javax.net.ssl.SSLException ||
                    !failure.message.orEmpty().contains("CERTIFICATE_UNKNOWN", ignoreCase = true)) throw failure
                if (attempt < 3) {
                    println("Waiting for system adbd paired-key reload, attempt ${attempt + 1}")
                    kotlinx.coroutines.delay(250L * (attempt + 1))
                }
            }
            connected.getOrThrow()
            assertTrue(shell.isConnected)
            assertTrue(shell.state.value.connected)
            val uid = shell.commandSync("id -u").getOrThrow().trim()
            assertEquals("2000", uid)
            shell.disconnect()
            val recreated = ShellExecutor(context)
            recreated.connectResult(connectionPort).getOrThrow()
            assertTrue(recreated.isConnected)
            recreated.disconnect()
        } finally {
            code.set(null)
            device.executeShellCommand("service call adb 11")
        }
    }
}
