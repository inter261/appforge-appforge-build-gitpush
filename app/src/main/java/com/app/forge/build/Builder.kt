package com.app.forge.build

import android.util.Base64
import com.app.forge.R
import com.app.forge.data.Store
import com.app.forge.github.GitHub
import com.app.forge.github.GitPush
import com.app.forge.util.isSkippedPath
import com.app.forge.github.UploadProgress
import com.app.forge.github.Step
import com.app.forge.template.WORKFLOW_PATH
import com.app.forge.template.Templates
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream


private const val MARK = "AppForge" // penanda repo buatan AppForge (di deskripsi repo)

/** Nama repo tetap milik proyek di GitHub. */
fun projectRepoName(project: String): String =
    "appforge-" + project.lowercase().replace(Regex("[^a-z0-9-]"), "-")

/**
 * Menghapus repo tetap milik proyek di GitHub. Blocking: panggil dari thread IO.
 * Mengembalikan true kalau repo ada lalu dihapus, false kalau repo memang tidak ada.
 * Hanya repo bertanda AppForge yang boleh dihapus; selain itu atau kalau gagal, melempar exception berisi alasannya.
 */
fun deleteProjectRepo(store: Store, project: String): Boolean {
    val ctx = store.app
    if (store.token.isBlank()) error(ctx.getString(R.string.repo_err_token))
    val gh = GitHub(store.token)
    val owner = gh.user()
    val repo = projectRepoName(project)
    val info = gh.repoInfo(owner, repo) ?: return false
    val desc = if (info.isNull("description")) "" else info.getString("description")
    if (desc != MARK) error(ctx.getString(R.string.repo_err_foreign, repo))
    val code = gh.deleteRepoCode(owner, repo)
    if (code != 204) error(
        if (code == 403 || code == 404) ctx.getString(R.string.repo_err_scope) else ctx.getString(R.string.repo_err_http, code)
    )
    return true
}

/** kind: "apk" | "zip" | "none" (lihat Entry.kind). */
class BuildResult(val success: Boolean, val message: String, val log: String, val kind: String, val raw: String = "")

fun cleanLog(raw: String): String =
    raw.replace("\uFEFF", "").replace(Regex("(?m)^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z "), "")

