package com.asdfg.soundmaster.adb

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import java.io.ByteArrayOutputStream

/** Real Android 14 Settings pairing server, real TLS exporter, real adbd shell. No Bluetooth claims. */
@RunWith(AndroidJUnit4::class)
class AdbWirelessIntegrationTest {
    @Test fun pairWithAndroidSettingsAndAuthenticateShell() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Real pairing requires a secure-adbd system image", "1",
            device.executeShellCommand("getprop ro.adb.secure").trim())
        device.executeShellCommand("settings put global development_settings_enabled 1")
        device.executeShellCommand("settings put global device_provisioned 1")
        device.executeShellCommand("settings put secure user_setup_complete 1")
        device.executeShellCommand("svc wifi enable")
        device.executeShellCommand("settings put global adb_wifi_enabled 1")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // AOSP has no WirelessDebuggingActivity. Follow the public Developer options UI.
        device.executeShellCommand("pm enable com.android.settings/.Settings\$DevelopmentSettingsDashboardActivity")
        val launch = device.executeShellCommand("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        device.waitForIdle()
        val found = UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(16)
            .scrollIntoView(UiSelector().text("Wireless debugging"))
        assertTrue("Developer options did not contain Wireless debugging: $launch", found)
        device.findObject(By.text("Wireless debugging")).click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 2_000)?.click()
        var pairRow = device.wait(Until.findObject(By.text("Pair device with pairing code")), 5_000)
        if (pairRow == null) {
            device.findObjects(By.checkable(true)).firstOrNull { !it.isChecked }?.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 2_000)?.click()
            pairRow = device.wait(Until.findObject(By.text("Pair device with pairing code")), 10_000)
        }
        if (pairRow == null) {
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            val secure = device.executeShellCommand("getprop ro.adb.secure").trim()
            val texts = device.findObjects(By.text(Pattern.compile(".+"))).map { it.text }.take(80)
            error("Wireless debugging pairing row missing. launch=$launch, ro.adb.secure=$secure, texts=$texts, UI=${hierarchy.toString("UTF-8").take(12000)}")
        }
        pairRow.click()
        val codeNode = device.wait(Until.findObject(By.text(Pattern.compile("[0-9]{6}"))), 10_000)
            ?: error("Android did not show a pairing code")
        val pin = codeNode.text
        val connectionPort = device.executeShellCommand("getprop service.adb.tls.port").trim().toInt()
        val addressSelector = By.text(Pattern.compile("[0-9.]+:[0-9]+"))
        device.wait(Until.hasObject(addressSelector), 5_000)
        val pairPort = device.findObjects(addressSelector).map { it.text.substringAfterLast(':').toInt() }
            .firstOrNull { it != connectionPort } ?: error("Android did not show a separate pairing address")
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
