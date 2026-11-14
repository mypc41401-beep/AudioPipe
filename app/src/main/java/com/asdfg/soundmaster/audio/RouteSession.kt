package com.asdfg.soundmaster.audio

import android.content.Context
import android.os.Process
import com.asdfg.soundmaster.adb.ShellExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/** One authenticated, loopback-only control connection owned by the foreground service. */
class RouteSession(private val context: Context,
    private val execute: suspend (String) -> Result<String> = { ShellExecutor.getInstance(context).commandSync(it) }
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var listener: ServerSocket? = null
    private lateinit var input: BufferedReader
    private lateinit var output: PrintWriter

    suspend fun start(uid: Int, deviceId: Int, simultaneous: Boolean): String {
        val original = execute("settings get system multi_audio_focus_enabled").getOrThrow().trim()
        require(original == "0" || original == "1" || original == "null") {
            "Could not read Android multi-audio-focus state: $original"
        }
        val originalFocus = original == "1"
        if (simultaneous && !originalFocus) {
            // Recover even if the shell helper is killed without running its finally block.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(RESTORE_FOCUS, true).commit()
        }
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        listener = server
        server.soTimeout = 500
        try {
            execute(launchCommand(context.applicationInfo.sourceDir, server.localPort, token)).getOrThrow()
            val deadline = System.nanoTime() + 15_000_000_000L
            while (socket == null && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                val candidate = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                try {
                    candidate.soTimeout = 4000
                    val reader = BufferedReader(InputStreamReader(candidate.getInputStream(), Charsets.UTF_8))
                    val hello = JSONObject(readBounded(reader))
                    val authorized = hello.optString("type") == "hello"
                        && hello.optInt("uid") in listOf(2000, 0)
                        && MessageDigest.isEqual(token.toByteArray(), hello.optString("token").toByteArray())
                    if (!authorized) { candidate.close(); continue }
                    socket = candidate
                    input = reader
                    output = PrintWriter(candidate.getOutputStream().writer(Charsets.UTF_8), true)
                } catch (_: Exception) { candidate.close() }
            }
            check(socket != null) {
                "ADB helper did not start within 15 seconds. This ROM may block app_process or reading the installed APK."
            }
            output.println(JSONObject().put("token", token).put("appUid", uid)
                .put("deviceId", deviceId).put("simultaneous", simultaneous)
                .put("originalFocus", originalFocus).toString())
            val result = response()
            check(result.optString("type") == "installed") { "Audio policy was not installed: $result" }
            return result.getString("device")
        } finally {
            server.close()
            listener = null
            tokenBytes.fill(0)
        }
    }

    fun observe(): JSONObject {
        output.println("status")
        return response().also { check(it.optString("type") == "status") { "Unexpected policy response: $it" } }
    }

    private fun response(): JSONObject = JSONObject(readBounded(input)).also {
        if (it.optString("type") == "error" || it.optString("type") == "cleanupError") {
            error(it.optString("message", "Audio policy failed"))
        }
    }

    fun close() {
        // EOF also removes the policy if the app process is killed or the task is stopped.
        runCatching { socket?.close() }
        runCatching { listener?.close() }
        socket = null
        listener = null
    }

    companion object {
        const val PREFS = "routing_settings"
        private const val RESTORE_FOCUS = "restore_multi_focus"
        private const val ENTRY = "com.asdfg.soundmaster.audio.RouteDaemon"

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun launchCommand(apk: String, port: Int, token: String): String {
            require(port in 1..65535 && token.matches(Regex("[0-9a-f]{64}")))
            return "(trap '' HUP; CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin " +
                "$ENTRY $port $token </dev/null >/dev/null 2>&1 &)"
        }

        private fun readBounded(reader: BufferedReader): String {
            val line = StringBuilder()
            while (line.length < 65536) {
                val c = reader.read()
                check(c >= 0) { "ADB audio helper disconnected; Android default routing is restored." }
                if (c == 10) return line.toString()
                line.append(c.toChar())
            }
            error("Audio helper response exceeded the limit")
        }

        suspend fun restoreFocusIfNeeded(context: Context): Result<Unit> = runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(RESTORE_FOCUS, false)) return@runCatching
            val reply = ShellExecutor.getInstance(context).commandSync(
                "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin $ENTRY restore-focus 0"
            ).getOrThrow()
            check(reply.contains("FOCUS_RESTORED")) { "Multi-audio-focus restoration failed: ${reply.take(200)}" }
            prefs.edit().remove(RESTORE_FOCUS).commit()
        }
    }
}
