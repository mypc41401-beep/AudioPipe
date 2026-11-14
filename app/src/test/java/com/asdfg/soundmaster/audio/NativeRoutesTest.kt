package com.asdfg.soundmaster.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeRoutesTest {
    @Test fun `multi focus uses actual runtime state not a saved preference alone`() {
        assertTrue(NativeRoutes.multiFocusEnabled("Multi Audio Focus enabled :true"))
        assertFalse(NativeRoutes.multiFocusEnabled("Multi Audio Focus enabled :false"))
        assertThrows(IllegalStateException::class.java) { NativeRoutes.multiFocusEnabled("Saved setting: 1") }
    }
    private val devices = listOf(NativeRoutes.Device(2, "AUDIO_DEVICE_OUT_SPEAKER", ""),
        NativeRoutes.Device(20, "AUDIO_DEVICE_OUT_BLUETOOTH_A2DP", "12:34:56:78:9A:BC"))
    private fun dump(current: String, preferred: Int = 20, state: String = "Active") = """
 Outputs (1):
  1. Port ID: 13; I/O handle: 13; IOProfile name:primary; Latency: 30
     Devices: {$current}
     - AudioTrack clients (1):
       1. Port ID: 99; Session ID: 6; uid 10123; State: $state
          AUDIO_FORMAT_PCM_16_BIT; 48000; Channel mask: 0x3
          Attributes: {usage=AUDIO_USAGE_MEDIA content=AUDIO_CONTENT_TYPE_MUSIC}
          Preferred Device Port ID: $preferred;
     - AudioTrack active (stream) clients (1):
       id 1: Port ID: 99; Session ID: 6; uid 10123; State: $state
          Attributes: {usage=AUDIO_USAGE_MEDIA content=AUDIO_CONTENT_TYPE_MUSIC}
 Inputs (0):
    """.trimIndent()

    @Test fun `uses current native output not preferred output and deduplicates client lists`() {
        val players = NativeRoutes.parse(dump("AUDIO_DEVICE_OUT_SPEAKER, @:"), devices, 10123, 20, 2)
        assertEquals(1, players.length())
        assertEquals(2, players.getJSONObject(0).getInt("actual"))
        assertFalse(RouteEvidence.from(org.json.JSONObject().put("players", players), 10123).bothObserved)
    }

    @Test fun `matches Bluetooth address but never emits it in parsed data`() {
        val players = NativeRoutes.parse(dump("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:12:34:56:78:9a:bc"), devices, 10123, 20, 2)
        assertEquals(20, players.getJSONObject(0).getInt("actual"))
        assertFalse(players.toString().contains("12:34"))
    }

    @Test fun `duplicate outputs and unmapped devices cannot pass isolation checks`() {
        for (output in listOf("AUDIO_DEVICE_OUT_SPEAKER, @:;AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:12:34:56:78:9A:BC",
            "AUDIO_DEVICE_OUT_USB_DEVICE, @:card=2")) {
            val players = NativeRoutes.parse(dump(output), devices, 10123, 20, 2)
            assertEquals(-1, players.getJSONObject(0).getInt("actual"))
        }
    }

    @Test fun `inactive clients are not counted and unsupported dump fails explicitly`() {
        assertEquals(0, NativeRoutes.parse(dump("AUDIO_DEVICE_OUT_SPEAKER, @:", state = "Inactive"), devices, 10123, 20, 2).length())
        assertThrows(IllegalStateException::class.java) {
            NativeRoutes.parse("Permission Denial", devices, 10123, 20, 2)
        }
    }
}
