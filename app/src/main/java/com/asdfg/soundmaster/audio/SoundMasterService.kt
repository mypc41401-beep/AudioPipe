package com.asdfg.soundmaster.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.adb.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the shell policy independently of the Activity. Never captures or replays audio. */
class SoundMasterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var session: RouteSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RoutingDiagnostics.record("Routing foreground service created; capture/replay disabled")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL,
            "Audio routing", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Starting audio policy…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (task != null) return START_NOT_STICKY
        val pkg = intent?.getStringExtra("package")
        val deviceId = intent?.getIntExtra("device", -1) ?: -1
        if (pkg.isNullOrBlank() || deviceId < 0) {
            mutableState.value = RoutingState(message = "Stopped: select an app and a connected Bluetooth output.")
            stopSelf()
            return START_NOT_STICKY
        }
        val deviceName = intent?.getStringExtra("deviceName") ?: "Bluetooth"
        mutableState.value = RoutingState(busy = true, packageName = pkg, deviceId = deviceId,
            deviceName = deviceName, message = "Checking ADB audio-policy support…")
        RoutingDiagnostics.record("Start requested: $pkg -> deviceId=$deviceId")
        task = scope.launch { runPolicy(pkg, deviceId, deviceName) }
        return START_NOT_STICKY // no replaying stale MediaProjection grants or phantom running state
    }

    private suspend fun runPolicy(pkg: String, deviceId: Int, deviceName: String) {
        var finalMessage = "Stopped. Android default audio routing restored."
        val route = RouteSession(applicationContext)
        session = route
        try {
            val uid = packageManager.getApplicationInfo(pkg, 0).uid
            require(uid >= 10000) { "Select an ordinary media app, not a system service." }
            val oldOp = ShellExecutor.getInstance(this).commandSync(
                "appops get ${RouteSession.quote(pkg)} PLAY_AUDIO").getOrThrow()
            check(!oldOp.contains("deny", true) && !oldOp.contains("ignore", true)) {
                "Selected app playback is blocked by PLAY_AUDIO. Stop and tap Restore selected app playback, then Start again."
            }
            val actualName = route.start(uid, deviceId, simultaneous = true)
            val installed = RoutingState(active = true, packageName = pkg, deviceId = deviceId,
                deviceName = actualName, message = "Policy accepted. Play $pkg and a second app to inspect their actual outputs.")
            mutableState.value = installed
            RoutingDiagnostics.record("Native UID policy accepted: uid=$uid, deviceId=$deviceId")
            updateNotification(installed.message)
            var mismatchSince = 0L
            var lastMessage = ""
            while (currentCoroutineContext().isActive) {
                val observation = route.observe()
                check(observation.getBoolean("devicesAvailable")) {
                    "Bluetooth or speaker disconnected. Reconnect the device and start again."
                }
                val evidence = RouteEvidence.from(observation, uid)
                RoutingDiagnostics.observe(observation)
                val mismatch = evidence.wrong > 0 || evidence.unknown > 0
                val now = android.os.SystemClock.elapsedRealtime()
                if (mismatch && mismatchSince == 0L) mismatchSince = now
                if (!mismatch) mismatchSince = 0L
                check(mismatchSince == 0L || now - mismatchSince < 6500) {
                    "ROM did not keep the requested outputs: ${evidence.wrong} wrong, ${evidence.unknown} unknown. Split routing is unsupported or overridden with these devices."
                }
                val message = when {
                    mismatch -> "Waiting for actual output routes (${evidence.wrong} wrong, ${evidence.unknown} unknown)…"
                    evidence.bothObserved -> "Observed by Android: $pkg → $actualName; ${evidence.others} other media player(s) → phone speaker. Check both speakers by listening."
                    else -> "Policy accepted; split not yet observed. Selected app: ${evidence.selected} active player(s); other apps: ${evidence.others}. Play both apps."
                }
                mutableState.value = installed.copy(message = message, bothObserved = evidence.bothObserved)
                if (message != lastMessage) {
                    RoutingDiagnostics.record(message)
                    updateNotification(message)
                }
                lastMessage = message
                delay(2500)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finalMessage = "Stopped: ${error.message?.take(600)}\nAndroid default routing restored; split audio is not active."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val acknowledged = route.stopAndAwait()
                route.close()
                session = null
                if (!acknowledged) finalMessage = "Stopped controlling audio; native policy cleanup was not acknowledged. Reconnect ADB and export diagnostics."
                mutableState.value = RoutingState(packageName = pkg, deviceId = deviceId,
                    deviceName = deviceName, message = finalMessage)
                RoutingDiagnostics.record(finalMessage)
                stopSelf()
            }
        }
    }

    private fun notification(message: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SoundMasterService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("AudioPipe audio policy")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Stop", stop).build()
    }

    private fun updateNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        RoutingDiagnostics.record("Routing foreground service destroyed; closing shell control socket")
        if (task?.isActive == true && running) mutableState.value = mutableState.value.copy(active = false, busy = true,
            message = "Stopping the native audio policy…")
        task?.cancel()
        session?.cancelStart()
        scope.cancel()
        if (task == null) mutableState.value = mutableState.value.copy(active = false, busy = false)
        super.onDestroy()
    }

    data class RoutingState(
        val active: Boolean = false,
        val busy: Boolean = false,
        val packageName: String? = null,
        val deviceId: Int = -1,
        val deviceName: String = "",
        val message: String = "Stopped. Select the music app and its Bluetooth output. Other media will use the phone speaker.",
        val bothObserved: Boolean = false
    )

    companion object {
        const val ACTION_STOP = "com.asdfg.soundmaster.STOP_ROUTING"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "soundmaster_service"
        private val mutableState = MutableStateFlow(RoutingState())
        val state: StateFlow<RoutingState> = mutableState
        val running: Boolean get() = state.value.active || state.value.busy
    }
}
