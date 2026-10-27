package com.app.forge.build

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.app.forge.R
import com.app.forge.github.Step
import java.io.File

/**
 * Notifikasi build: satu notifikasi progres selama build berjalan (nama langkah/tugas Gradle, bilah progres, tombol Batal),
 * lalu satu notifikasi hasil. Kalau sukses dan hasilnya APK, notifikasi itu punya tombol "Install" yang langsung membuka
 * pemasang paket.
 */
object BuildNotifier {
    private const val CH_PROGRESS = "build_progress"
    private const val CH_RESULT = "build_result"
    const val ID_PROGRESS = 4101
    private const val ID_RESULT = 4102
    const val ACTION_CANCEL = "com.app.forge.BUILD_CANCEL"

    private val DONE = setOf("success", "failure", "skipped", "cancelled")

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Proses build", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progres build yang sedang berjalan"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Hasil build", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Build selesai, dengan tombol Install bila berhasil"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent().setClassName(ctx, "com.app.forge.ui.MainActivity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Teks progres dari langkah job GitHub Actions dan tugas Gradle terakhir di log. */
    private fun describe(steps: List<Step>, logs: List<String>): Triple<String, Int, Int> {
        val total = steps.size
        val done = steps.count { it.state in DONE }
        val current = steps.firstOrNull { it.state == "in_progress" }?.name
        val task = parseGradleTasks(logs.takeLast(300).joinToString("\n")).lastOrNull()?.path
        val text = when {
            current == null && total == 0 -> "Menyiapkan build…"
            task != null && current != null -> "$current · $task"
            current != null -> current
            else -> "Menyelesaikan…"
        }
        return Triple(text, done, total)
    }

    fun progressNotification(ctx: Context, running: List<BuildSession>): Notification {
        val cancel = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, BuildActionReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_build)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (running.size <= 1) {
            val s = running.firstOrNull()
            val steps = s?.steps ?: emptyList()
            val (text, done, total) = describe(steps, s?.logs ?: emptyList())
            val cancelling = s?.cancelling == true
            b.setContentTitle(if (cancelling) "Membatalkan build · ${s?.project}" else "Membangun ${s?.project ?: "proyek"}")
                .setContentText(if (total > 0) "Langkah ${minOf(done + 1, total)}/$total · $text" else text)
                .setProgress(total.coerceAtLeast(1), done, total == 0)
            if (!cancelling) b.addAction(0, "Batal", cancel)
        } else {
            // Beberapa build sekaligus: satu notifikasi ringkasan, tiap proyek satu baris.
            val lines = running.map { s ->
                val (text, done, total) = describe(s.steps, s.logs)
                "${s.project}: " + (if (total > 0) "$done/$total · " else "") + text
            }
            b.setContentTitle("Membangun ${running.size} proyek")
                .setContentText(running.joinToString(", ") { it.project })
                .setStyle(NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } })
                .setProgress(0, 0, true)
                .addAction(0, "Batalkan semua", cancel)
        }
        return b.build()
    }

    fun update(ctx: Context, running: List<BuildSession>) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, progressNotification(ctx, running))
        }
    }

    /** Notifikasi hasil. [apk] hanya diisi kalau build sukses dan hasilnya berkas APK yang bisa dipasang. */
    fun finish(ctx: Context, project: String, status: String, message: String, apk: File?, zip: File?) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!BuildManager.anyRunning()) nm.cancel(ID_PROGRESS)
            val b = NotificationCompat.Builder(ctx, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat_build)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
            when {
                status == "success" && apk != null && apk.exists() -> {
                    val mb = apk.length() / 1_048_576.0
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
                    val install = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pi = PendingIntent.getActivity(ctx, 2, install, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    b.setContentTitle("Build berhasil · $project")
                        .setContentText("APK siap dipasang (${"%.1f".format(mb)} MB)")
                        .addAction(0, "Install", pi)
                }
                status == "success" ->
                    b.setContentTitle("Build berhasil · $project")
                        .setContentText(if (zip != null) "Hasil berupa ZIP/AAB, buka aplikasi untuk mengambilnya" else "Selesai tanpa berkas hasil")
                status == "cancelled" ->
                    b.setContentTitle("Build dibatalkan · $project").setContentText("Dibatalkan")
                else -> {
                    val first = message.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Lihat log untuk detailnya"
                    b.setContentTitle("Build gagal · $project")
                        .setContentText(first)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(600)))
                }
            }
            nm.notify(ID_RESULT + 1 + (project.hashCode() and 0xFFF), b.build())
        }
    }
}
