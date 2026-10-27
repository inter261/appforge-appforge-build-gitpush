package com.app.forge.data

import android.content.Context
import com.app.forge.template.NewSpec
import com.app.forge.template.Templates
import com.app.forge.template.WORKFLOW_PATH
import org.json.JSONObject
import java.io.File

class Store(context: Context) {
    val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences("appforge", Context.MODE_PRIVATE)

    // TODO: pindahkan token & password keystore ke EncryptedSharedPreferences / Android Keystore
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /** File yang terakhir dibuka di IDE (terbaru di depan), disimpan per proyek. */
    fun recents(project: String): List<String> =
        (prefs.getString("recent:$project", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun setRecents(project: String, list: List<String>) =
        prefs.edit().putString("recent:$project", list.joinToString("\n")).apply()

    /** Tab yang terbuka saat proyek terakhir ditutup (urutan tab) dan tab aktifnya. */
    fun openTabs(project: String): Pair<List<String>, String> =
        (prefs.getString("tabs:$project", "") ?: "").split('\n').filter { it.isNotBlank() } to (prefs.getString("tabcur:$project", "") ?: "")

    fun setOpenTabs(project: String, paths: List<String>, current: String?) =
        prefs.edit().putString("tabs:$project", paths.joinToString("\n")).putString("tabcur:$project", current ?: "").apply()

    /** Petunjuk inlay (tipe val/var) di editor Kotlin: tampil atau disembunyikan. */
    var inlayHints: Boolean
        get() = prefs.getBoolean("inlay", true)
        set(v) = prefs.edit().putBoolean("inlay", v).apply()

    /** Bungkus baris panjang di editor. */
    var wordWrap: Boolean
        get() = prefs.getBoolean("wrap", false)
        set(v) = prefs.edit().putBoolean("wrap", v).apply()

    /** Tema warna sintaks editor: "auto" (mengikuti tema aplikasi) atau nama tema TextMate. */
    var editorTheme: String
        get() = prefs.getString("editor_theme", "auto") ?: "auto"
        set(v) = prefs.edit().putString("editor_theme", v).apply()

    /** Bookmark per proyek: path -> nomor baris (berbasis 0). */
    fun bookmarks(project: String): Map<String, Set<Int>> =
        (prefs.getString("bm:$project", "") ?: "").split('\n').mapNotNull { l ->
            val i = l.lastIndexOf(':')
            if (i <= 0) null else l.substring(0, i) to (l.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    fun setBookmarks(project: String, all: Map<String, Set<Int>>) =
        prefs.edit().putString("bm:$project", all.entries.flatMap { (p, ls) -> ls.map { "$p:$it" } }.joinToString("\n")).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark", true)
        set(v) = prefs.edit().putBoolean("dark", v).apply()

    /** Repo GitHub yang terhubung ke proyek, format "owner/repo@branch"; kosong = belum terhubung. */
    fun origin(project: String): String = prefs.getString("origin:$project", "") ?: ""
    fun setOrigin(project: String, v: String) = prefs.edit().putString("origin:$project", v).apply()

    fun persistent(project: String): Boolean = prefs.getBoolean("persist:$project", true)
    fun setPersistent(project: String, v: Boolean) = prefs.edit().putBoolean("persist:$project", v).apply()

    /** Visibilitas repo GitHub buatan AppForge: false = privat (bawaan), true = publik. */
    fun isPublic(project: String): Boolean = prefs.getBoolean("pub:$project", false)
    fun setPublic(project: String, v: Boolean) = prefs.edit().putBoolean("pub:$project", v).apply()

    /** Lokasi bawaan: penyimpanan internal aplikasi. */
    val defaultRoot: File = File(app.filesDir, "projects").also { it.mkdirs() }

    /** Pesan kalau lokasi pilihan tidak bisa diakses saat aplikasi dibuka (mis. kartu SD dicabut), sehingga sementara dipakai lokasi bawaan. */
    var rootWarning: String? = null
        private set

    /** Folder tempat semua proyek disimpan; bisa diganti lewat [setRoot]. */
    var root: File = resolveRoot()
        private set

    private fun resolveRoot(): File {
        rootWarning = null
        val p = prefs.getString("root", "") ?: ""
        if (p.isBlank()) return defaultRoot
        val f = File(p)
        val err = StorageOps.checkWritable(f)
        if (err != null) {
            rootWarning = "Lokasi penyimpanan $p tidak bisa diakses ($err). Sementara memakai penyimpanan internal."
            return defaultRoot
        }
        return f
    }

    /** Mengganti lokasi penyimpanan proyek (null = bawaan). Proyek yang sudah ada tidak dipindah; pakai [moveProjectsTo] dulu kalau perlu. */
    fun setRoot(dir: File?) {
        prefs.edit().putString("root", if (dir == null || dir == defaultRoot) "" else dir.absolutePath).apply()
        root = resolveRoot()
    }

    /** Memindahkan semua proyek di lokasi sekarang ke [newRoot]. Blocking: panggil dari thread IO. */
    fun moveProjectsTo(newRoot: File): MoveResult = StorageOps.moveProjects(root, newRoot, projects())


    // Keystore disimpan di luar folder proyek, jadi tidak ikut ekspor ZIP maupun commit ke Git.
    private val keystoreDir: File = File(app.filesDir, "keystores").also { it.mkdirs() }
    private fun keystoreFile(project: String) = File(keystoreDir, "$project.jks")

    private fun safe(name: String, path: String): File {
        require(!path.contains("..") && !path.startsWith("/")) { "Path tidak valid" }
        return File(File(root, name), path)
    }

    fun projects(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Membuat proyek dari template; mengembalikan nama akhir (diberi akhiran -2, -3 kalau sudah ada).
     * Untuk Android, [spec] menentukan package/bahasa/SDK; tanpa [spec], package-nya com.appforge.<nama> yang belum dipakai.
     */
    fun create(rawName: String, template: String, spec: NewSpec? = null): String {
        val base = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "proyek" }
        var name = base
        var n = 2
        while (File(root, name).exists()) name = "$base-${n++}"
        val finalSpec = if (template == "android") spec ?: NewSpec(Templates.defaultPackage(rawName, packagesInUse())) else null
        Templates.starter(template, finalSpec, rawName.trim().ifEmpty { name }).forEach { (p, c) -> write(name, p, c) }
        saveConfig(name, ProjectConfig(template = template))
        applyWorkflow(name)
        return name
    }

    private val APP_ID_RE = Regex("applicationId\\s*=?\\s*[\"']([A-Za-z][\\w.]*)[\"']")

    /** applicationId semua proyek yang ada, ditambah package AppForge sendiri: nama yang tidak boleh dipakai proyek baru. */
    fun packagesInUse(): Set<String> {
        val out = HashSet<String>()
        out += app.packageName
        for (name in projects()) {
            val dir = File(root, name)
            val gradles = dir.walkTopDown().maxDepth(3)
                .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
            for (g in gradles) {
                val id = runCatching { APP_ID_RE.find(g.readText())?.groupValues?.get(1) }.getOrNull()
                if (id != null) out += id
            }
        }
        return out
    }

    /** Dipanggil setelah impor ZIP: deteksi template, dan buat build.yml kalau proyek belum punya. */
    fun initImported(name: String): String {
        val tpl = Templates.detect(files(name))
        saveConfig(name, ProjectConfig(template = tpl))
        if (workflowText(name) == null) applyWorkflow(name)
        return tpl
    }

    fun files(name: String): List<String> {
        val d = File(root, name)
        return d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path }.sorted().toList()
    }

    /** Sidik jari isi proyek (path, ukuran, waktu ubah semua file dan folder); berubah kalau ada yang ditambah/diubah/dihapus dari luar. */
    fun signature(name: String): Long {
        var h = 1125899906842597L
        for (f in File(root, name).walkTopDown()) {
            h = 31 * h + f.path.hashCode()
            h = 31 * h + f.lastModified()
            h = 31 * h + f.length()
        }
        return h
    }

    /** Folder di dalam proyek (termasuk yang masih kosong), path relatif. */
    fun dirs(name: String): List<String> {
        val d = File(root, name)
        return d.walkTopDown().filter { it.isDirectory && it != d }.map { it.relativeTo(d).path }.sorted().toList()
    }

    /** File/folder di dalam proyek; path kosong = folder proyek itu sendiri. */
    fun file(name: String, path: String): File = if (path.isEmpty()) File(root, name) else safe(name, path)

    fun read(name: String, path: String): String = safe(name, path).readText()

    fun write(name: String, path: String, text: String) {
        val f = safe(name, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    // ---- Operasi berkas (dipakai pohon berkas di IDE) ----

    private fun rel(name: String, f: File): String = f.relativeTo(File(root, name)).path

    private fun validName(n: String) {
        require(n.isNotBlank() && !n.contains('/') && n != "." && n != ".." && !n.contains("..")) { "Nama tidak valid" }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (f.exists()) f = File(dir, "$stem ($n)$ext").also { n++ }
        return f
    }

    fun mkdir(name: String, path: String) {
        require(!safe(name, path).exists()) { "Sudah ada: $path" }
        require(safe(name, path).mkdirs()) { "Gagal membuat folder" }
    }

    fun createFile(name: String, path: String) {
        require(!safe(name, path).exists()) { "Sudah ada: $path" }
        write(name, path, "")
    }

    /** Mengembalikan path baru. */
    fun rename(name: String, path: String, newName: String): String {
        validName(newName)
        val src = safe(name, path)
        val dst = File(src.parentFile, newName)
        require(dst == src || !dst.exists()) { "Sudah ada: $newName" }
        if (dst != src) require(src.renameTo(dst)) { "Gagal mengganti nama" }
        return rel(name, dst)
    }

    private fun destDir(name: String, dest: String): File =
        file(name, dest).also { require(it.isDirectory) { "Tujuan bukan folder" } }

    private fun insideSelf(src: File, dd: File): Boolean =
        src.isDirectory && (dd.canonicalPath == src.canonicalPath || dd.canonicalPath.startsWith(src.canonicalPath + "/"))

    /** Mengembalikan path baru. */
    fun move(name: String, path: String, dest: String): String {
        val src = safe(name, path)
        val dd = destDir(name, dest)
        require(!insideSelf(src, dd)) { "Folder tidak bisa dipindah ke dalam dirinya sendiri" }
        val dst = File(dd, src.name)
        if (dst == src) return path
        require(!dst.exists()) { "Sudah ada di tujuan: ${src.name}" }
        if (!src.renameTo(dst)) {
            require(src.copyRecursively(dst)) { "Gagal memindahkan" }
            src.deleteRecursively()
        }
        return rel(name, dst)
    }

    /** Mengembalikan path salinan (diberi akhiran (2), (3) kalau namanya sudah ada). */
    fun copy(name: String, path: String, dest: String): String {
        val src = safe(name, path)
        val dd = destDir(name, dest)
        require(!insideSelf(src, dd)) { "Folder tidak bisa disalin ke dalam dirinya sendiri" }
        val dst = unique(dd, src.name)
        require(src.copyRecursively(dst)) { "Gagal menyalin" }
        return rel(name, dst)
    }

    fun deletePath(name: String, path: String) {
        require(path.isNotEmpty()) { "Tidak bisa menghapus root" }
        safe(name, path).deleteRecursively()
    }

    /** Menaruh file dari luar (pengelola file) ke folder [dest]; mengembalikan path-nya. */
    fun importInto(name: String, dest: String, fileName: String, bytes: ByteArray): String {
        validName(fileName)
        val dd = destDir(name, dest)
        val dst = unique(dd, fileName)
        dst.writeBytes(bytes)
        return rel(name, dst)
    }

    fun delete(name: String) {
        File(root, name).deleteRecursively()
        keystoreFile(name).delete()
        prefs.edit().remove("cfg:$name").remove("persist:$name").remove("recent:$name").apply()
    }

    // ---- Pengaturan build ----

    fun config(project: String): ProjectConfig {
        val raw = prefs.getString("cfg:$project", null)
        if (raw != null) {
            val parsed = runCatching {
                val j = JSONObject(raw)
                ProjectConfig(
                    template = j.optString("tpl", "android"),
                    release = j.optBoolean("rel", false),
                    versionName = j.optString("vn"),
                    versionCode = j.optString("vc"),
                    sign = j.optBoolean("sign", false),
                    alias = j.optString("alias"),
                    storePass = j.optString("sp"),
                    keyPass = j.optString("kp"),
                    releaseMode = j.optString("rm", "none"),
                    releaseTag = j.optString("rt")
                )
            }.getOrNull()
            if (parsed != null) return parsed
        }
        // Proyek lama (sebelum ada pengaturan): tebak dari isi file.
        return ProjectConfig(template = Templates.detect(files(project)))
    }

    fun saveConfig(project: String, c: ProjectConfig) {
        val j = JSONObject().put("tpl", c.template).put("rel", c.release).put("vn", c.versionName)
            .put("vc", c.versionCode).put("sign", c.sign).put("alias", c.alias)
            .put("sp", c.storePass).put("kp", c.keyPass).put("rm", c.releaseMode).put("rt", c.releaseTag)
        prefs.edit().putString("cfg:$project", j.toString()).apply()
    }

    // ---- Workflow ----

    fun workflowText(project: String): String? =
        File(File(root, project), WORKFLOW_PATH).takeIf { it.isFile }?.readText()

    /** Menulis build.yml dari template + pengaturan saat ini (menimpa file yang ada). */
    fun applyWorkflow(project: String) {
        write(project, WORKFLOW_PATH, Templates.workflow(app, config(project)))
    }

    // ---- Keystore ----

    fun hasKeystore(project: String): Boolean = keystoreFile(project).isFile

    fun keystoreBytes(project: String): ByteArray? = keystoreFile(project).takeIf { it.isFile }?.readBytes()

    fun importKeystore(project: String, bytes: ByteArray) {
        // Keystore dikirim sebagai input workflow_dispatch (batas payload GitHub 65 KB, termasuk base64).
        require(bytes.isNotEmpty()) { "File keystore kosong" }
        require(bytes.size <= MAX_KEYSTORE) { "Keystore terlalu besar (maks ${MAX_KEYSTORE / 1000} KB)" }
        keystoreFile(project).writeBytes(bytes)
    }

    fun removeKeystore(project: String) {
        keystoreFile(project).delete()
    }

    companion object {
        const val MAX_KEYSTORE = 40_000
    }
}
