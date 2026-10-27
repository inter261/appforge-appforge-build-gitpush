package com.app.forge.build

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Service latar depan tanpa logika: tugasnya hanya menjaga proses aplikasi tetap hidup selama build berjalan
 * (supaya build tidak mati saat kamu pindah ke aplikasi lain) dan menampilkan notifikasi progresnya.
 */
class BuildService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BuildNotifier.ensureChannels(this)
        val n = BuildNotifier.progressNotification(this, BuildManager.running())
        if (Build.VERSION.SDK_INT >= 29) startForeground(BuildNotifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(BuildNotifier.ID_PROGRESS, n)
        if (!BuildManager.anyRunning()) stopSelf()
        return START_NOT_STICKY
    }
}
