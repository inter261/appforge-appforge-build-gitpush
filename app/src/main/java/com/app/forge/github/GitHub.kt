package com.app.forge.github

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Kemajuan unggah file: [done] dari [total] file, [bytesDone] dari [bytesTotal] byte; [current] = file yang baru terkirim. */
data class UploadProgress(val done: Int, val total: Int, val bytesDone: Long, val bytesTotal: Long, val current: String = "")

fun fmtSize(n: Long): String =
    if (n >= 1048576) "%.1f MB".format(n / 1048576.0) else if (n >= 1024) "${n / 1024} KB" else "$n B"

/** [startedAt]/[completedAt] dalam ms epoch (0 = belum). */
data class Step(val name: String, val state: String, val seconds: Int = -1, val startedAt: Long = 0, val completedAt: Long = 0)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

private fun millis(a: String): Long = runCatching { java.time.Instant.parse(a).toEpochMilli() }.getOrDefault(0L)

private fun secs(a: String, b: String): Int =
    runCatching { java.time.Duration.between(java.time.Instant.parse(a), java.time.Instant.parse(b)).seconds.toInt() }.getOrDefault(-1)

class GitHub(private val token: String) {
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val api = "https://api.github.com"

    private fun builder(url: String) = Request.Builder().url(url)
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        .header("Accept", "application/vnd.github+json")