/** Blocking: panggil dari thread IO. */
fun runBuild(
    store: Store,
    project: String,
    task: BuildTask,
    outFile: File,
    onSteps: (List<Step>) -> Unit,
    onRun: (String, String, Long) -> Unit,
    cancelled: () -> Boolean,
    log: (String) -> Unit,
    live: (String) -> Unit = {},
    onUpload: (UploadProgress) -> Unit = {}
): BuildResult {
    if (store.token.isBlank()) error("Isi GitHub token dulu di Pengaturan")
    val cfg0 = store.config(project)
    // Varian dan format hasil (APK/AAB) mengikuti task yang dipilih; hanya berlaku untuk workflow bawaan.
    val cfg = if (Templates.hasBuildType(cfg0.template)) cfg0.copy(release = task.release) else cfg0
    val bundle = task.bundle && cfg.template == "android"

    // Siapkan file, workflow, dan signing sebelum menyentuh GitHub, supaya salah konfigurasi ketahuan cepat.
    val dir = File(store.root, project)
    // Tidak ada file yang dibaca ke RAM di sini; GitPush membaca dari disk sebagai aliran. Folder build/, .git, .gradle dst dilewati.
    val paths = ArrayList<String>()
    store.files(project).forEach { p ->
        if (!isSkippedPath(p) && File(dir, p).length() > 0) paths.add(p)
    }
    val memFiles = HashMap<String, ByteArray>()
    // Kalau proyek punya build.yml sendiri, itu yang dipakai; kalau belum ada, dibuat dari template + pengaturan.
    if (WORKFLOW_PATH !in paths) { memFiles[WORKFLOW_PATH] = Templates.workflow(store.app, cfg, bundle).toByteArray(); paths.add(WORKFLOW_PATH) }
    else if (task.release != cfg0.release || task.bundle) log("build.yml proyek dipakai; varian dan format hasil mengikuti isi file itu")
    val wf = String(memFiles[WORKFLOW_PATH] ?: File(dir, WORKFLOW_PATH).readBytes())
    if (!wf.contains("workflow_dispatch")) {
        error("build.yml harus punya trigger 'workflow_dispatch'. Buka Workflow > Edit build.yml.")
    }

    var inputs = emptyMap<String, String>()
    if (cfg.sign && Templates.hasSigning(cfg.template)) {
        val ks = store.keystoreBytes(project) ?: error("Signing aktif tapi keystore belum dipilih (Workflow > Signing)")
        if (cfg.alias.isBlank() || cfg.storePass.isEmpty()) error("Isi alias key dan password keystore (Workflow > Signing)")
        if (!wf.contains("keystore_b64")) {
            error("Signing aktif tapi build.yml tidak punya input keystore_b64. Tekan 'Terapkan ke build.yml' di menu Workflow.")
        }
        inputs = mapOf(
            "keystore_b64" to Base64.encodeToString(ks, Base64.NO_WRAP),
            "keystore_password" to cfg.storePass,
            "key_alias" to cfg.alias.trim(),
            "key_password" to cfg.keyPass.ifEmpty { cfg.storePass }
        )
        log("Signing: keystore sendiri (alias ${cfg.alias.trim()})")
    }

    val gh = GitHub(store.token)
    val owner = gh.user()
    log("Login sebagai $owner")

    val persistent = store.persistent(project)
    val pub = store.isPublic(project)
    val slug = project.lowercase().replace(Regex("[^a-z0-9-]"), "-")
    val repo: String
    val branch: String
    if (persistent) {
        repo = "appforge-$slug"
        val info = gh.repoInfo(owner, repo)
        if (info == null) {
            branch = gh.createRepo(repo, MARK, !pub)
            log("Repo tetap dibuat: $repo (${if (pub) "publik" else "privat"})")
        } else {
            val desc = if (info.isNull("description")) "" else info.getString("description")
            if (desc != MARK) error("Repo $repo sudah ada dan bukan buatan AppForge. Ganti nama proyek atau matikan 'Repo tetap'.")
            branch = info.optString("default_branch", "main")
            log("Memakai repo tetap: $repo")
            if (info.optBoolean("private", true) == pub) {
                val c = gh.setPrivate(owner, repo, !pub)
                log(if (c == 200) "Visibilitas repo diubah jadi ${if (pub) "publik" else "privat"}" else "Visibilitas repo gagal diubah (HTTP $c)")
            }
        }
    } else {
        repo = "appforge-$slug-${System.currentTimeMillis() / 1000}"
        branch = gh.createRepo(repo, MARK, !pub)
        log("Repo sementara dibuat: $repo")
    }

    try {
        log("Memeriksa ${paths.size} file di proyek...")
        val gp = GitPush(File(store.app.filesDir, "git"), store.token)
        val up = try {
            gp.push(owner, repo, branch, dir, paths, memFiles, "AppForge build", { log(it) }, onUpload, cancelled)
        } finally {
            if (!persistent) runCatching { gp.gitDirFor(owner, repo).deleteRecursively() }
        }
        log(if (up == 0) "Tidak ada perubahan file" else "$up file baru/berubah terkirim")

        val before = gh.latestRun(owner, repo)
        gh.dispatch(owner, repo, branch, inputs)
        log("Workflow dijalankan, menunggu runner...")

        var runId = 0L
        repeat(20) {
            if (runId == 0L) {
                val r = gh.latestRun(owner, repo)
                if (r > before) runId = r else Thread.sleep(3000)
            }
        }
        if (runId == 0L) error("Run tidak ditemukan")
        onRun(owner, repo, runId)

        val seen = mutableSetOf<String>()
        var jobId = 0L
        var conclusion = ""
        var cancelSent = false
        var liveOk = false
        var liveLen = -1
        while (true) {
            if (!cancelSent && cancelled()) {
                runCatching { gh.cancelRun(owner, repo, runId) }
                cancelSent = true
                log("Membatalkan build...")
            }
            val (status, concl) = gh.runState(owner, repo, runId)
            val (jid, steps) = gh.jobs(owner, repo, runId)
            if (jid != 0L) jobId = jid
            onSteps(steps)
            // Log asli job (keluaran Gradle) ikut ditampilkan selagi berjalan; bila belum tersedia, tampilkan perubahan status langkah.
            if (jobId != 0L) {
                val raw = runCatching { gh.jobLogLive(owner, repo, jobId) }.getOrNull()
                if (raw != null) {
                    liveOk = true
                    if (raw.length != liveLen) {
                        liveLen = raw.length
                        live(raw)
                    }
                }
            }
            if (!liveOk) steps.forEach { s ->
                val line = "• ${s.name}: ${s.state}"
                if (seen.add(line)) log(line)
            }
            if (status == "completed") { conclusion = concl; break }
            // Interval cek diperlambat bila beberapa build jalan bersamaan, supaya total permintaan ke API GitHub tetap sama (batas 5000/jam).
            Thread.sleep(5000L * BuildManager.running().size.coerceAtLeast(1))
        }

        val rawFull = if (jobId != 0L) gh.jobLog(owner, repo, jobId) else ""
        val full = if (jobId != 0L) cleanLog(rawFull) else "(log tidak tersedia)"
        if (conclusion == "cancelled") return BuildResult(false, "Dibatalkan", full, "none", rawFull)
        if (conclusion != "success") return BuildResult(false, "Build gagal ($conclusion)", full, "none", rawFull)

        val needsApk = Templates.needsArtifact(cfg.template) && !bundle
        var url: String? = null
        repeat(if (needsApk || bundle) 10 else 3) {
            if (url == null) {
                url = gh.artifactUrl(owner, repo, runId)
                if (url == null) Thread.sleep(3000)
            }
        }
        val artifact = url
        if (artifact == null) {
            return if (needsApk) BuildResult(false, "Artifact APK tidak ditemukan (cek path upload-artifact)", full, "none", rawFull)
            else if (bundle) BuildResult(false, "Artifact AAB tidak ditemukan (cek path upload-artifact)", full, "none", rawFull)
            else BuildResult(true, "Berhasil (workflow tidak menghasilkan artifact)", full, "none", rawFull)
        }
        log("Mengunduh hasil build...")
        val zip = gh.download(artifact)

        var found = false
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".apk")) {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { z.copyTo(it) }
                    found = true
                    break
                }
                e = z.nextEntry
            }
        }
        if (found) return BuildResult(true, "Berhasil", full, "apk", rawFull)
        if (needsApk) return BuildResult(false, "Tidak ada file .apk di dalam artifact", full, "none", rawFull)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(zip)
        return BuildResult(true, "Berhasil", full, "zip", rawFull)
    } finally {
        if (!persistent) runCatching { gh.deleteRepo(owner, repo) }
    }
}
