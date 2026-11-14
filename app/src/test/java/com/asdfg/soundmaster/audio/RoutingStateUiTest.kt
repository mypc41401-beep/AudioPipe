package com.asdfg.soundmaster.audio

import android.os.Looper
import android.widget.TextView
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutingStateUiTest {
    @Test fun `recreated UI reports service evidence even before app and device lists load`() {
        val field = SoundMasterService::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val flow = field.get(null) as MutableStateFlow<SoundMasterService.RoutingState>
        val original = flow.value
        val message = "Policy accepted; split not yet observed. Play both apps."
        flow.value = SoundMasterService.RoutingState(active = true,
            packageName = "com.music.bitchord", deviceId = 42, deviceName = "X-906", message = message)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val status = controller.get().findViewById<TextView>(R.id.serviceStatus).text.toString()
            assertEquals(message, status)
            assertFalse(status.contains("null"))
            assertFalse(status.contains("Default"))
        } finally {
            controller.pause().stop().destroy()
            flow.value = original
        }
    }
}
