package com.asdfg.soundmaster.audio

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.adb.AdbWirelessIntegrationTest
import com.asdfg.soundmaster.adb.ShellExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Copied into the test APK by this optional CI job only. Two ordinary original-player UIDs. */
@RunWith(AndroidJUnit4::class)
class TwoUidBluetoothTest {
    @Test fun productionServiceSeparatesTwoUidsSurvivesUiAndStopsOnBluetoothLoss() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val manager = context.getSystemService(AudioManager::class.java)
        val bt = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        assertNotNull("A real emulated A2DP endpoint is required; no fake device fixture", bt)
        val bluetooth = checkNotNull(bt)
        val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // Same production SPAKE2/TLS path as the user's phone, with test-only system setup.
        AdbWirelessIntegrationTest().pairWithSystemAdbdAndAuthenticateShell()
        val shell = ShellExecutor.getInstance(context)
        shell.connectResult(shell.getCachedPort()).getOrThrow()
        assertTrue(shell.isConnected)
        val peer = "dev.audiopipe.ci.peer"
        val peerUid = context.packageManager.getApplicationInfo(peer,0).uid
        assertTrue(peerUid >= 10000)
        assertNotEquals(context.applicationInfo.uid, peerUid)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({}, Handler(context.mainLooper)).build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
        val frames = 4800
        val samples = ShortArray(frames*2) { i ->
            (1000*kotlin.math.sin(2*Math.PI*440*(i/2)/48000)).toInt().toShort()
        }
        val player = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames*4).build()
        try {
            assertEquals(samples.size,player.write(samples,0,samples.size))
            player.setLoopPoints(0,frames,-1)
            player.play()
            assertNull(player.preferredDevice)
            device.executeShellCommand("am start -W -n $peer/.PeerPlayerActivity")
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                repeat(2) { cycle ->
                    activity.onActivity {
                        it.startForegroundService(Intent(it,SoundMasterService::class.java).apply {
                            putExtra("package",context.packageName)
                            putExtra("device",bluetooth.id)
                            putExtra("deviceName","AudioPipe CI Speaker")
                        })
                    }
                    await("Production service must observe both ordinary UIDs on disjoint outputs: ${SoundMasterService.state.value}") {
                        SoundMasterService.state.value.bothObserved && player.routedDevice?.id == bluetooth.id
                    }
                    assertBoth(context,context.applicationInfo.uid,peerUid,bluetooth.id,speaker.id)
                    @Suppress("DEPRECATION")
                    val foreground = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                        .firstOrNull { it.service.className==SoundMasterService::class.java.name }
                    assertTrue("Production service must be in foreground",foreground?.foreground == true)
                    if (cycle==0) {
                        val before = report(context).getString("routeObservationAt")
                        device.pressHome()
                        Thread.sleep(10000)
                        assertTrue(SoundMasterService.state.value.active)
                        assertNotEquals("Observer must keep updating in background",before,report(context).getString("routeObservationAt"))
                        assertBoth(context,context.applicationInfo.uid,peerUid,bluetooth.id,speaker.id)
                        context.startActivity(Intent(context,MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        activity.moveToState(Lifecycle.State.RESUMED)
                        activity.recreate()
                        assertTrue("UI recreation must preserve service state",SoundMasterService.state.value.bothObserved)
                        println("TWO_UID_BACKGROUND_DIAGNOSTIC\n"+RoutingDiagnostics.report(context))
                        context.stopService(Intent(context,SoundMasterService::class.java))
                        await("Stop must finish acknowledged native policy cleanup") { !SoundMasterService.running }
                        await("Original peer must settle back to default Bluetooth after Stop") {
                            val outputs=listOf(NativeRoutes.Device(bluetooth.id,"AUDIO_DEVICE_OUT_BLUETOOTH_A2DP",bluetooth.address),
                                NativeRoutes.Device(speaker.id,"AUDIO_DEVICE_OUT_SPEAKER",speaker.address))
                            val players=NativeRoutes.parse(ShellFixture.run("dumpsys media.audio_policy"),outputs,
                                context.applicationInfo.uid,bluetooth.id,speaker.id)
                            (0 until players.length()).any { val p=players.getJSONObject(it);p.getInt("uid")==peerUid && p.getInt("actual")==bluetooth.id }
                        }
                    } else {
                        device.executeShellCommand("svc bluetooth disable")
                        await("Bluetooth loss must stop service, not leave a false routing claim") { !SoundMasterService.running }
                        assertTrue(SoundMasterService.state.value.message.contains("disconnected",ignoreCase=true))
                        println("TWO_UID_DISCONNECT_STATUS "+SoundMasterService.state.value.message)
                    }
                }
            }
        } finally {
            println("TWO_UID_FINAL_DIAGNOSTIC\n"+RoutingDiagnostics.report(context))
            println("TWO_UID_NATIVE_DUMP\n"+ShellFixture.run("dumpsys media.audio_policy"))
            context.stopService(Intent(context,SoundMasterService::class.java))
            player.stop();player.release()
            manager.abandonAudioFocusRequest(focus)
            device.executeShellCommand("am force-stop $peer")
            shell.disconnect()
        }
    }
    private fun report(context: Context) = JSONObject(RoutingDiagnostics.report(context).substringBefore("\n\nState transitions / errors:"))
    private fun assertBoth(context: Context, selectedUid: Int, peerUid: Int, bt: Int, speaker: Int) {
        val snapshot=report(context)
        assertTrue(snapshot.getJSONObject("service").getBoolean("bothRoutesObserved"))
        val players=snapshot.getJSONObject("originalPlayerRoutes").getJSONArray("players")
        assertTrue((0 until players.length()).any { val p=players.getJSONObject(it);p.getInt("uid")==selectedUid && p.getInt("actual")==bt })
        assertTrue((0 until players.length()).any { val p=players.getJSONObject(it);p.getInt("uid")==peerUid && p.getInt("actual")==speaker })
        assertTrue((0 until players.length()).all { val p=players.getJSONObject(it);p.getInt("actual")==p.getInt("expected") })
    }
    private fun await(message: String,predicate: () -> Boolean) {
        val deadline=System.nanoTime()+25_000_000_000L
        while(!predicate() && System.nanoTime()<deadline) Thread.sleep(150)
        assertTrue(message+"; latest="+SoundMasterService.state.value,predicate())
    }
}
