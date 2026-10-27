package com.app.forge.build

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tombol "Batal" di notifikasi progres. */
class BuildActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BuildNotifier.ACTION_CANCEL) BuildManager.stopAll()
    }
}
