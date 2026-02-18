package com.screencast.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screencast.tv.common.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppPrefs.init(context)
            if (AppPrefs.autoStartOnBoot) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
