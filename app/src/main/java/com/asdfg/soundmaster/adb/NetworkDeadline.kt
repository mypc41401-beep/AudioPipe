package com.asdfg.soundmaster.adb

import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Closing a socket actually interrupts blocking TLS/IO; coroutine timeout alone does not. */
internal object NetworkDeadline {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AudioPipe-ADB-timeout").apply { isDaemon = true }
    }

    fun <T> run(timeoutMs: Int, close: () -> Unit, block: () -> T): T {
        val expired = AtomicBoolean(false)
        val deadline = scheduler.schedule({
            expired.set(true)
            close()
        }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            val result = block()
            if (expired.get()) throw SocketTimeoutException("ADB operation timed out")
            return result
        } catch (e: Exception) {
            if (expired.get()) throw SocketTimeoutException("ADB operation timed out").apply { initCause(e) }
            throw e
        } finally {
            deadline.cancel(false)
        }
    }
}
