package com.asdfg.soundmaster.audio

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.R
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityLifecycleTest {
    @Test fun launchRecreateBackgroundAndRejectInvalidServiceStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertEquals("Routing must not request playback-capture permission",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                scenario.onActivity { it.startForegroundService(Intent(it, SoundMasterService::class.java)) }
                Thread.sleep(300)
                val deadline = System.nanoTime() + 4_000_000_000L
                @Suppress("DEPRECATION")
                fun alive(): Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getRunningServices(100).any { it.service.className == SoundMasterService::class.java.name }
                while (alive() && System.nanoTime() < deadline) Thread.sleep(100)
                assertFalse("Invalid start must not leak a foreground service", alive())
                assertFalse("Invalid start must not advertise active routing", SoundMasterService.running)
            }
            scenario.recreate()
            scenario.onActivity {
                val text = it.findViewById<TextView>(R.id.serviceStatus).text.toString()
                assertFalse(text.contains("Routing null"))
                assertFalse(text.contains("→ Default"))
                assertTrue(it.findViewById<android.widget.Button>(R.id.startStopButton).isEnabled)
            }
            device.pressHome()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertTrue(RoutingDiagnostics.report(context).contains("originalPlayerRoutes"))
        }
    }
}
