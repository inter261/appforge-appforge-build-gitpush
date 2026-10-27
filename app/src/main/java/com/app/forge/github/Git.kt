package com.app.forge.github

import com.app.forge.data.Store
import com.app.forge.util.ImportResult
import com.app.forge.util.isSkippedPath
import com.app.forge.util.readZipEntries
import java.io.File

/** Repo GitHub yang terhubung ke sebuah proyek. */
class Origin(val owner: String, val repo: String, val branch: String) {
    override fun toString() = "$owner/$repo@$branch"

    companion object {
        fun parse(s: String): Origin? {
            val i = s.lastIndexOf('@')
            if (i <= 0) return null
            val parts = s.substring(0, i).split('/')
            return if (parts.size == 2) Origin(parts[0], parts[1], s.substring(i + 1)) else null
        }
    }
    }

private val URL_RE = Regex("^(?:https?://github\\.com/|git@github\\.com:|github\\.com/)([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/tree/([^\\s]+))?/?$")
private val SLUG_RE = Regex("^([\\w.-]+)/([\\w.-]+?)(?:\\.git)?$")

/** "owner/repo", URL https, atau git@github.com:owner/repo.git → (owner, repo, branch?) atau null. */
fun parseRepoRef(input: String): Triple<String, String, String?>? {
    val t = input.trim()
    URL_RE.find(t)?.let { return Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { null }) }
    SLUG_RE.find(t)?.let { return Triple(it.groupValues[1], it.groupValues[2], null) }
    return null
}

private fun localFiles(store: Store, project: String): Map<String, ByteArray> {
    val dir = File(store.root, project)
    val out = LinkedHashMap<String, ByteArray>()
    for (p in store.files(project)) {
        if (isSkippedPath(p)) continue
        val f = File(dir, p)
        if (f.length() > 0) out[p] = f.readBytes()
    }
    return out
}

/** Unduh repo sebagai proyek baru dan hubungkan ke asalnya. Blocking: panggil dari thread IO. */
fun cloneRepo(store: Store, input: String, branchOverride: String?): ImportResult {
    val ref = parseRepoRef(input) ?: error("Format tidak dikenali. Pakai owner/repo atau URL GitHub.")
    val (owner, repo, urlBranch) = ref
    val gh = GitHub(store.token)
    val info = gh.repoInfo(owner, repo)
        ?: error("Repo $owner/$repo tidak ditemukan. Kalau privat, isi token GitHub di Pengaturan.")
    val branch = branchOverride?.takeIf { it.isNotBlank() } ?: urlBranch ?: info.optString("default_branch", "main")
    val entries = readZipEntries(gh.zipball(owner, repo, branch))
    if (entries.isEmpty()) error("Repo kosong atau tidak ada file yang bisa diimpor")

    val base = repo.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "proyek" }
    var name = base
    var n = 2
    while (File(store.root, name).exists()) name = "$base-${n++}"
    val dest = File(store.root, name)
    for ((rel, data) in entries) {
        val f = File(dest, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }
    val tpl = store.initImported(name)
    store.setOrigin(name, Origin(owner, repo, branch).toString())
    return ImportResult(name, "$name (${entries.size} file, $owner/$repo@$branch, ${com.app.forge.template.Templates.label(tpl)})")
}

/** Kirim seluruh isi proyek sebagai satu commit ke repo terhubung. Mengembalikan jumlah file yang diunggah (0 = tidak ada perubahan). */
fun commitPush(store: Store, project: String, message: String, note: (String) -> Unit = {}): Int {
    val o = Origin.parse(store.origin(project)) ?: error("Proyek belum terhubung ke repo")
    if (store.token.isBlank()) error("Isi token GitHub di Pengaturan dulu")
    return GitHub(store.token).commitAll(o.owner, o.repo, o.branch, localFiles(store, project), message.ifBlank { "Update dari AppForge" }, note)
}

class PullResult(val updated: Int, val added: Int)

/** Tulis file remote yang berbeda atau belum ada secara lokal. File lokal yang tidak ada di remote dibiarkan. */
fun pullLatest(store: Store, project: String): PullResult {
    val o = Origin.parse(store.origin(project)) ?: error("Proyek belum terhubung ke repo")
    val entries = readZipEntries(GitHub(store.token).zipball(o.owner, o.repo, o.branch))
    val dir = File(store.root, project)
    var upd = 0
    var add = 0
    for ((rel, data) in entries) {
        val f = File(dir, rel)
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(data); add++ }
        else if (!f.readBytes().contentEquals(data)) { f.writeBytes(data); upd++ }
    }
    return PullResult(upd, add)
}

/** Buat repo GitHub baru dan hubungkan; belum mengirim file (panggil [commitPush] setelahnya). */
fun createOrigin(store: Store, project: String, name: String, private: Boolean, description: String): Origin {
    if (store.token.isBlank()) error("Isi token GitHub di Pengaturan dulu")
    val gh = GitHub(store.token)
    val owner = gh.user()
    val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
    if (clean.isEmpty()) error("Nama repo kosong")
    if (gh.repoInfo(owner, clean) != null) error("Repo $owner/$clean sudah ada. Pakai Hubungkan untuk repo yang sudah ada.")
    val branch = gh.createRepo(clean, description, private)
    val o = Origin(owner, clean, branch)
    store.setOrigin(project, o.toString())
    return o
}

/** Hubungkan proyek ke repo yang sudah ada. */
fun linkOrigin(store: Store, project: String, input: String, branchOverride: String?): Origin {
    val (owner, repo, urlBranch) = parseRepoRef(input) ?: error("Format tidak dikenali. Pakai owner/repo atau URL GitHub.")
    val info = GitHub(store.token).repoInfo(owner, repo) ?: error("Repo $owner/$repo tidak ditemukan atau token tidak punya akses")
    val branch = branchOverride?.takeIf { it.isNotBlank() } ?: urlBranch ?: info.optString("default_branch", "main")
    val o = Origin(owner, repo, branch)
    store.setOrigin(project, o.toString())
    return o
}
