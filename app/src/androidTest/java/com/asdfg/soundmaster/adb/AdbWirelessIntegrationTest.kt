package com.asdfg.soundmaster.adb

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Real Android 14 Settings pairing server, real TLS exporter, real adbd shell. No Bluetooth claims. */
@RunWith(AndroidJUnit4::class)
class AdbWirelessIntegrationTest {
    @Test fun pairWithAndroidSettingsAndAuthenticateShell() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("settings put global development_settings_enabled 1")
        device.executeShellCommand("svc wifi enable")
        device.executeShellCommand("settings put global adb_wifi_enabled 1")
        device.executeShellCommand("am start -n 'com.android.settings/.Settings\$WirelessDebuggingActivity'")
        val pairRow = device.wait(Until.findObject(By.text("Pair device with pairing code")), 15_000)
            ?: error("Wireless debugging pairing row was not available on this emulator")
        pairRow.click()
        val codeNode = device.wait(Until.findObject(By.text(Pattern.compile("[0-9]{6}"))), 10_000)
            ?: error("Android did not show a pairing code")
        val pin = codeNode.text
        val address = device.wait(Until.findObject(By.text(Pattern.compile("[0-9.]+:[0-9]+"))), 5_000)
            ?: error("Android did not show a pairing address")
        val pairPort = address.text.substringAfterLast(':').toInt()
        val shell = ShellExecutor.getInstance(context)
        shell.pair(pairPort, pin).getOrThrow()
        assertTrue(context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE).getBoolean("adb_paired", false))
        // Pairing and connection ports are separate. adbd publishes the latter as a system property.
        val port = device.executeShellCommand("getprop service.adb.tls.port").trim().toInt()
        assertNotEquals(pairPort, port)
        shell.connectResult(port).getOrThrow()
        assertTrue(shell.isConnected)
        assertTrue(shell.state.value.connected)
        val uid = shell.commandSync("id -u").getOrThrow().trim()
        assertTrue("Unexpected shell UID: $uid", uid == "2000" || uid == "0")
        shell.disconnect()
        // The same encrypted identity must still work after a new executor is constructed.
        val recreated = ShellExecutor(context)
        recreated.connectResult(port).getOrThrow()
        assertTrue(recreated.isConnected)
        recreated.disconnect()
    }
}
