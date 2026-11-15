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
import android.os.SystemClock
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.adb.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/** Holds the shell policy independently of the Activity. Never captures or replays audio. */
class SoundMasterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var session: RouteSession? = null
    private var latestStartId = 0
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val repairRequested = AtomicBoolean(false)
    private var lastRepairRequest = -REPAIR_COOLDOWN_MS
    private val outputChanges = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { changes.trySend(Unit) }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { changes.trySend(Unit) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShellExecutor.getInstance(this).setAutoReconnectOwner(this, true)
        RoutingDiagnostics.record("Routing foreground service created; capture/replay disabled")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL,
            "Audio routing", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Starting audio policy…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outputChanges, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            if (task == null) stopSelfResult(startId)
            else {
                mutableState.value = mutableState.value.copy(active = false, busy = true,
                    message = "Stopping the native audio policy…")
                task?.cancel()
                session?.cancelStart()
                // Retain foreground ownership until finally completes bounded policy cleanup.
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPAIR) {
            if (task == null) { stopSelfResult(startId); return START_NOT_STICKY }
            if (task?.isActive != true || mutableState.value.busy || repairRequested.get()) return START_NOT_STICKY
            if (SystemClock.elapsedRealtime() - lastRepairRequest < REPAIR_COOLDOWN_MS) {
                mutableState.value = mutableState.value.copy(notice = "Repair cooldown: wait 30 seconds before another repair. Audio was not reset again.")
                return START_NOT_STICKY
            }
            lastRepairRequest = SystemClock.elapsedRealtime()
            repairRequested.set(true)
            mutableState.value = mutableState.value.copy(busy = true, notice = "Checking ADB before repairing AudioPipe's own policy…")
            changes.trySend(Unit)
            return START_NOT_STICKY
        }
        if (task != null) return START_NOT_STICKY // includes cancelled tasks still acknowledging cleanup
        val pkg = intent?.getStringExtra("package")
        val deviceId = intent?.getIntExtra("device", -1) ?: -1
        if (pkg.isNullOrBlank() || deviceId < 0) {
            mutableState.value = RoutingState(message = "Stopped: select an app and a connected Bluetooth output.")
            stopSelf()
            return START_NOT_STICKY
        }
        val deviceName = intent?.getStringExtra("deviceName") ?: "Bluetooth"
        stateOwner = this
        repairRequested.set(false)
        RoutingDiagnostics.clearObservation()
        mutableState.value = RoutingState(busy = true, packageName = pkg, deviceId = deviceId,
            deviceName = deviceName, message = "Checking ADB audio-policy support…")
        RoutingDiagnostics.record("Start requested: $pkg -> deviceId=$deviceId")
        val deviceType = intent?.getIntExtra("deviceType", -1) ?: -1
        val address = intent?.getStringExtra("deviceAddress").orEmpty()
        task = scope.launch { runPolicy(pkg, deviceId, deviceName, deviceType, address) }
        return START_NOT_STICKY // no replaying stale MediaProjection grants or phantom running state
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runPolicy(pkg: String, deviceId: Int, deviceName: String, deviceType: Int, address: String) {
        var resolvedDeviceId = deviceId
        var finalMessage = "Stopped. Android default audio routing restored."
        var route = RouteSession(applicationContext)
        var repairs = 0
        var snapshots = 0
        session = route
        try {
            val uid = packageManager.getApplicationInfo(pkg, 0).uid
            require(uid >= 10000) { "Select an ordinary media app, not a system service." }
            val oldOp = ShellExecutor.getInstance(this).commandSync(
                "appops get ${RouteSession.quote(pkg)} PLAY_AUDIO").getOrThrow()
            check(!oldOp.contains("deny", true) && !oldOp.contains("ignore", true)) {
                "Selected app playback is blocked by PLAY_AUDIO. Stop and tap Restore selected app playback, then Start again."
            }
            suspend fun install(): RoutingState {
                val name = route.start(uid, deviceId, simultaneous = true,
                    deviceType = deviceType, deviceAddress = address, deviceName = deviceName)
                resolvedDeviceId = route.actualDeviceId
                val installed = RoutingState(active = true, packageName = pkg, deviceId = resolvedDeviceId,
                    deviceName = name, message = "Policy accepted; split not yet observed. Play both apps.",
                    policyGeneration = repairs + 1, repairCount = repairs,
                    notice = if (repairs > 0) "AudioPipe policy recreated after acknowledged cleanup. Check both speakers; source-app buffers were not reset." else "")
                mutableState.value = installed
                RoutingDiagnostics.record("Native UID policy accepted: uid=$uid, requestedPort=$deviceId, actualPort=$resolvedDeviceId, generation=${repairs + 1}")
                updateNotification(installed.message)
                return installed
            }
            var installed = install()
            while (changes.tryReceive().isSuccess) { } // initial output enumeration is already reflected
            var mismatchSince = 0L
            var lastMessage = ""
            var settledAt = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive) {
                if (repairRequested.getAndSet(false)) {
                    // Never tear down a working policy merely because ADB is temporarily off.
                    val ready = ShellExecutor.getInstance(this).commandSync("id -u")
                    if (ready.isFailure) {
                        installed = installed.copy(notice = "Repair postponed; existing policy retained. ${ShellExecutor.describeError(ready.exceptionOrNull()!!)}")
                        RoutingDiagnostics.record(installed.notice)
                    } else {
                        check(packageManager.getApplicationInfo(pkg, 0).uid == uid) { "Selected application's UID changed; select it again." }
                        mutableState.value = installed.copy(active = false, busy = true, bothObserved = false,
                            notice = "Repairing AudioPipe policy once. Android default output may be heard briefly.")
                        RoutingDiagnostics.clearObservation()
                        check(route.stopAndAwait()) { "Old policy cleanup was not acknowledged; refusing to create an overlapping policy." }
                        route.close()
                        currentCoroutineContext().ensureActive()
                        route = RouteSession(applicationContext)
                        session = route
                        repairs++
                        installed = install()
                        mismatchSince = 0L
                        settledAt = SystemClock.elapsedRealtime()
                        lastMessage = ""
                    }
                }
                val observation = route.observe()
                snapshots++
                check(observation.getBoolean("devicesAvailable")) {
                    "Bluetooth or speaker disconnected. Reconnect the device and start again."
                }
                val evidence = RouteEvidence.from(observation, uid)
                RoutingDiagnostics.observe(observation)
                val mismatch = evidence.wrong > 0 || evidence.unknown > 0
                val now = SystemClock.elapsedRealtime()
                if (mismatch && mismatchSince == 0L) mismatchSince = now
                if (!mismatch) mismatchSince = 0L
                check(mismatchSince == 0L || now - mismatchSince < 6500) {
                    "ROM did not keep the requested outputs: ${evidence.wrong} wrong, ${evidence.unknown} unknown. Split routing is unsupported or overridden with these devices."
                }
                val message = when {
                    mismatch -> "Waiting for actual output routes (${evidence.wrong} wrong, ${evidence.unknown} unknown)…"
                    evidence.bothObserved -> "Last observed by Android: $pkg → ${installed.deviceName}; ${evidence.others} other media player(s) → phone speaker. Verify by listening."
                    else -> "Policy accepted; split not yet observed. Selected app: ${evidence.selected} active player(s); other apps: ${evidence.others}. Play both apps."
                }
                installed = installed.copy(message = message, bothObserved = evidence.bothObserved,
                    busy = false, snapshotCount = snapshots, observedAt = System.currentTimeMillis(), observedElapsed = now)
                mutableState.value = installed
                if (message != lastMessage) {
                    RoutingDiagnostics.record(message)
                    updateNotification(message)
                }
                lastMessage = message
                // No periodic policy installation/focus writes. Full native dumps are expensive;
                // only startup/mismatch gets a short bounded settling window.
                val next = if (mismatch || (!evidence.bothObserved && now - settledAt < 10_000))
                    3500L else OBSERVATION_INTERVAL_MS
                val event = select<Boolean> {
                    changes.onReceive { true }
                    onTimeout(next) { false }
                }
                // Coalesce codec/device event bursts. Explicit repair bypasses this short delay.
                if (event && !repairRequested.get()) delay((5000L - (SystemClock.elapsedRealtime() - now)).coerceAtLeast(0))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finalMessage = "Stopped: ${error.message?.take(600)}\nAndroid default routing restored; split audio is not active."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val acknowledged = route.stopAndAwait()
                route.close()
                if (!acknowledged) finalMessage = "Stopped controlling audio; native policy cleanup was not acknowledged. Reconnect ADB and export diagnostics."
                withContext(Dispatchers.Main.immediate) {
                    session = null
                    task = null
                    if (stateOwner === this@SoundMasterService) {
                        RoutingDiagnostics.clearObservation()
                        mutableState.value = RoutingState(packageName = pkg, deviceId = resolvedDeviceId,
                            deviceName = deviceName, message = finalMessage, repairCount = repairs, snapshotCount = snapshots)
                        stateOwner = null
                    }
                    RoutingDiagnostics.record(finalMessage)
                    // A late cleanup from an older Start must not stop a newer service request.
                    stopSelfResult(latestStartId)
                }
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
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outputChanges)
        ShellExecutor.getInstance(this).setAutoReconnectOwner(this, false)
        RoutingDiagnostics.record("Routing foreground service destroyed; closing shell control socket")
        if (stateOwner === this && task?.isActive == true && running) mutableState.value = mutableState.value.copy(active = false, busy = true,
            message = "Stopping the native audio policy…")
        task?.cancel()
        session?.cancelStart()
        scope.cancel()
        if (stateOwner === this && task == null) { mutableState.value = mutableState.value.copy(active = false, busy = false); stateOwner = null }
        super.onDestroy()
    }

    data class RoutingState(
        val active: Boolean = false,
        val busy: Boolean = false,
        val packageName: String? = null,
        val deviceId: Int = -1,
        val deviceName: String = "",
        val message: String = "Stopped. Select the music app and its Bluetooth output. Other media will use the phone speaker.",
        val bothObserved: Boolean = false,
        val policyGeneration: Int = 0,
        val repairCount: Int = 0,
        val snapshotCount: Int = 0,
        val observedAt: Long = 0,
        val observedElapsed: Long = 0,
        val notice: String = ""
    )

    companion object {
        const val ACTION_STOP = "com.asdfg.soundmaster.STOP_ROUTING"
        const val ACTION_REPAIR = "com.asdfg.soundmaster.REPAIR_AUDIO"
        const val OBSERVATION_INTERVAL_MS = 15_000L
        const val REPAIR_COOLDOWN_MS = 30_000L
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "soundmaster_service"
        private val mutableState = MutableStateFlow(RoutingState())
        @Volatile private var stateOwner: SoundMasterService? = null
        val state: StateFlow<RoutingState> = mutableState
        val running: Boolean get() = state.value.active || state.value.busy
    }
}
