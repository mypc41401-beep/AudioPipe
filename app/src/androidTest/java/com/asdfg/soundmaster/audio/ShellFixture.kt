package com.asdfg.soundmaster.audio

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** UiAutomation Runtime.exec(String) does not interpret shell syntax. Feed a real sh via stdin.
 * Test APK only; production ADB already executes shell commands through adbd's shell service. */
object ShellFixture {
    fun run(script: String): String {
        val fds = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRwe("/system/bin/sh")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write((script + "\nexit\n").toByteArray()) }
            val stdout = ParcelFileDescriptor.AutoCloseInputStream(fds[0]).bufferedReader().use { it.readText() }
            val stderr = ParcelFileDescriptor.AutoCloseInputStream(fds[2]).bufferedReader().use { it.readText() }
            return stdout + if (stderr.isBlank()) "" else "\n$stderr"
        } finally { fds.forEach { runCatching { it.close() } } }
    }
}
