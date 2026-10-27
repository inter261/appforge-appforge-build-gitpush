package com.app.forge.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.app.forge.data.Store
import com.app.forge.github.fmtSize
import com.app.forge.template.Templates
import java.io.File

private class DocNode(val id: String, val name: String, val isDir: Boolean)

private fun children(ctx: Context, tree: Uri, parentId: String): List<DocNode> {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val out = ArrayList<DocNode>()
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            out.add(DocNode(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
        }
    }
    return out
}

/** Nama folder yang dipilih lewat pemilih folder Android. */
fun folderDisplayName(ctx: Context, tree: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(tree)
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    val name = ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    return name ?: id.substringAfterLast('/').substringAfterLast(':')
}

private class Copied(val count: Int, val bytes: Long)

/** Salin isi folder pilihan ke [dest]. Yang dilewati sama seperti impor ZIP (build/, .git, .gradle, dst). Blocking. */
private fun copyTree(ctx: Context, tree: Uri, dest: File): Copied {
    var count = 0
    var bytes = 0L
    fun walk(parentId: String, rel: String) {
        for (n in children(ctx, tree, parentId)) {
            if (n.name.isEmpty()) continue
            val path = if (rel.isEmpty()) n.name else rel + "/" + n.name
            if (isSkippedPath(path)) continue
            if (n.isDir) { walk(n.id, path); continue }
            val src = DocumentsContract.buildDocumentUriUsingTree(tree, n.id)
            val ins = ctx.contentResolver.openInputStream(src) ?: continue
            val f = File(dest, path)
            f.parentFile?.mkdirs()
            ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            count++
            bytes += f.length()
        }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), "")
    return Copied(count, bytes)
}

/** Buat proyek baru dari isi sebuah folder. Repo GitHub dibuat otomatis oleh build pertama. Blocking: panggil dari thread IO. */
fun importFolder(ctx: Context, store: Store, tree: Uri, rawName: String, publicRepo: Boolean): ImportResult {
    val base = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifEmpty { "proyek" }
    var name = base
    var n = 2
    while (File(store.root, name).exists()) name = base + "-" + (n++)
    val dest = File(store.root, name)
    val r = try {
        copyTree(ctx, tree, dest)
    } catch (e: Exception) {
        dest.deleteRecursively()
        throw e
    }
    if (r.count == 0) {
        dest.deleteRecursively()
        error("Folder kosong, atau semua isinya dilewati (build/, .git, .gradle)")
    }
    val tpl = store.initImported(name)
    store.setPublic(name, publicRepo)
    return ImportResult(name, "$name (${r.count} file, ${fmtSize(r.bytes)}, ${Templates.label(tpl)}). Repo GitHub dibuat otomatis saat Build pertama.")
}
