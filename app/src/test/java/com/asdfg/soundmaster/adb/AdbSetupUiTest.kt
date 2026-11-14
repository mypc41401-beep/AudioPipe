package com.asdfg.soundmaster.adb

import android.content.Context
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.asdfg.soundmaster.MainActivity
import com.asdfg.soundmaster.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbSetupUiTest {
    @Test fun `Setup opens pairing controls and rejects incomplete codes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ShellExecutor.getInstance(context).disconnect()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.connectButton).performClick()
            activity.supportFragmentManager.executePendingTransactions()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val code = dialog.findViewById<EditText>(R.id.pairingCodeInput)!!
            dialog.findViewById<EditText>(R.id.pairingPortInput)!!.setText("37123")
            code.setText("123")
            dialog.findViewById<Button>(R.id.pairAdbButton)!!.performClick()
            assertNotNull(code.error)
            assertFalse(ShellExecutor.getInstance(context).isConnected)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
}
