package com.example.hourchime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SoundSettingsHelper.playSound(context, "uri_end", "volume_end")
    }
}
