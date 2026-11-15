package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AdbModernDiscoveryTest {
    @Test fun failedOrStoppedWatchCannotBeMistakenForAnActiveSubscription() {
        val manager=mock<NsdManager>();val context=mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        lateinit var discovery:NsdManager.DiscoveryListener
        doAnswer { discovery=it.getArgument(4);null }.whenever(manager)
            .discoverServices(any(),any(),any<NetworkRequest>(),any<Executor>(),any())
        val first=AdbMdns.watch(context,AdbMdns.TLS_CONNECT,{},{},{})
        assertTrue(first.isActive)
        discovery.onStartDiscoveryFailed(AdbMdns.TLS_CONNECT,3)
        assertFalse("A failed watch must allow the next recovery attempt to replace it",first.isActive)
        val replacement=AdbMdns.watch(context,AdbMdns.TLS_CONNECT,{},{},{})
        assertTrue(replacement.isActive)
        discovery.onDiscoveryStopped(AdbMdns.TLS_CONNECT)
        assertFalse(replacement.isActive)
        replacement.close();first.close()
    }
    @Test fun localUpdatesDeliverChangedPortsAndIgnoreRemoteAndLateCallbacks() {
        val manager=mock<NsdManager>();val context=mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        lateinit var discovery:NsdManager.DiscoveryListener
        val callbacks=mutableListOf<NsdManager.ServiceInfoCallback>()
        doAnswer { discovery=it.getArgument(4);discovery.onDiscoveryStarted(AdbMdns.TLS_CONNECT);null }
            .whenever(manager).discoverServices(any(),any(),any<NetworkRequest>(),any<Executor>(),any())
        doAnswer { callbacks.add(it.getArgument(2));null }.whenever(manager).registerServiceInfoCallback(any(),any(),any())
        val ports=mutableListOf<Int>();val lost=mutableListOf<Int>()
        val watch=AdbMdns.watch(context,AdbMdns.TLS_CONNECT,ports::add,lost::add,{})
        fun info(name:String,host:String,port:Int)=NsdServiceInfo().apply {
            serviceName=name;serviceType=AdbMdns.TLS_CONNECT;this.host=InetAddress.getByName(host);this.port=port }
        val remote=info("remote","192.0.2.1",31001)
        discovery.onServiceFound(remote);callbacks.last().onServiceUpdated(remote);assertTrue(ports.isEmpty())
        val local=info("local","127.0.0.1",32002)
        discovery.onServiceFound(local);val first=callbacks.last();first.onServiceUpdated(local)
        local.port=33003;first.onServiceUpdated(local);first.onServiceUpdated(local)
        assertEquals(listOf(32002,33003),ports)
        first.onServiceLost();assertEquals(listOf(33003),lost)
        discovery.onServiceFound(local);val replacement=callbacks.last()
        local.port=34004;replacement.onServiceUpdated(local)
        local.port=35005;first.onServiceUpdated(local)
        assertEquals(listOf(32002,33003,34004),ports)
        watch.close();local.port=36006;replacement.onServiceUpdated(local)
        assertEquals(3,ports.size)
        verify(manager,atLeastOnce()).unregisterServiceInfoCallback(any())
        verify(manager,atLeastOnce()).stopServiceDiscovery(discovery)
    }
    @Test fun oneShotModernDiscoveryUnregistersTheServiceInfoCallback() = runTest {
        val manager=mock<NsdManager>();val context=mock<Context>()
        whenever(context.getSystemService(NsdManager::class.java)).thenReturn(manager)
        val local=NsdServiceInfo().apply { serviceName="own-adbd";serviceType=AdbMdns.TLS_CONNECT
            host=InetAddress.getLoopbackAddress();port=32002 }
        doAnswer { it.getArgument<NsdManager.ServiceInfoCallback>(2).onServiceUpdated(local);null }
            .whenever(manager).registerServiceInfoCallback(any(),any(),any())
        doAnswer { val d=it.getArgument<NsdManager.DiscoveryListener>(4);d.onDiscoveryStarted(AdbMdns.TLS_CONNECT);d.onServiceFound(local);null }
            .whenever(manager).discoverServices(any(),any(),any<NetworkRequest>(),any<Executor>(),any())
        assertEquals(32002,AdbMdns.discover(context,AdbMdns.TLS_CONNECT))
        verify(manager,atLeastOnce()).unregisterServiceInfoCallback(any())
        verify(manager,atLeastOnce()).stopServiceDiscovery(any())
    }
}
