package com.asdfg.soundmaster.adb

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.asdfg.soundmaster.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AdbSetupDialog : DialogFragment() {
    private var operation: Job? = null
    private var connectedSuccessfully = false
    private lateinit var shell: ShellExecutor
    private lateinit var code: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        shell = ShellExecutor.getInstance(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_adb_setup, null)
        val pairPort = view.findViewById<EditText>(R.id.pairingPortInput)
        val connectPort = view.findViewById<EditText>(R.id.connectionPortInput)
        code = view.findViewById(R.id.pairingCodeInput)
        val pair = view.findViewById<Button>(R.id.pairAdbButton)
        val connect = view.findViewById<Button>(R.id.connectAdbButton)
        val discover = view.findViewById<Button>(R.id.discoverPairingButton)
        val settings = view.findViewById<Button>(R.id.developerSettingsButton)
        val message = view.findViewById<TextView>(R.id.adbSetupMessage)

        settings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { message.text = "Open Settings → Developer options → Wireless debugging manually." }
        }
        discover.setOnClickListener {
            if (operation?.isActive == true) return@setOnClickListener
            operation = lifecycleScope.launch {
                shell.discoverPairingPort().onSuccess { pairPort.setText(it.toString()) }
            }
        }
        pair.setOnClickListener {
            if (operation?.isActive == true) return@setOnClickListener
            val port = pairPort.text.toString().toIntOrNull()
            if (port == null || port !in 1..65535) {
                pairPort.error = "Enter the port shown next to the pairing code"
                return@setOnClickListener
            }
            val pin = code.text.toString()
            if (!pin.matches(Regex("[0-9]{6}"))) {
                code.error = "Enter all six digits"
                return@setOnClickListener
            }
            operation = lifecycleScope.launch {
                try {
                    if (shell.pair(port, pin).isSuccess) {
                        val typed = connectPort.text.toString().toIntOrNull()
                        val result = if (typed != null) shell.connectResult(typed) else shell.autoConnect()
                        if (result.isSuccess) finishConnected()
                        // Pairing remains saved if discovery fails; manual Connect is available.
                    }
                } finally { code.text.clear() }
            }
        }
        connect.setOnClickListener {
            if (operation?.isActive == true) return@setOnClickListener
            val raw = connectPort.text.toString()
            val port = raw.toIntOrNull()
            if (raw.isNotEmpty() && (port == null || port !in 1..65535)) {
                connectPort.error = "Enter a port between 1 and 65535"
                return@setOnClickListener
            }
            operation = lifecycleScope.launch {
                val result = if (port != null) shell.connectResult(port) else shell.autoConnect()
                if (result.isSuccess) finishConnected()
            }
        }
        lifecycleScope.launch {
            shell.state.collect { state ->
                message.text = state.message
                listOf(pair, connect, discover, settings).forEach { it.isEnabled = !state.busy }
            }
        }
        return AlertDialog.Builder(requireContext())
            .setTitle("Wireless debugging — AudioPipe")
            .setView(view)
            .setNegativeButton("Close / cancel", null)
            .create()
    }

    private fun finishConnected() {
        connectedSuccessfully = true
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::code.isInitialized) code.text.clear()
        if (!connectedSuccessfully && operation?.isActive == true) {
            shell.cancelCurrentOperation()
            operation?.cancel()
        }
        super.onDismiss(dialog)
    }
}
