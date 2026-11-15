package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AdbNetworkObserverTest {
    @Test fun overlappingWifiNetworksAreNotMistakenForACompleteDisconnect() {
        val manager=mock<ConnectivityManager>();val context=mock<Context>()
        whenever(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        whenever(manager.allNetworks).thenReturn(emptyArray())
        lateinit var cb:ConnectivityManager.NetworkCallback
        doAnswer { cb=it.getArgument(1);null }.whenever(manager).registerNetworkCallback(any<NetworkRequest>(),any<ConnectivityManager.NetworkCallback>())
        val states=mutableListOf<Boolean>();val observer=AdbNetworkObserver(context) { states.add(it) }
        val a=mock<Network>();val b=mock<Network>()
        cb.onAvailable(a);cb.onAvailable(b);cb.onLost(a);assertEquals(true,states.last())
        cb.onLost(b);assertEquals(false,states.last())
        observer.close();val count=states.size;cb.onAvailable(a);assertEquals(count,states.size)
        verify(manager).unregisterNetworkCallback(cb)
    }
    @Test fun localWifiDoesNotNeedInternetValidation() {
        val manager=mock<ConnectivityManager>();val context=mock<Context>();val wifi=mock<Network>()
        whenever(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        whenever(manager.allNetworks).thenReturn(arrayOf(wifi))
        val capabilities=mock<NetworkCapabilities>()
        whenever(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        whenever(manager.getNetworkCapabilities(wifi)).thenReturn(capabilities)
        val states=mutableListOf<Boolean>();val observer=AdbNetworkObserver(context) { states.add(it) }
        assertEquals(listOf(true),states);observer.close()
    }
}
