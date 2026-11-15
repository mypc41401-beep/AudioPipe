package com.asdfg.soundmaster.adb

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AdbRecoveryLoopTest {
    @Test fun retriesAreBoundedThenWaitForAServiceEvent() = runTest {
        var attempts=0;val waits=mutableListOf<Long?>()
        val loop=AdbRecoveryLoop(backgroundScope,{true},{attempts++;Result.failure(IOException("offline"))},
            {_,delay->waits.add(delay)},heartbeatMs=100,backoffMs=listOf(10,20))
        loop.start();runCurrent();assertEquals(1,attempts)
        advanceTimeBy(10);runCurrent();assertEquals(2,attempts)
        advanceTimeBy(20);runCurrent();assertEquals(3,attempts);assertNull(waits.last())
        advanceTimeBy(10_000);runCurrent();assertEquals(3,attempts)
        loop.wake();runCurrent();assertEquals(4,attempts);loop.stop()
    }
    @Test fun healthyConnectionChecksAreInfrequentAndCancelledWithTheOwner() = runTest {
        var attempts=0;val loop=AdbRecoveryLoop(backgroundScope,{true},{attempts++;Result.success(Unit)},{_,_->},heartbeatMs=30_000)
        loop.start();runCurrent();assertEquals(1,attempts)
        advanceTimeBy(29_999);runCurrent();assertEquals(1,attempts)
        advanceTimeBy(1);runCurrent();assertEquals(2,attempts)
        loop.stop();advanceTimeBy(120_000);runCurrent();assertEquals(2,attempts)
    }
    @Test fun unavailableWifiPerformsNoConnectionAttemptsUntilAnEvent() = runTest {
        var wifi=false;var attempts=0
        val loop=AdbRecoveryLoop(backgroundScope,{wifi},{attempts++;Result.success(Unit)},{_,_->})
        loop.start();runCurrent();advanceTimeBy(120_000);runCurrent();assertEquals(0,attempts)
        wifi=true;loop.wake();runCurrent();assertEquals(1,attempts);loop.stop()
    }
    @Test fun unreadableIdentityDoesNotCauseRepeatedAuthenticationAttempts() = runTest {
        var attempts=0
        val loop=AdbRecoveryLoop(backgroundScope,{true},{attempts++;Result.failure(SavedAdbIdentityException())},{_,_->})
        loop.start();runCurrent();advanceTimeBy(300_000);runCurrent();assertEquals(1,attempts)
        loop.stop()
    }

}
