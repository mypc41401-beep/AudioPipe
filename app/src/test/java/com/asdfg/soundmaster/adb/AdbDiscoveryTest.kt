package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbDiscoveryTest {
    @Test fun `discovery failure reports the error and stops discovery`() = runTest {
        val manager = mock<NsdManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        doAnswer {
            it.getArgument<NsdManager.DiscoveryListener>(2)
                .onStartDiscoveryFailed(AdbMdns.TLS_CONNECT, 3)
            null
        }.whenever(manager).discoverServices(any(), any(), any<NsdManager.DiscoveryListener>())
        val result = runCatching { AdbMdns.discover(context, AdbMdns.TLS_CONNECT) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("error 3"))
        verify(manager, atLeastOnce()).stopServiceDiscovery(any())
    }

    @Test fun `missing service has a bounded timeout and manual fallback`() = runTest {
        val manager = mock<NsdManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        val result = runCatching { AdbMdns.discover(context, AdbMdns.TLS_CONNECT, 50) }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("timed out"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("manually"))
        verify(manager, atLeastOnce()).stopServiceDiscovery(any())
    }

    @Test fun `local service is accepted and remote service ignored`() = runTest {
        val manager = mock<NsdManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        val local = NsdServiceInfo().apply { host = InetAddress.getLoopbackAddress(); port = 37123 }
        doAnswer {
            it.getArgument<NsdManager.ResolveListener>(1).onServiceResolved(local); null
        }.whenever(manager).resolveService(any(), any())
        doAnswer {
            val listener = it.getArgument<NsdManager.DiscoveryListener>(2)
            listener.onDiscoveryStarted(AdbMdns.TLS_CONNECT)
            listener.onServiceFound(local)
            null
        }.whenever(manager).discoverServices(any(), any(), any<NsdManager.DiscoveryListener>())
        assertEquals(37123, AdbMdns.discover(context, AdbMdns.TLS_CONNECT))
        local.host = InetAddress.getByName("192.0.2.1")
        assertTrue(runCatching { AdbMdns.discover(context, AdbMdns.TLS_CONNECT, 50) }.isFailure)
    }

    @Test fun `invalid pairing never persists a paired flag or leaves UI busy`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val shell = ShellExecutor(context)
        assertTrue(shell.pair(0, "123456").isFailure)
        assertFalse(shell.isSetUp)
        assertFalse(shell.state.value.busy)
        assertTrue(shell.pair(32123, "123").isFailure)
        assertFalse(prefs.getBoolean("adb_paired", false))
    }
}
