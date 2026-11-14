package com.asdfg.soundmaster

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.asdfg.soundmaster.adb.ShellExecutor
import com.asdfg.soundmaster.adb.AdbSetupDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.asdfg.soundmaster.audio.SoundMasterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.R)
class MainActivity : AppCompatActivity() {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var audioManager: AudioManager

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var importKeyButton: Button
    private lateinit var appSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var balanceSeekBar: SeekBar
    private lateinit var balanceValue: TextView
    private lateinit var startStopButton: Button
    private lateinit var serviceStatus: TextView

    private var installedApps: List<ApplicationInfo> = emptyList()
    private var audioOutputs: List<AudioDeviceInfo> = emptyList()
    private var selectedApp: String? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var currentVolume = 100f
    private var currentBalance = 0f

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SoundMasterService.projectionData = result.data
            startSoundMaster()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
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

        createNotificationChannel()
        bindViews()
        setupListeners()
        loadApps()
        loadAudioOutputs()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shellExecutor.state.collect { updateAdbStatus(it) }
            }
        }
        checkAdbStatus()
        requestPermissions()
    }

    private fun bindViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        importKeyButton = findViewById(R.id.importKeyButton)
        
        appSpinner = findViewById(R.id.appSpinner)
        outputSpinner = findViewById(R.id.outputSpinner)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        volumeValue = findViewById(R.id.volumeValue)
        balanceSeekBar = findViewById(R.id.balanceSeekBar)
        balanceValue = findViewById(R.id.balanceValue)
        startStopButton = findViewById(R.id.startStopButton)
        serviceStatus = findViewById(R.id.serviceStatus)
    }

    private fun setupListeners() {
        connectButton.setOnClickListener { showAdbSetup() }
        importKeyButton.setOnClickListener {
            if (SoundMasterService.running) {
                Toast.makeText(this, "Stop audio routing before changing ADB setup", Toast.LENGTH_LONG).show()
            } else importKeyLauncher.launch(arrayOf("*/*"))
        }

        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                 val newApp = installedApps.getOrNull(position)?.packageName
                 if (selectedApp != newApp) {
                     selectedApp = newApp
                     if (SoundMasterService.running) {
                         Toast.makeText(this@MainActivity, "Stop service to change app", Toast.LENGTH_SHORT).show()
                     }
                 }
             }
             override fun onNothingSelected(parent: AdapterView<*>?) {
                 selectedApp = null
             }
        }

        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newOutput = audioOutputs.getOrNull(position)
                if (selectedOutput != newOutput) {
                    val oldOutputId = selectedOutput?.id ?: -1
                    selectedOutput = newOutput
                    
                    if (SoundMasterService.running) {
                        selectedApp?.let { pkg ->
                            val service = getService()
                            service?.packageThreads?.get(pkg)?.let { thread ->
                                val success = thread.switchOutputDevice(oldOutputId, newOutput)
                                if (success) {
                                    Toast.makeText(this@MainActivity, "Switched to ${newOutput?.productName}", Toast.LENGTH_SHORT).show()
                                    updateServiceUI(true)
                                }
                            }
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedOutput = null
            }
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolume = progress.toFloat()
                volumeValue.text = "$progress%"
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setVolume(selectedOutput?.id ?: -1, currentVolume)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        balanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBalance = (progress - 100).toFloat()
                balanceValue.text = currentBalance.toInt().toString()
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setBalance(selectedOutput?.id ?: -1, currentBalance)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startStopButton.setOnClickListener {
            if (SoundMasterService.running) {
                stopService(Intent(this, SoundMasterService::class.java))
                updateServiceUI(false)
            } else {
                if (selectedApp == null) {
                    Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!shellExecutor.isConnected) {
                    Toast.makeText(this, "Connect AudioPipe in Setup / Pair first", Toast.LENGTH_LONG).show()
                    showAdbSetup()
                    return@setOnClickListener
                }

                requestMediaProjection()
            }
        }
    }

    private fun showAdbSetup() {
        if (SoundMasterService.running) {
            Toast.makeText(this, "Stop audio routing before changing ADB setup", Toast.LENGTH_LONG).show()
            return
        }
        if (supportFragmentManager.findFragmentByTag("adb-setup") == null) {
            AdbSetupDialog().show(supportFragmentManager, "adb-setup")
        }
    }

    private fun checkAdbStatus() {
        if (shellExecutor.isSetUp && !shellExecutor.isConnected) {
            lifecycleScope.launch { shellExecutor.autoConnect() }
        }
    }

    private fun updateAdbStatus(state: ShellExecutor.ConnectionState) {
        statusIndicator.setBackgroundResource(
            if (state.connected) R.drawable.status_indicator_green else R.drawable.status_indicator_red
        )
        statusText.text = state.message
        connectButton.text = if (state.busy) "Working…" else if (state.connected) "Connected" else "Setup / Pair"
        connectButton.isEnabled = !state.busy
        importKeyButton.isEnabled = !state.busy
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .sortedBy { packageManager.getApplicationLabel(it).toString() }

            val appNames = installedApps.map { packageManager.getApplicationLabel(it).toString() }

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, appNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                appSpinner.adapter = adapter

                val spotifyIndex = installedApps.indexOfFirst { it.packageName == "com.spotify.music" }
                if (spotifyIndex >= 0) {
                    appSpinner.setSelection(spotifyIndex)
                }
            }
        }
    }

    private fun loadAudioOutputs() {
        audioOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

        val outputNames = audioOutputs.map { "${it.productName ?: getDeviceTypeName(it.type)}" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outputNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        outputSpinner.adapter = adapter

        val btIndex = audioOutputs.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (btIndex >= 0) {
            outputSpinner.setSelection(btIndex)
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Unknown"
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSoundMaster() {
        val intent = Intent(this, SoundMasterService::class.java).apply {
            putExtra("packages", arrayOf(selectedApp))
            putExtra("devices", intArrayOf(selectedOutput?.id ?: -1))
            putExtra("volumes", floatArrayOf(currentVolume))
        }
        startForegroundService(intent)
        updateServiceUI(true)
    }

    private fun updateServiceUI(running: Boolean) {
        if (running) {
            startStopButton.text = getString(R.string.stop)
            serviceStatus.text = "Routing ${selectedApp} → ${selectedOutput?.productName ?: "Default"}"
        } else {
            startStopButton.text = getString(R.string.start)
            serviceStatus.text = ""
        }
    }

    private fun getService(): SoundMasterService? {
        return SoundMasterService.instance
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SoundMasterService.NOTIFICATION_CHANNEL,
            "SoundMaster Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI(SoundMasterService.running)
        loadAudioOutputs() 
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
