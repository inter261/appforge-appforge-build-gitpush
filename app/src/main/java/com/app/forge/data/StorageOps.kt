package com.app.forge.data

import java.io.File

/** Hasil memindahkan proyek ke lokasi baru. */
class MoveResult(val moved: List<String>, val skipped: List<String>)

/** Operasi penyimpanan yang tidak bergantung pada Android (supaya mudah diuji). */
object StorageOps {
    /**
     * Mengubah document id dari pemilih folder Android ("primary:Documents/App" atau "1A2B-3C4D:Proyek")
     * menjadi path file. [primaryRoot] biasanya /storage/emulated/0.
     */
    fun docIdToPath(docId: String, primaryRoot: String): String? {
        val i = docId.indexOf(':')
        if (i <= 0) return null
        val volume = docId.substring(0, i)
        val rel = docId.substring(i + 1).trim('/')
        val base = if (volume == "primary") primaryRoot else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** Pesan error kalau [dir] tidak bisa dipakai untuk menyimpan proyek, atau null kalau aman. */
    fun checkWritable(dir: File): String? = try {
        if (!dir.exists() && !dir.mkdirs()) {
            "Tidak bisa membuat folder: ${dir.path}"
        } else {
            val t = File(dir, ".forge-test")
            t.writeText("ok")
            t.delete()
            null
        }
    } catch (e: Exception) {
        "Tidak bisa menulis ke ${dir.path}: ${e.message}"
    }

    /** Memindahkan folder-folder proyek [names] dari [src] ke [dst]. Yang namanya sudah ada di tujuan atau gagal disalin dilewati (aslinya tetap utuh). */
    fun moveProjects(src: File, dst: File, names: List<String>): MoveResult {
        val moved = ArrayList<String>()
        val skipped = ArrayList<String>()
        if (src.canonicalPath == dst.canonicalPath) return MoveResult(moved, skipped)
        dst.mkdirs()
        for (p in names) {
            val from = File(src, p)
            val to = File(dst, p)
            if (!from.isDirectory) continue
            if (to.exists()) {
                skipped += p
                continue
            }
            val ok = runCatching { from.copyRecursively(to, overwrite = false) { _, _ -> OnErrorAction.TERMINATE } }.getOrDefault(false)
            if (ok) {
                from.deleteRecursively()
                moved += p
            } else {
                to.deleteRecursively() // salinan setengah jadi dibuang; aslinya tidak disentuh
                skipped += p
            }
        }
        return MoveResult(moved, skipped)
    }

    /** Sisa ruang di [dir] (atau induk terdekat yang sudah ada), dalam byte; -1 kalau tidak diketahui. */
    fun freeBytes(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: -1L
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }
}
