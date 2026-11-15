package com.asdfg.soundmaster

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.asdfg.soundmaster.adb.AdbSetupDialog
import com.asdfg.soundmaster.adb.ShellExecutor
import com.asdfg.soundmaster.audio.RouteSession
import com.asdfg.soundmaster.audio.RoutingDiagnostics
import com.asdfg.soundmaster.audio.SoundMasterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var shellExecutor: ShellExecutor
    private lateinit var audioManager: AudioManager
    private lateinit var appSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var startStopButton: Button
    private lateinit var serviceStatus: TextView
    private var installedApps: List<ApplicationInfo> = emptyList()
    private var audioOutputs: List<AudioDeviceInfo> = emptyList()
    private var selectedApp: String? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var loadingSelections = false
    private val prefs by lazy { getSharedPreferences(RouteSession.PREFS, Context.MODE_PRIVATE) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            RoutingDiagnostics.record("Audio outputs added: ${added.map { it.type to it.id }}")
            loadAudioOutputs()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            RoutingDiagnostics.record("Audio outputs removed: ${removed.map { it.type to it.id }}")
            loadAudioOutputs()
        }
    }

    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = RoutingDiagnostics.report(applicationContext)
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(report) }
                        ?: error("Could not open the selected report file")
                }
            }.onSuccess { toast("Diagnostic report saved. Review it before sharing.") }
                .onFailure { toast("Export failed: ${it.message}") }
        }
    }

    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val data = ByteArray(65537)
                        var size = 0
                        while (size < data.size) {
                            val n = stream.read(data, size, data.size - size)
                            if (n < 0) break
                            size += n
                        }
                        require(size in 1..65536) { "Choose an ADB private key smaller than 64 KB" }
                        data.copyOf(size)
                    } ?: error("Could not read the selected file")
                }
                try {
                    if (shellExecutor.importKey(bytes).isSuccess) showAdbSetup()
                } finally { bytes.fill(0) }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shellExecutor = ShellExecutor.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appSpinner = findViewById(R.id.appSpinner)
        outputSpinner = findViewById(R.id.outputSpinner)
        startStopButton = findViewById(R.id.startStopButton)
        serviceStatus = findViewById(R.id.serviceStatus)
        findViewById<Button>(R.id.connectButton).setOnClickListener { showAdbSetup() }
        findViewById<Button>(R.id.importKeyButton).setOnClickListener {
            if (SoundMasterService.running) toast("Stop audio routing before changing ADB setup")
            else importKeyLauncher.launch(arrayOf("*/*"))
        }
        setupSelections()
        startStopButton.setOnClickListener {
            if (SoundMasterService.running) stopService(Intent(this, SoundMasterService::class.java))
            else startRouting()
        }
        findViewById<Button>(R.id.restorePlaybackButton).setOnClickListener { restoreSelectedPlayback() }
        findViewById<Button>(R.id.enableSimultaneousButton).setOnClickListener { saveMultiFocus(true) }
        findViewById<Button>(R.id.normalFocusButton).setOnClickListener { saveMultiFocus(false) }
        findViewById<Button>(R.id.exportDiagnosticsButton).setOnClickListener {
            exportDiagnostics.launch("AudioPipe-diagnostics-${System.currentTimeMillis()}.txt")
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        loadApps()
        loadAudioOutputs()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SoundMasterService.state.collect { render(it) } }
                launch { shellExecutor.state.collect { updateAdbStatus(it) } }
            }
        }
        if (shellExecutor.isSetUp && !shellExecutor.isConnected) {
            lifecycleScope.launch { shellExecutor.autoConnect() }
        }
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun setupSelections() {
        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (loadingSelections || SoundMasterService.running) return
                selectedApp = installedApps.getOrNull(position)?.packageName
                prefs.edit().putString("selected_package", selectedApp).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (loadingSelections || SoundMasterService.running) return
                selectedOutput = audioOutputs.getOrNull(position - 1)
                selectedOutput?.let { prefs.edit().putString("selected_device", deviceKey(it)).apply() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadApps() = lifecycleScope.launch {
        val apps = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(0).filter {
                it.uid >= 10000 && it.packageName != packageName
                    && packageManager.getLaunchIntentForPackage(it.packageName) != null
            }.sortedBy { packageManager.getApplicationLabel(it).toString() }
        }
        installedApps = apps
        val wanted = SoundMasterService.state.value.takeIf { it.active || it.busy }?.packageName
            ?: selectedApp ?: prefs.getString("selected_package", "com.music.bitchord")
        val index = apps.indexOfFirst { it.packageName == wanted }.let { if (it < 0) 0 else it }
        loadingSelections = true
        appSpinner.adapter = adapter(apps.map { "${packageManager.getApplicationLabel(it)} (${it.packageName})" })
        if (apps.isNotEmpty()) appSpinner.setSelection(index)
        selectedApp = apps.getOrNull(index)?.packageName
        loadingSelections = false
        render(SoundMasterService.state.value)
    }

    private fun loadAudioOutputs() {
        if (!::outputSpinner.isInitialized) return
        audioOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        val active = SoundMasterService.state.value.takeIf { it.active || it.busy }
        val oldId = active?.deviceId ?: selectedOutput?.id
        val savedKey = prefs.getString("selected_device", null)
        val savedMatches = audioOutputs.filter { deviceKey(it) == savedKey }
        val chosen = audioOutputs.firstOrNull { it.id == oldId }
            ?: savedMatches.singleOrNull() ?: audioOutputs.singleOrNull()
        loadingSelections = true
        outputSpinner.adapter = adapter(listOf("Select connected Bluetooth media device") + audioOutputs.map {
            "${it.productName} — ${if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "A2DP" else "LE Audio"}"
        })
        selectedOutput = chosen
        outputSpinner.setSelection(audioOutputs.indexOf(chosen) + 1)
        loadingSelections = false
    }

    private fun adapter(labels: List<String>) = ArrayAdapter(this,
        android.R.layout.simple_spinner_item, labels).also {
        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun deviceKey(d: AudioDeviceInfo) = "${d.type}|${d.address}|${d.productName}"

    private fun startRouting() {
        val pkg = selectedApp ?: return toast("Select the music app first")
        val device = selectedOutput ?: return toast("Connect and select X-906 or another Bluetooth media device")
        if (!shellExecutor.isConnected) { toast("Connect AudioPipe in Setup / Pair first"); showAdbSetup(); return }
        try {
            startForegroundService(Intent(this, SoundMasterService::class.java).apply {
                putExtra("package", pkg)
                putExtra("device", device.id)
                putExtra("deviceName", device.productName.toString())
                putExtra("deviceType", device.type)
                putExtra("deviceAddress", device.address)
            })
            // Only the service may announce policy acceptance or observed outputs.
            serviceStatus.text = "Starting foreground service…"
            startStopButton.isEnabled = false
            lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                if (!SoundMasterService.running && serviceStatus.text == "Starting foreground service…") {
                    startStopButton.isEnabled = true
                    serviceStatus.text = "Android did not start the routing service. Retry with AudioPipe open."
                    RoutingDiagnostics.record(serviceStatus.text.toString())
                }
            }
        } catch (error: RuntimeException) {
            startStopButton.isEnabled = true
            serviceStatus.text = "Could not start routing service: ${error.message}"
            RoutingDiagnostics.record(serviceStatus.text.toString())
        }
    }

    private fun restoreSelectedPlayback() {
        if (SoundMasterService.running) return toast("Stop routing before restoring playback")
        val pkg = selectedApp ?: return toast("Select the affected app first")
        if (!shellExecutor.isConnected) return toast("Connect ADB first")
        lifecycleScope.launch {
            val result = shellExecutor.commandSync("appops set ${RouteSession.quote(pkg)} PLAY_AUDIO allow")
            toast(if (result.isSuccess && result.getOrNull().orEmpty().isBlank())
                "$pkg playback allowed. Now press Start." else "Restore failed: ${result.exceptionOrNull()?.message ?: result.getOrNull()}")
        }
    }

    private fun saveMultiFocus(enabled: Boolean) {
        if (SoundMasterService.running) return toast("Stop routing before changing the saved audio-focus option")
        if (!shellExecutor.isConnected) return toast("Connect ADB first")
        lifecycleScope.launch {
            runCatching {
                val value = if (enabled) "1" else "0"
                val reply = shellExecutor.commandSync("settings put system multi_audio_focus_enabled $value").getOrThrow()
                check(reply.isBlank()) { reply.take(200) }
                check(shellExecutor.commandSync("settings get system multi_audio_focus_enabled").getOrThrow().trim() == value) {
                    "Android did not save the audio-focus option"
                }
                val message = "Saved ${if (enabled) "simultaneous media" else "normal audio focus"}. Restart your phone, enable Wireless debugging and reconnect AudioPipe. This is a persistent system setting."
                serviceStatus.text = message
                RoutingDiagnostics.record(message)
                toast(message)
            }.onFailure { toast("Could not save audio-focus option: ${it.message}") }
        }
    }

    private fun render(state: SoundMasterService.RoutingState) {
        val running = state.active || state.busy
        serviceStatus.text = state.message
        startStopButton.text = if (running) getString(R.string.stop) else getString(R.string.start)
        startStopButton.isEnabled = true
        appSpinner.isEnabled = !running
        outputSpinner.isEnabled = !running
        findViewById<Button>(R.id.restorePlaybackButton).isEnabled = !running
        findViewById<Button>(R.id.enableSimultaneousButton).isEnabled = !running
        findViewById<Button>(R.id.normalFocusButton).isEnabled = !running
        if (running) {
            val appIndex = installedApps.indexOfFirst { it.packageName == state.packageName }
            if (appIndex >= 0 && appSpinner.selectedItemPosition != appIndex) appSpinner.setSelection(appIndex)
            val deviceIndex = audioOutputs.indexOfFirst { it.id == state.deviceId }
            if (deviceIndex >= 0 && outputSpinner.selectedItemPosition != deviceIndex + 1) outputSpinner.setSelection(deviceIndex + 1)
        }
    }

    private fun updateAdbStatus(state: ShellExecutor.ConnectionState) {
        findViewById<View>(R.id.statusIndicator).setBackgroundResource(
            if (state.connected) R.drawable.status_indicator_green else R.drawable.status_indicator_red)
        findViewById<TextView>(R.id.statusText).text = state.message
        findViewById<Button>(R.id.connectButton).apply {
            text = if (state.busy) "Working…" else if (state.connected) "Connected" else "Setup / Pair"
            isEnabled = !state.busy
        }
        findViewById<Button>(R.id.importKeyButton).isEnabled = !state.busy

    }

    private fun showAdbSetup() {
        if (SoundMasterService.running) return toast("Stop audio routing before changing ADB setup")
        if (supportFragmentManager.findFragmentByTag("adb-setup") == null) {
            AdbSetupDialog().show(supportFragmentManager, "adb-setup")
        }
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onResume() {
        super.onResume()
        loadAudioOutputs()
        render(SoundMasterService.state.value)
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }
}
