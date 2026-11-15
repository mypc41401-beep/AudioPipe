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
        context.getSharedPreferences(RouteSession.PREFS,Context.MODE_PRIVATE).edit()
            .putString("selected_package",peer).commit()
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
            await("Peer source must be a real foreground media service") {
                ShellFixture.run("dumpsys activity services $peer").contains("isForeground=true")
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                await("The real app picker must restore the selected peer package") {
                    var selected=false
                    activity.onActivity { selected=it.findViewById<android.widget.Spinner>(com.asdfg.soundmaster.R.id.appSpinner)
                        .selectedItem?.toString()?.contains(peer)==true }
                    selected
                }
                repeat(5) { cycle ->
                    activity.onActivity { it.findViewById<android.widget.Button>(com.asdfg.soundmaster.R.id.startStopButton).performClick() }
                    await("Production service must observe both ordinary UIDs on disjoint outputs: ${SoundMasterService.state.value}") {
                        SoundMasterService.state.value.bothObserved && player.routedDevice?.id == speaker.id
                    }
                    assertBoth(context,peerUid,context.applicationInfo.uid,bluetooth.id,speaker.id)
                    @Suppress("DEPRECATION")
                    val foreground = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                        .firstOrNull { it.service.className==SoundMasterService::class.java.name }
                    assertTrue("Production service must be in foreground",foreground?.foreground == true)
                    if (cycle<4) {
                      if (cycle==0) {
                        val before = report(context).getString("routeObservationAt")
                        val initial = report(context).getJSONObject("service").getInt("snapshotCount")
                        val initialFds = report(context).getJSONObject("originalPlayerRoutes").getInt("helperOpenFds")
                        device.pressHome()
                        // Two minutes of actual software Bluetooth playback, rather than a compile-only test.
                        repeat(8) { tick ->
                            Thread.sleep(15000)
                            assertTrue("FGS must remain active during soak",SoundMasterService.state.value.active)
                            assertBoth(context,peerUid,context.applicationInfo.uid,bluetooth.id,speaker.id)
                            assertEquals("No automatic policy-reinstall loop",1,SoundMasterService.state.value.policyGeneration)
                            println("TWO_UID_SOAK_TICK_${tick+1} "+report(context).getJSONObject("service"))
                        }
                        assertTrue(SoundMasterService.state.value.active)
                        assertNotEquals("Observer must keep updating in background",before,report(context).getString("routeObservationAt"))
                        assertBoth(context,peerUid,context.applicationInfo.uid,bluetooth.id,speaker.id)
                        val after = report(context)
                        val probeDelta = after.getJSONObject("service").getInt("snapshotCount")-initial
                        assertTrue("Steady-state native probes must be sparse, actual=$probeDelta",probeDelta in 6..12)
                        assertTrue("Helper descriptors must not leak during soak",after.getJSONObject("originalPlayerRoutes")
                            .getInt("helperOpenFds") <= initialFds+6)
                        context.startActivity(Intent(context,MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        activity.moveToState(Lifecycle.State.RESUMED)
                        activity.recreate()
                        assertTrue("UI recreation must preserve service state",SoundMasterService.state.value.bothObserved)
                        println("TWO_UID_BACKGROUND_DIAGNOSTIC\n"+RoutingDiagnostics.report(context))
                        activity.onActivity { it.findViewById<android.widget.Button>(com.asdfg.soundmaster.R.id.repairAudioButton).performClick() }
                        await("Repair must re-establish original UID routing after acknowledged cleanup") {
                            SoundMasterService.state.value.policyGeneration==2 && SoundMasterService.state.value.bothObserved
                        }
                        assertBoth(context,peerUid,context.applicationInfo.uid,bluetooth.id,speaker.id)
                        repeat(3) { context.startService(Intent(context,SoundMasterService::class.java)
                            .setAction(SoundMasterService.ACTION_REPAIR)) }
                        Thread.sleep(1500)
                        assertEquals("Repeated repair taps must not loop audio policy resets",2,SoundMasterService.state.value.policyGeneration)
                        println("TWO_UID_REPAIR_DIAGNOSTIC\n"+RoutingDiagnostics.report(context))
                      }
                        activity.onActivity { it.findViewById<android.widget.Button>(com.asdfg.soundmaster.R.id.startStopButton).performClick() }
                        await("Stop must finish acknowledged native policy cleanup") { !SoundMasterService.running }
                        assertEquals("Stopped diagnostics must discard previous routing evidence","No shell observation yet",
                            report(context).getString("originalPlayerRoutes"))
                        await("Original peer must settle back to default Bluetooth after Stop") {
                            val outputs=listOf(NativeRoutes.Device(bluetooth.id,"AUDIO_DEVICE_OUT_BLUETOOTH_A2DP",bluetooth.address),
                                NativeRoutes.Device(speaker.id,"AUDIO_DEVICE_OUT_SPEAKER",speaker.address))
                            val players=NativeRoutes.parse(ShellFixture.run("dumpsys media.audio_policy"),outputs,
                                peerUid,bluetooth.id,speaker.id)
                            listOf(peerUid,context.applicationInfo.uid).all { uid -> (0 until players.length()).any {
                                val p=players.getJSONObject(it);p.getInt("uid")==uid && p.getInt("actual")==bluetooth.id } }
                        }
                    } else {
                        device.executeShellCommand("svc bluetooth disable")
                        await("Bluetooth loss must stop service, not leave a false routing claim") { !SoundMasterService.running }
                        assertTrue(SoundMasterService.state.value.message.contains("disconnected",ignoreCase=true))
                        println("TWO_UID_DISCONNECT_STATUS "+SoundMasterService.state.value.message)
                    }
                }
                device.executeShellCommand("svc bluetooth enable")
                await("Bonded virtual A2DP speaker must reconnect") {
                    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                }
                val reconnected=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                await("App must restore the reconnected A2DP selection") {
                    var ready=false
                    activity.onActivity { ready=it.findViewById<android.widget.Spinner>(com.asdfg.soundmaster.R.id.outputSpinner)
                        .selectedItem?.toString()?.contains("A2DP")==true }
                    ready
                }
                activity.onActivity { it.findViewById<android.widget.Button>(com.asdfg.soundmaster.R.id.startStopButton).performClick() }
                await("UI Start must work again after Bluetooth reconnection") { SoundMasterService.state.value.bothObserved }
                val installedPort=SoundMasterService.state.value.deviceId
                assertTrue("Resolved port must be a current A2DP device",manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.id==installedPort })
                assertBoth(context,peerUid,context.applicationInfo.uid,installedPort,speaker.id)
                println("TWO_UID_RECONNECTED_DIAGNOSTIC\n"+RoutingDiagnostics.report(context))
                activity.onActivity { it.findViewById<android.widget.Button>(com.asdfg.soundmaster.R.id.startStopButton).performClick() }
                await("Final UI Stop must finish policy cleanup") { !SoundMasterService.running }
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
