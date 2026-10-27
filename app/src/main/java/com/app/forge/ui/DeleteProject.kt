package com.app.forge.ui

import android.app.Activity
import android.widget.Toast
import com.app.forge.app
import com.app.forge.build.deleteProjectRepo
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Hapus proyek: folder di perangkat, repo di GitHub, atau keduanya.
 * Repo hanya dihapus kalau memang dibuat oleh AppForge (ditandai di deskripsinya) dan token punya scope delete_repo.
 * Urutannya repo dulu: kalau gagal, folder lokal tidak ikut hilang.
 */
fun Activity.confirmDeleteProject(name: String, onDone: (localDeleted: Boolean) -> Unit) {
    val store = app.store
    val pick = booleanArrayOf(true, store.origin(name).isNotEmpty() || store.persistent(name))
    MaterialAlertDialogBuilder(this)
        .setTitle("Hapus “$name”?")
        .setMultiChoiceItems(arrayOf("Folder di perangkat", "Repo di GitHub (hanya repo buatan AppForge)"), pick) { _, i, c -> pick[i] = c }
        .setNegativeButton("Batal", null)
        .setPositiveButton("Hapus") { _, _ ->
            if (!pick[0] && !pick[1]) { Toast.makeText(this, "Pilih yang mau dihapus", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            bg({
                val notes = ArrayList<String>()
                if (pick[1]) {
                    val r = runCatching { deleteProjectRepo(store, name) }
                    if (r.isFailure) {
                        // Repo gagal dihapus: hentikan, supaya folder lokal tidak hilang tanpa alasan.
                        error("Repo GitHub tidak dihapus: ${r.exceptionOrNull()!!.msg()}")
                    }
                    notes.add(if (r.getOrDefault(false)) "Repo GitHub dihapus" else "Repo GitHub tidak ada")
                }
                if (pick[0]) { store.delete(name); notes.add("Folder dihapus") }
                notes.joinToString(" · ")
            }) { r ->
                r.onSuccess { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); onDone(pick[0]) }
                    .onFailure { Toast.makeText(this, it.msg(), Toast.LENGTH_LONG).show() }
            }
        }
        .show()
}
