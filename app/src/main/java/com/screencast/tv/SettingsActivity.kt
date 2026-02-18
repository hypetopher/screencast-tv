package com.screencast.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.screencast.tv.airplay.AirPlayService
import com.screencast.tv.common.AppPrefs
import com.screencast.tv.dlna.DlnaService

class SettingsActivity : FragmentActivity() {

    private lateinit var deviceNameValue: TextView
    private lateinit var subtitleSizeValue: TextView
    private lateinit var autoStartValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        deviceNameValue = findViewById(R.id.tv_device_name_value)
        subtitleSizeValue = findViewById(R.id.tv_subtitle_size_value)
        autoStartValue = findViewById(R.id.tv_auto_start_value)

        refreshValues()

        findViewById<LinearLayout>(R.id.row_device_name).setOnClickListener {
            showDeviceNameDialog()
        }

        findViewById<LinearLayout>(R.id.row_subtitle_size).setOnClickListener {
            val next = (AppPrefs.subtitleSize + 1) % 4
            AppPrefs.subtitleSize = next
            refreshValues()
        }

        findViewById<LinearLayout>(R.id.row_auto_start).setOnClickListener {
            AppPrefs.autoStartOnBoot = !AppPrefs.autoStartOnBoot
            refreshValues()
        }
    }

    private fun refreshValues() {
        deviceNameValue.text = AppPrefs.deviceName
        subtitleSizeValue.text = AppPrefs.subtitleSizeLabel
        autoStartValue.text = if (AppPrefs.autoStartOnBoot) "On" else "Off"
    }

    private fun showDeviceNameDialog() {
        val editText = EditText(this).apply {
            setText(AppPrefs.deviceName)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x99FFFFFF.toInt())
            setPadding(48, 32, 48, 32)
            textSize = 20f
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setting_device_name)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty() && name != AppPrefs.deviceName) {
                    AppPrefs.deviceName = name
                    refreshValues()
                    restartServices()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restartServices() {
        val airplayIntent = Intent(this, AirPlayService::class.java)
        val dlnaIntent = Intent(this, DlnaService::class.java)

        stopService(airplayIntent)
        stopService(dlnaIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(airplayIntent)
            startForegroundService(dlnaIntent)
        } else {
            startService(airplayIntent)
            startService(dlnaIntent)
        }
    }
}
