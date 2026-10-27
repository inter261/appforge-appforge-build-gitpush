package com.app.forge.build

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.app.forge.data.Entry
import com.app.forge.data.HistoryStore
import com.app.forge.data.Store
import com.app.forge.github.GitHub
import com.app.forge.github.Step
import com.app.forge.github.UploadProgress
import com.app.forge.github.fmtSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Keadaan satu build (satu per proyek). Dibaca dari UI; ditulis hanya oleh [BuildManager]. */
class BuildSession(val project: String) {
    @Volatile var running: Boolean = false
    @Volatile var lastId: Long? = null
    @Volatile var logs: List<String> = emptyList()

    /** Langkah job GitHub Actions (untuk progres). */
    @Volatile var steps: List<Step> = emptyList()

    /** idle | running | success | failed | cancelled */
    @Volatile var status: String = "idle"
    @Volatile var task: BuildTask? = null
    @Volatile var finalLog: String = ""
    @Volatile var finalMessage: String = ""
    @Volatile var cancelling: Boolean = false

    /** Waktu mulai build (ms) dan log mentah job GitHub (untuk dipecah per langkah). */
    @Volatile var startedAt: Long = 0
    @Volatile var jobLog: String = ""

    /** Kemajuan unggah file ke GitHub (null sebelum dimulai). */
    @Volatile var upload: UploadProgress? = null

    @Volatile internal var cancelRequested = false
    @Volatile internal var head = listOf<String>()
    @Volatile internal var runRef: Triple<String, String, Long>? = null
    internal var token = ""

    /** Baris dari sisi aplikasi sebelum langkah GitHub muncul (kirim file, tunggu runner). */
    val preLog: List<String> get() = head

    /** Alamat halaman run di GitHub (null sebelum run dibuat). */
    val runUrl: String? get() = runRef?.let { "https://github.com/${it.first}/${it.second}/actions/runs/${it.third}" }

    /** Ringkasan unggahan untuk ditampilkan: "Mengirim 3/12 file · 340 KB / 2.1 MB". Null bila belum ada. */
    fun uploadText(): String? {
        val u = upload ?: return null
        return if (u.total == 0) "Tidak ada file yang berubah"
        else "Mengirim ${u.done}/${u.total} file · ${fmtSize(u.bytesDone)} / ${fmtSize(u.bytesTotal)}"
    }

    /** Nama langkah yang sedang berjalan, atau keterangan unggahan/persiapan bila langkah GitHub belum muncul. */
    fun currentText(): String =
        steps.firstOrNull { it.state == "in_progress" }?.name
            ?: (if (steps.isEmpty()) uploadText() else null) ?: "menyiapkan"

    fun elapsed(): String {
        val t = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
        return if (t >= 60) "${t / 60}m ${"%02d".format(t % 60)}s" else "${t}s"
    }
}

/**
 * Beberapa build bisa berjalan bersamaan (satu per proyek; tiap proyek punya repo GitHub sendiri).
 * Build berjalan di scope sendiri, jadi tidak terhenti saat pindah layar. Layar mendaftar lewat [addListener].
 */
object BuildManager {
    private val sessions = ConcurrentHashMap<String, BuildSession>()
    @Volatile var lastStarted: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it() } } }

    /** Sesi build proyek ini; bila belum pernah build di sesi ini, sesi kosong berstatus "idle". */
    fun of(project: String): BuildSession = sessions[project] ?: BuildSession(project)

    fun running(): List<BuildSession> = sessions.values.filter { it.running }.sortedBy { it.startedAt }
    fun anyRunning(): Boolean = sessions.values.any { it.running }

    /** Sesi terakhir yang selesai (untuk spanduk hasil). */
    fun lastFinished(): BuildSession? = sessions.values.filter { !it.running && it.lastId != null }.maxByOrNull { it.lastId ?: 0 }

    fun start(app: Context, store: Store, hist: HistoryStore, project: String, task: BuildTask = BuildTask.default(store.config(project))) {
        if (sessions[project]?.running == true) return
        val s = BuildSession(project)
        sessions[project] = s
        s.running = true
        s.status = "running"
        s.task = task
        s.startedAt = System.currentTimeMillis()
        s.token = store.token
        lastStarted = project
        changed()
        val id = System.currentTimeMillis()
        val appCtx = app.applicationContext
        // Notifikasi progres (juga menjaga proses tetap hidup saat aplikasi di latar belakang).
        BuildNotifier.ensureChannels(appCtx)
        runCatching { appCtx.startForegroundService(Intent(appCtx, BuildService::class.java)) }
        scope.launch {
            while (s.running) {
                BuildNotifier.update(appCtx, running())
                delay(1500)
            }
        }
        scope.launch {
            val tmp = File(appCtx.cacheDir, "build-$project-$id.out")
            val res = try {
                runBuild(
                    store, project, task, tmp,
                    onSteps = { s.steps = it; changed() },
                    onRun = { o, r, i -> s.runRef = Triple(o, r, i) },
                    cancelled = { s.cancelRequested },
                    log = { line -> s.head = s.head + line; s.logs = s.logs + line; changed() },
                    live = { text -> s.jobLog = text; s.logs = s.head + cleanLog(text).lines(); changed() },
                    onUpload = { s.upload = it; changed() }
                )
            } catch (e: Exception) {
                BuildResult(false, e.message ?: e.toString(), s.logs.joinToString("\n"), "none")
            }
            val secs = ((System.currentTimeMillis() - id) / 1000).toInt()
            val hasOut = res.kind != "none" && tmp.exists()
            val kind = if (hasOut) res.kind else "none"
            val entry = Entry(id, project, res.success, secs, res.message, if (hasOut) tmp.length() else 0, kind)
            hist.save(entry, res.log)
            if (hasOut) {
                tmp.copyTo(hist.output(entry), overwrite = true)
                tmp.delete()
            }
            s.finalLog = res.log
            s.jobLog = res.raw.ifEmpty { res.log }
            s.finalMessage = res.message
            s.status = when {
                res.success -> "success"
                res.message == "Dibatalkan" -> "cancelled"
                else -> "failed"
            }
            s.cancelling = false
            s.lastId = id
            s.running = false
            if (!anyRunning()) runCatching { appCtx.stopService(Intent(appCtx, BuildService::class.java)) }
            BuildNotifier.finish(
                appCtx, project, s.status, res.message,
                apk = if (res.success && hasOut && kind == "apk") hist.output(entry) else null,
                zip = if (res.success && hasOut && kind == "zip") hist.output(entry) else null
            )
            if (anyRunning()) BuildNotifier.update(appCtx, running())
            changed()
        }
    }

    /** Batalkan build satu proyek (membatalkan run di GitHub Actions). */
    fun stop(project: String) {
        val s = sessions[project] ?: return
        if (!s.running) return
        s.cancelRequested = true
        s.cancelling = true
        changed()
        val r = s.runRef
        if (r != null) scope.launch { runCatching { GitHub(s.token).cancelRun(r.first, r.second, r.third) } }
    }

    fun stopAll() = running().forEach { stop(it.project) }
}
