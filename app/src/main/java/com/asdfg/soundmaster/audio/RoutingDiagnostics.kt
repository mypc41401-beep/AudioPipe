package com.asdfg.soundmaster.audio

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.asdfg.soundmaster.adb.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Opt-in local export. No uploads, ADB identities, pairing messages, MACs or installed-app lists. */
object RoutingDiagnostics {
    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processStarted = SystemClock.elapsedRealtime()
    @Volatile private var context: Context? = null
    @Volatile private var lastObservation: String? = null
    @Volatile private var observationTime: String? = null
    @Volatile private var initialized = false

    fun initialize(app: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            context = app.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    // A short synchronous write only on an already-fatal thread, before delegating
                    // to Android's original crash handler. Never consume or conceal the crash.
                    val trace = error.stackTrace.take(12).joinToString("\n") { it.toString() }
                    File(app.filesDir, "routing-last-crash.txt").writeText(sanitize(
                        "${Instant.now()} ${error.javaClass.name}: ${error.message}\n$trace"))
                }
                previous?.uncaughtException(thread, error)
            }
            record("Application process started")
        }
    }

    fun sanitize(text: String): String = text
        .replace(Regex("-----BEGIN[^-]*PRIVATE KEY-----.*?-----END[^-]*PRIVATE KEY-----", RegexOption.DOT_MATCHES_ALL), "[private key redacted]")
        .replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b"), "[MAC redacted]")
        .replace(Regex("(?i)\\b[0-9a-f]{64}\\b"), "[token redacted]")
        .replace(Regex("(?i)(token|password|pairing[ _-]*code|private[ _-]*key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        .take(65536)

    fun record(event: String) {
        val app = context ?: return
        val line = "${Instant.now()} ${sanitize(event).take(1200)}\n"
        scope.launch {
            synchronized(lock) {
                runCatching {
                    val file = File(app.filesDir, "routing-events.txt")
                    val old = if (file.exists()) file.readText().takeLast(48000) else ""
                    file.writeText((old + line).takeLast(64000))
                }
            }
        }
    }

    fun observe(status: JSONObject) {
        // Backend reports only numeric UIDs and port IDs; never store its authentication handshake.
        val safe = JSONObject().put("devicesAvailable", status.optBoolean("devicesAvailable"))
            .put("selectedDevice", status.optInt("selectedDevice", -1))
            .put("speakerDevice", status.optInt("speakerDevice", -1))
            .put("players", status.optJSONArray("players") ?: JSONArray()).toString()
        lastObservation = safe
        observationTime = Instant.now().toString()
    }

    @Suppress("DEPRECATION")
    fun report(app: Context): String {
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val state = SoundMasterService.state.value
        val runtime = Runtime.getRuntime()
        val foreground = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100).firstOrNull { it.service.className == SoundMasterService::class.java.name }
        val devices = JSONArray()
        for (d in manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) devices.put(JSONObject()
            .put("id", d.id).put("type", d.type).put("name", sanitize(d.productName.toString()))
            .put("sampleRates", JSONArray(d.sampleRates.toList())).put("isSink", d.isSink))
        val json = JSONObject().put("reportVersion", 1).put("createdAt", Instant.now())
            .put("appVersion", "1.2-uid-routing-test").put("androidSdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("adbShellConnected", ShellExecutor.getInstance(app).isConnected)
            .put("service", JSONObject().put("policyActive", state.active).put("starting", state.busy)
                .put("androidReportsForeground", foreground?.foreground ?: false)
                .put("selectedPackage", state.packageName ?: "none").put("selectedDeviceId", state.deviceId)
                .put("status", sanitize(state.message)).put("bothRoutesObserved", state.bothObserved))
            .put("audioOutputs", devices)
            .put("capture", "MediaProjection and AudioRecord not used; AudioPipe creates no replay AudioTrack")
            .put("originalPlayerRoutes", lastObservation?.let { JSONObject(it) } ?: "No shell observation yet")
            .put("routeObservationAt", observationTime ?: "none")
            .put("audioMode", manager.mode)
            .put("performanceSnapshot", JSONObject().put("processCpuMillis", Process.getElapsedCpuTime())
                .put("processUptimeMillis", SystemClock.elapsedRealtime() - processStarted)
                .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()))
            .put("limits", "Framework-reported original player device IDs are not an acoustic test. No physical Bluetooth verification has been performed by the developer.")
        val events = synchronized(lock) {
            File(app.filesDir, "routing-events.txt").takeIf { it.exists() }?.readText().orEmpty().takeLast(64000)
        }
        val crash = File(app.filesDir, "routing-last-crash.txt").takeIf { it.exists() }?.readText().orEmpty()
        return sanitize(json.toString(2)) + "\n\nState transitions / errors:\n" + sanitize(events) +
            "\nLast recorded fatal error (if any):\n" + sanitize(crash)
    }
}
