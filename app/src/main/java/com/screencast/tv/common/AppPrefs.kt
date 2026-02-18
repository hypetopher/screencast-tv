package com.screencast.tv.common

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {

    private const val PREFS_NAME = "screencast_prefs"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_SUBTITLE_SIZE = "subtitle_size"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    private const val DEFAULT_DEVICE_NAME = "ScreenCast TV"
    private const val DEFAULT_SUBTITLE_SIZE = 1 // Medium
    private const val DEFAULT_AUTO_START = false

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var subtitleSize: Int
        get() = prefs.getInt(KEY_SUBTITLE_SIZE, DEFAULT_SUBTITLE_SIZE)
        set(value) = prefs.edit().putInt(KEY_SUBTITLE_SIZE, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    val subtitleSizeLabel: String
        get() = when (subtitleSize) {
            0 -> "Small"
            1 -> "Medium"
            2 -> "Large"
            3 -> "Extra Large"
            else -> "Medium"
        }

    val subtitleSizeSp: Float
        get() = when (subtitleSize) {
            0 -> 14f
            1 -> 22f
            2 -> 32f
            3 -> 44f
            else -> 22f
        }
}
