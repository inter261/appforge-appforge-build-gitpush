package com.app.forge.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.app.forge.data.Store
import com.app.forge.template.Templates
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val SKIP = setOf(".git", ".gradle", ".idea", "__MACOSX", ".DS_Store")

private fun skipped(rel: String): Boolean {
    if (rel.startsWith("/") || rel.contains("..") || rel == "local.properties") return true
    val seg = rel.split("/")
    return seg.indices.any { i ->
        seg[i] in SKIP || (seg[i] == "build" && "src" !in seg.take(i)) // folder hasil build, bukan paket "build" di dalam src
    }
}

/** Apakah path relatif ini dilewati saat impor/commit (folder build, .git, dst). */
fun isSkippedPath(rel: String): Boolean = skipped(rel)

/** Isi ZIP (folder induk tunggal dibuang) sebagai peta path → byte, dengan file yang dilewati sudah disaring. */
fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val raw = LinkedHashMap<String, ByteArray>()
    ZipInputStream(bytes.inputStream().buffered()).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (!e.isDirectory) raw[e.name] = z.readBytes()
            e = z.nextEntry
        }
    }
    val root = raw.keys.map { it.substringBefore('/', "") }.distinct().singleOrNull()
        ?.takeIf { r -> r.isNotEmpty() && raw.keys.all { it.startsWith("$r/") } } ?: ""
    val prefix = if (root.isEmpty()) "" else "$root/"
    val out = LinkedHashMap<String, ByteArray>()
    for ((k, v) in raw) {
        val rel = k.removePrefix(prefix)
        if (!skipped(rel)) out[rel] = v
    }
    return out
}

/** [name] = nama proyek hasil impor, [summary] = ringkasan untuk ditampilkan, mis. "MyApp (42 file, Android (Gradle))". */
class ImportResult(val name: String, val summary: String)

fun importZip(ctx: Context, store: Store, uri: Uri): ImportResult {
    val cr = ctx.contentResolver
    var base = "proyek"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) base = c.getString(0).removeSuffix(".zip")
    }
    val orig = base.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "proyek" }
    var name = orig
    var n = 2
    while (File(store.root, name).exists()) name = "$orig-${n++}"

    // Lewat pertama: cari folder induk tunggal (mis. "MyApp/") supaya bisa dibuang.
    val names = mutableListOf<String>()
    ZipInputStream((cr.openInputStream(uri) ?: error("Tidak bisa membuka file")).buffered()).use { z ->
        var e = z.nextEntry
        while (e != null) { if (!e.isDirectory) names += e.name; e = z.nextEntry }
    }
    if (names.isEmpty()) error("ZIP kosong")
    val root = names.map { it.substringBefore('/', "") }.distinct().singleOrNull()
        ?.takeIf { r -> r.isNotEmpty() && names.all { it.startsWith("$r/") } } ?: ""
    val prefix = if (root.isEmpty()) "" else "$root/"

    val dest = File(store.root, name)
    var count = 0
    ZipInputStream((cr.openInputStream(uri) ?: error("Tidak bisa membuka file")).buffered()).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (!e.isDirectory) {
                val rel = e.name.removePrefix(prefix)
                if (!skipped(rel)) {
                    val f = File(dest, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                    count++
                }
            }
            e = z.nextEntry
        }
    }
    if (count == 0) { dest.deleteRecursively(); error("Tidak ada file yang bisa diimpor") }
    val tpl = store.initImported(name)
    return ImportResult(name, "$name ($count file, ${Templates.label(tpl)})")
}

/** Nama tampilan sebuah URI (dipakai saat impor file dari pengelola file). */
fun displayName(ctx: Context, uri: Uri, fallback: String = "file"): String {
    var n = fallback
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) n = c.getString(0) ?: fallback
    }
    return n
}

/** Ekspor seluruh proyek (sub kosong) atau satu folder di dalamnya sebagai ZIP. */
fun exportZip(ctx: Context, store: Store, project: String, uri: Uri, sub: String = "") {
    val base = store.file(project, sub)
    val top = if (sub.isEmpty()) project else base.name
    ZipOutputStream((ctx.contentResolver.openOutputStream(uri) ?: error("Tidak bisa menulis file")).buffered()).use { z ->
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            z.putNextEntry(ZipEntry("$top/${f.relativeTo(base).path}"))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }
}

/** Menyalin file hasil build ke lokasi yang dipilih pengguna (Storage Access Framework). */
fun copyToUri(ctx: Context, src: File, uri: Uri) {
    val out = ctx.contentResolver.openOutputStream(uri) ?: error("Tidak bisa menulis file")
    out.use { o -> src.inputStream().use { it.copyTo(o) } }
}
