package com.asdfg.soundmaster.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbReconnectTest {
    private val identity = object : AdbIdentity {
        override val adbPublicKey = byteArrayOf(1,2,3)
        override val sslContext: SSLContext get() = error("No fake crypto used by this controller test")
        override fun sign(data: ByteArray?) = error("No signature expected")
    }
    private class Connection(var broken: Boolean=false, val commandFailure: Boolean=false) : AdbConnection {
        override var isConnected=false
        val commands=mutableListOf<String>()
        override fun connect() { if(broken) throw ConnectException("stale"); isConnected=true }
        override fun shell(command:String):String {
            commands.add(command)
            if(broken || (commandFailure && command!="id -u")) { isConnected=false;throw IOException("link lost") }
            return if(command=="id -u") "2000" else "done"
        }
        override fun close() { isConnected=false }
    }
    private fun context():Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("adb_settings",0).edit().clear().putBoolean("adb_paired",true)
            .putString("adbkey","unchanged encrypted identity").putInt("last_port",31001).commit()
    }
    @Test fun stalePortDiscoversCurrentPortWithoutReplacingIdentity() = runTest {
        val ctx=context();val ports=mutableListOf<Int>();var discoveries=0
        val shell=ShellExecutor(ctx,ReconnectHooks({ discoveries++;32002 }, { port,key ->
            assertSame(identity,key);ports.add(port);Connection(broken=port==31001)
        },{identity},backgroundScope))
        shell.autoConnect().getOrThrow()
        assertEquals(listOf(31001,32002),ports);assertEquals(1,discoveries)
        assertTrue(shell.isConnected);assertEquals(32002,shell.getCachedPort())
        assertEquals("unchanged encrypted identity",ctx.getSharedPreferences("adb_settings",0).getString("adbkey",null))
        assertTrue(shell.isSetUp);shell.disconnect()
    }
    @Test fun staleSocketIsProbedAndRecoveredBeforeExecutingACommand() = runTest {
        val ctx=context();val created=mutableListOf<Connection>();var port=31001
        val shell=ShellExecutor(ctx,ReconnectHooks({32002},{ p,_ ->
            Connection(broken=p!=port).also { created.add(it) }
        },{identity},backgroundScope))
        shell.autoConnect().getOrThrow();created.last().broken=true;port=32002
        assertEquals("done",shell.commandSync("echo requested_once").getOrThrow())
        assertTrue(shell.isConnected)
        assertEquals(1,created.sumOf { c->c.commands.count { it=="echo requested_once" } })
        shell.disconnect()
    }
    @Test fun failedMutatingCommandIsNotReplayed() = runTest {
        val ctx=context();val c=Connection(commandFailure=true)
        val shell=ShellExecutor(ctx,ReconnectHooks({31001},{_,_->c},{identity},backgroundScope))
        shell.autoConnect().getOrThrow()
        val result=shell.commandSync("settings put system example 1")
        assertTrue(result.isFailure);assertFalse(shell.isConnected)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not replayed"))
        assertEquals(1,c.commands.count { it.startsWith("settings put") });shell.disconnect()
    }
    @Test fun ownerRecoveryWakesOnNewPortAndWifiReturnThenStopsWithoutOwners() = runTest {
        val ctx=context();var availablePort=-1;var attempts=0
        val shell=ShellExecutor(ctx,ReconnectHooks({ if(availablePort<0) throw IOException("no service");availablePort },
            {port,_->attempts++;Connection(broken=port!=availablePort)},{identity},backgroundScope))
        val owner=Any();shell.setAutoReconnectOwner(owner,true);runCurrent()
        assertFalse(shell.isConnected)
        availablePort=32002;shell.connectionPortFound(32002);runCurrent()
        assertTrue(shell.isConnected)
        shell.wifiChanged(false);runCurrent();assertFalse(shell.isConnected)
        val pausedAttempts=attempts;advanceTimeBy(60_000);runCurrent();assertEquals(pausedAttempts,attempts)
        availablePort=33003;shell.wifiChanged(true);shell.connectionPortFound(33003);runCurrent()
        assertTrue(shell.isConnected);assertEquals(33003,shell.getCachedPort())
        shell.setAutoReconnectOwner(owner,false)
        val stoppedAttempts=attempts;advanceTimeBy(180_000);runCurrent();assertEquals(stoppedAttempts,attempts)
        shell.disconnect()
    }
    @Test fun aNewExecutorUsesSavedKeyAndRecoversTheSavedStalePort() = runTest {
        val ctx=context();val first=ShellExecutor(ctx,ReconnectHooks({31001},{_,key->assertSame(identity,key);Connection()},{identity},backgroundScope))
        first.autoConnect().getOrThrow();first.disconnect()
        val next=ShellExecutor(ctx,ReconnectHooks({33003},{p,key->assertSame(identity,key);Connection(broken=p!=33003)},{identity},backgroundScope))
        next.autoConnect().getOrThrow();assertTrue(next.isConnected);assertTrue(next.isSetUp)
        assertEquals("unchanged encrypted identity",ctx.getSharedPreferences("adb_settings",0).getString("adbkey",null));next.disconnect()
    }
    @Test fun discoveryFailureRetainsPairingAndOffersManualConnectionFallback() = runTest {
        val ctx=context();val shell=ShellExecutor(ctx,ReconnectHooks({throw IOException("mDNS unavailable; enter connection port manually")},
            {_,_->Connection(broken=true)},{identity},backgroundScope))
        assertTrue(shell.autoConnect().isFailure);assertFalse(shell.isConnected);assertTrue(shell.isSetUp)
        assertFalse(shell.state.value.busy);assertTrue(shell.state.value.message.contains("manually"))
        shell.disconnect()
    }
    @Test fun unreadableSavedIdentityIsReportedWithoutDiscardingPairing() = runTest {
        val ctx=context();var creates=0
        val shell=ShellExecutor(ctx,ReconnectHooks({32002},{_,_->creates++;Connection()},
            {throw SavedAdbIdentityException()},backgroundScope))
        assertTrue(shell.autoConnect().exceptionOrNull() is SavedAdbIdentityException)
        assertTrue(shell.isSetUp);assertEquals(0,creates)
        assertTrue(shell.state.value.message.contains("NOT been replaced"));shell.disconnect()
    }
    @Test fun explicitCancelStopsRetriesUntilTheNextOwnerActivation() = runTest {
        val ctx=context();var creates=0
        val shell=ShellExecutor(ctx,ReconnectHooks({32002},{_,_->creates++;Connection()},{identity},backgroundScope))
        val owner=Any();shell.setAutoReconnectOwner(owner,true);runCurrent();assertTrue(shell.isConnected)
        shell.cancelCurrentOperation();val before=creates
        advanceTimeBy(120_000);runCurrent();assertEquals(before,creates);assertFalse(shell.isConnected)
        shell.setAutoReconnectOwner(owner,true);runCurrent();assertTrue(shell.isConnected)
        shell.setAutoReconnectOwner(owner,false);shell.disconnect()
    }

    @Test fun healthyBackgroundProbeDoesNotFlashDisconnectedOrDisableStart() = runTest {
        val ctx=context();val connection=Connection()
        val shell=ShellExecutor(ctx,ReconnectHooks({31001},{_,_->connection},{identity},backgroundScope))
        val owner=Any();shell.setAutoReconnectOwner(owner,true);runCurrent()
        assertTrue(shell.isConnected)
        val states=mutableListOf<ShellExecutor.ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { shell.state.collect { states.add(it) } }
        val probes=connection.commands.count { it=="id -u" }
        advanceTimeBy(30_000);runCurrent()
        assertTrue("A real controller probe must have occurred",connection.commands.count { it=="id -u" }>probes)
        assertTrue(states.isNotEmpty())
        assertTrue("Healthy liveness checks must preserve usable connected UI state",states.all { it.connected && !it.busy })
        shell.setAutoReconnectOwner(owner,false);shell.disconnect()
    }

}
