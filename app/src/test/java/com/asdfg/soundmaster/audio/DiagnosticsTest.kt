package com.asdfg.soundmaster.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsTest {
    @Test fun `exports redact injected key material pairing codes tokens and Bluetooth addresses`() {
        val token = "ab".repeat(32)
        val text = """failure token=$token pairing_code=123456 device 12:34:56:78:9A:BC
            -----BEGIN RSA PRIVATE KEY-----
            SECRET_KEY_BYTES
            -----END RSA PRIVATE KEY-----"""
        val safe = RoutingDiagnostics.sanitize(text)
        for (secret in listOf(token, "123456", "12:34:56:78:9A:BC", "SECRET_KEY_BYTES")) {
            assertFalse("Secret was exported: $secret", safe.contains(secret))
        }
    }

    @Test fun `stopped report does not pretend that capture or real hardware was tested`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = RoutingDiagnostics.report(context)
        assertTrue(report.contains("MediaProjection and AudioRecord not used"))
        assertTrue(report.contains("No physical Bluetooth verification"))
        assertTrue(report.contains("originalPlayerRoutes"))
        assertTrue(report.contains("androidReportsForeground"))
    }
}