    private fun send(r: Request): Pair<Int, String> =
        http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }

    private fun get(path: String) = send(builder(api + path).build())
    private fun post(path: String, body: String) =
        send(builder(api + path).post(body.toRequestBody(json)).build())

    private fun must(r: Pair<Int, String>, what: String, ok: Int = 200): String {
        if (r.first != ok) error("Gagal $what (HTTP ${r.first}): ${r.second.take(200)}")
        return r.second
    }

    fun user(): String {
        val (c, b) = get("/user")
        if (c != 200) error("Token ditolak (HTTP $c)")
        return JSONObject(b).getString("login")
    }

    fun repoInfo(owner: String, repo: String): JSONObject? {
        val (c, b) = get("/repos/$owner/$repo")
        return if (c == 200) JSONObject(b) else null
    }

    /** Mengembalikan nama default branch. */
    fun createRepo(name: String, description: String, private: Boolean = true): String {
        val body = JSONObject().put("name", name).put("private", private)
            .put("auto_init", true).put("description", description).toString()
        val b = must(post("/user/repos", body), "membuat repo", 201)
        return JSONObject(b).str("default_branch").ifEmpty { "main" }
    }

    private fun gitSha(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${data.size}\u0000".toByteArray())
        md.update(data)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Satu commit berisi seluruh isi proyek (file yang sudah tidak ada ikut terhapus).
     * Hanya file baru/berubah yang diunggah. Mengembalikan jumlah file yang diunggah.
     */
    fun commitAll(
        owner: String, repo: String, branch: String, files: Map<String, ByteArray>, message: String,
        note: (String) -> Unit = {},
        progress: (UploadProgress) -> Unit = {}
    ): Int {
        val refPath = "/repos/$owner/$repo/git/ref/heads/$branch"
        var r = get(refPath)
        repeat(5) { if (r.first != 200) { Thread.sleep(2000); r = get(refPath) } }
        val head = JSONObject(must(r, "membaca branch")).getJSONObject("object").getString("sha")
        val treeSha = JSONObject(must(get("/repos/$owner/$repo/git/commits/$head"), "membaca commit"))
            .getJSONObject("tree").getString("sha")

        val existing = HashMap<String, String>()
        val arr = JSONObject(must(get("/repos/$owner/$repo/git/trees/$treeSha?recursive=1"), "membaca tree"))
            .getJSONArray("tree")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("type") == "blob") existing[t.getString("path")] = t.getString("sha")
        }

        val entries = JSONArray()
        val maxLines = 300
        // Tahap 1: cari file yang benar-benar baru/berubah, supaya jumlah dan ukuran total sudah diketahui sebelum mengunggah.
        val shas = HashMap<String, String>()
        val changed = ArrayList<String>()
        var bytesTotal = 0L
        for ((path, data) in files) {
            val sha = gitSha(data)
            shas[path] = sha
            if (existing[path] != sha) { changed.add(path); bytesTotal += data.size }
        }
        note("Berubah: ${changed.size} dari ${files.size} file, total ${fmtSize(bytesTotal)}")
        progress(UploadProgress(0, changed.size, 0, bytesTotal))
        // Tahap 2: unggah satu per satu dan laporkan kemajuannya.
        var uploaded = 0
        var bytesDone = 0L
        for (path in changed) {
            val data = files.getValue(path)
            val body = JSONObject().put("content", Base64.encodeToString(data, Base64.NO_WRAP))
                .put("encoding", "base64").toString()
            shas[path] = JSONObject(must(post("/repos/$owner/$repo/git/blobs", body), "mengunggah $path", 201))
                .getString("sha")
            uploaded++
            bytesDone += data.size
            if (uploaded <= maxLines) note("  [$uploaded/${changed.size}] + $path (${fmtSize(data.size.toLong())})")
            else if (uploaded == maxLines + 1) note("  … daftar dipotong, unggahan terus berjalan")
            progress(UploadProgress(uploaded, changed.size, bytesDone, bytesTotal, path))
        }
        for ((path, _) in files) {
            entries.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", shas.getValue(path)))
        }

        val removed = existing.keys.filter { it !in files }
        removed.take(maxLines).forEach { note("  − $it (dihapus)") }
        if (removed.size > maxLines) note("  … dan ${removed.size - maxLines} file terhapus lainnya")
        val same = files.size - uploaded
        if (same > 0) note("  = $same file tidak berubah (dilewati)")

        val newTree = JSONObject(
            must(post("/repos/$owner/$repo/git/trees", JSONObject().put("tree", entries).toString()), "membuat tree", 201)
        ).getString("sha")
        if (newTree == treeSha) return 0 // tidak ada perubahan

        val commit = JSONObject(
            must(
                post(
                    "/repos/$owner/$repo/git/commits",
                    JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(head)).toString()
                ), "membuat commit", 201
            )
        ).getString("sha")
        val body = JSONObject().put("sha", commit).toString().toRequestBody(json)
        must(send(builder("$api/repos/$owner/$repo/git/refs/heads/$branch").patch(body).build()), "memperbarui branch")
        return uploaded
    }

    fun dispatch(owner: String, repo: String, ref: String, inputs: Map<String, String> = emptyMap()) {
        val j = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) {
            val inp = JSONObject()
            inputs.forEach { (k, v) -> inp.put(k, v) }
            j.put("inputs", inp)
        }
        val body = j.toString()
        var last = ""
        repeat(10) {
            val (c, resp) = post("/repos/$owner/$repo/actions/workflows/build.yml/dispatches", body)
            if (c == 204) return
            last = "HTTP $c: ${resp.take(160)}"
            Thread.sleep(3000)
        }
        error("Gagal menjalankan workflow ($last). Cek scope token ('workflow') dan pastikan build.yml punya trigger workflow_dispatch serta input yang sesuai.")
    }

    fun latestRun(owner: String, repo: String): Long {
        val (c, b) = get("/repos/$owner/$repo/actions/runs?event=workflow_dispatch&per_page=1")
        if (c != 200) return 0
        val a = JSONObject(b).getJSONArray("workflow_runs")
        return if (a.length() > 0) a.getJSONObject(0).getLong("id") else 0
    }

    fun cancelRun(owner: String, repo: String, id: Long) {
        post("/repos/$owner/$repo/actions/runs/$id/cancel", "{}")
    }

    /** status to conclusion */
    fun runState(owner: String, repo: String, id: Long): Pair<String, String> {
        val j = JSONObject(get("/repos/$owner/$repo/actions/runs/$id").second)
        return j.str("status") to j.str("conclusion")
    }

    fun jobs(owner: String, repo: String, id: Long): Pair<Long, List<Step>> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$id/jobs").second).getJSONArray("jobs")
        if (arr.length() == 0) return 0L to emptyList()
        val job = arr.getJSONObject(0)
        val steps = job.optJSONArray("steps")
        val list = (0 until (steps?.length() ?: 0)).map {
            val s = steps!!.getJSONObject(it)
            Step(
                s.str("name"), s.str("conclusion").ifEmpty { s.str("status") }, secs(s.str("started_at"), s.str("completed_at")),
                millis(s.str("started_at")), millis(s.str("completed_at"))
            )
        }
        return job.getLong("id") to list
    }

    /** Log lengkap satu job (teks mentah). */
    fun jobLog(owner: String, repo: String, jobId: Long): String {
        val (c, b) = get("/repos/$owner/$repo/actions/jobs/$jobId/logs")
        return if (c == 200) b else "(log tidak tersedia, HTTP $c)"
    }

    /** Log job yang sedang berjalan: null bila belum tersedia (mis. HTTP 404 sebelum ada langkah selesai). */
    fun jobLogLive(owner: String, repo: String, jobId: Long): String? {
        val (c, b) = get("/repos/$owner/$repo/actions/jobs/$jobId/logs")
        return if (c == 200 && b.isNotBlank()) b else null
    }

    fun artifactUrl(owner: String, repo: String, id: Long): String? {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$id/artifacts").second).getJSONArray("artifacts")
        return if (arr.length() > 0) arr.getJSONObject(0).getString("archive_download_url") else null
    }

    /** ZIP isi repo pada [ref] (branch/tag); null = default branch. Repo publik bisa diunduh tanpa token. */
    fun zipball(owner: String, repo: String, ref: String?): ByteArray =
        download("$api/repos/$owner/$repo/zipball" + (if (ref.isNullOrBlank()) "" else "/$ref"))

    fun download(url: String): ByteArray =
        http.newCall(builder(url).build()).execute().use {
            if (!it.isSuccessful) error("Unduh gagal (HTTP ${it.code})")
            it.body!!.bytes()
        }

    /** Ubah visibilitas repo. Mengembalikan kode HTTP (200 = berhasil). */
    fun setPrivate(owner: String, repo: String, private: Boolean): Int =
        send(builder("$api/repos/$owner/$repo").patch(JSONObject().put("private", private).toString().toRequestBody(json)).build()).first

    /** Seperti [deleteRepo] tetapi mengembalikan kode HTTP (204 = berhasil) supaya kegagalan bisa dilaporkan. */
    fun deleteRepoCode(owner: String, repo: String): Int =
        send(builder("$api/repos/$owner/$repo").delete().build()).first

    fun deleteRepo(owner: String, repo: String) {
        send(builder("$api/repos/$owner/$repo").delete().build())
    }
}
