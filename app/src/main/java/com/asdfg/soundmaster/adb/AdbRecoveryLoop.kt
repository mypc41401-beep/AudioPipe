package com.asdfg.soundmaster.adb

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** In-use liveness checks + a bounded retry burst. After exhaustion only external events wake it. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AdbRecoveryLoop(
    private val scope: CoroutineScope,
    private val wifiAvailable: () -> Boolean,
    private val attempt: suspend () -> Result<Unit>,
    private val waiting: (Throwable?, Long?) -> Unit,
    private val fatal: (Throwable) -> Boolean = { it is SavedAdbIdentityException },
    private val heartbeatMs: Long = 30_000,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000)
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var failures = 0
            while (isActive) {
                if (!wifiAvailable()) {
                    waiting(null, null)
                    signals.receive()
                    failures = 0
                    continue
                }
                val result = attempt()
                ensureActive()
                if (result.isSuccess) {
                    failures = 0
                    pauseOrSignal(heartbeatMs)
                } else {
                    val error = checkNotNull(result.exceptionOrNull())
                    val delay = if (!fatal(error)) backoffMs.getOrNull(failures++) else null
                    waiting(error, delay)
                    if (delay == null) { signals.receive(); failures = 0 }
                    else if (pauseOrSignal(delay)) failures = 0
                }
            }
        }
    }
    fun wake() { signals.trySend(Unit) }
    fun stop() { job?.cancel(); job = null; while (signals.tryReceive().isSuccess) { } }
    private suspend fun pauseOrSignal(delayMs: Long): Boolean = select {
        signals.onReceive { true }
        onTimeout(delayMs) { false }
    }
}
