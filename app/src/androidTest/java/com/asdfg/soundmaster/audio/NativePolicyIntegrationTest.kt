package com.asdfg.soundmaster.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.io.PrintWriter
import java.util.UUID
import com.asdfg.soundmaster.MainActivity

/** Real shell policy/observer tests against the emulator HAL. A route-change test explicitly skips
 * if the HAL provides no second endpoint. Neither test is a Xiaomi/Bluetooth acoustic test. */
@RunWith(AndroidJUnit4::class)
class NativePolicyIntegrationTest {
    @Test fun realShellPolicyObservesOriginalTrackAndPreservesFocus() = exercisePolicy(false)

    @Test fun originalTrackChangesDeviceWhenSecondEndpointExists() = exercisePolicy(true)

    private fun exercisePolicy(requireSecondEndpoint: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val apk = context.applicationInfo.sourceDir + ":" + instrumentation.context.applicationInfo.sourceDir
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speaker = devices.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val alternative = devices.firstOrNull { it.id != speaker.id && it.type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) }
        if (requireSecondEndpoint) assumeTrue(
            "HAL exposes only one usable output; a second native device cannot be simulated on this user image",
            alternative != null)
        val originalFocus = device.executeShellCommand("settings get system multi_audio_focus_enabled").trim()
        val originalEnabled = originalFocus == "1"
        val frames = 4800
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames * 4).build()
        val samples = ShortArray(frames * 2) { ((kotlin.math.sin(it * 0.04)) * 100).toInt().toShort() }
        assertEquals(samples.size, player.write(samples, 0, samples.size))
        player.setLoopPoints(0, frames, -1)
        player.play()
        try {
            await("Original track must have a real routed device") { player.routedDevice != null }
            val initial = checkNotNull(player.routedDevice)
            val target = if (requireSecondEndpoint) devices.first { it.id != initial.id && it.id in listOf(speaker.id, alternative!!.id) }
                else initial
            if (requireSecondEndpoint) assertNotEquals(initial.id, target.id)
            assertNull("No preferred-device request is used", player.preferredDevice)
            repeat(3) { cycle ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 20000
                val token = UUID.randomUUID().toString()
                ShellFixture.run("(trap '' HUP; CLASSPATH=${RouteSession.quote(apk)} " +
                    "/system/bin/app_process /system/bin com.asdfg.soundmaster.audio.NativePolicyFixture " +
                    "${server.localPort} $token ${context.applicationInfo.uid} ${target.id} ${speaker.id} " +
                    "${if (originalEnabled) 1 else 0} </dev/null >/dev/null 2>&1 &)")
                server.accept().use { control ->
                    control.soTimeout = 10000
                    val input = control.getInputStream().bufferedReader()
                    val output = PrintWriter(control.getOutputStream(), true)
                    assertEquals("Real shell helper must accept the native policy", "READY $token 2000", input.readLine())
                    assertEquals("1", device.executeShellCommand("settings get system multi_audio_focus_enabled").trim())
                    await("Original track does not match the policy output") {
                        player.routedDevice?.id == target.id
                    }
                    output.println("status")
                    val status = JSONObject(input.readLine())
                    val players = status.getJSONArray("players")
                    assertTrue("Shell must see original app UID and actual device", (0 until players.length()).any {
                        val p = players.getJSONObject(it)
                        p.getInt("uid") == context.applicationInfo.uid && p.getInt("actual") == target.id
                    })
                    // An app's explicit output preference may override a primary dynamic policy
                    // on Android 14. This is a platform limit, not success: the monitor must see it.
                    assertTrue(player.setPreferredDevice(speaker))
                    Thread.sleep(500)
                    output.println("status")
                    val overrideStatus = JSONObject(input.readLine())
                    val overridden = RouteEvidence.from(overrideStatus, context.applicationInfo.uid)
                    if (target.id != speaker.id && player.routedDevice?.id != target.id) {
                        assertTrue("A device override must invalidate requested routing", overridden.wrong > 0)
                        assertFalse(overridden.bothObserved)
                    }
                    player.setPreferredDevice(null)
                    await("UID route must resume after clearing explicit preference") { player.routedDevice?.id == target.id }

                    if (cycle == 0) ActivityScenario.launch(MainActivity::class.java).use { activity ->
                        device.pressHome()
                        Thread.sleep(800)
                        assertEquals("UI backgrounding must not remove the shell policy", target.id, player.routedDevice?.id)
                        context.startActivity(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                        activity.recreate()
                        assertEquals("Activity recreation must not remove the shell policy", target.id, player.routedDevice?.id)
                    }
                    output.println("stop")
                    assertEquals("STOPPED", input.readLine())
                }
            }
            await("Native route must return to Android default on cleanup") { player.routedDevice?.id == initial.id }
            assertEquals(if (originalEnabled) "1" else "0",
                device.executeShellCommand("settings get system multi_audio_focus_enabled").trim())
            }
        } finally { player.stop(); player.release() }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(message, predicate())
    }
}
