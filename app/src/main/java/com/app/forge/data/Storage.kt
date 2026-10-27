package com.app.forge.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** Satu pilihan lokasi penyimpanan proyek. [needsAccess] = butuh izin "akses semua file". */
class StorageLocation(val label: String, val dir: File, val note: String, val needsAccess: Boolean = false)

/** Bagian penyimpanan yang bergantung pada Android: daftar lokasi, izin, dan konversi hasil pemilih folder. */
object Storage {
    /** Lokasi yang bisa dipakai tanpa izin khusus: internal, folder aplikasi di memori HP, dan di kartu SD. */
    fun builtInLocations(ctx: Context): List<StorageLocation> {
        val out = ArrayList<StorageLocation>()
        out.add(
            StorageLocation(
            "Penyimpanan internal aplikasi",
            File(ctx.filesDir, "projects"),
            "Privat dan paling aman. Tidak terlihat di pengelola file; ikut terhapus kalau aplikasi di-uninstall."
            )
        )
        ctx.getExternalFilesDirs(null).forEachIndexed { i, d ->
            if (d == null) return@forEachIndexed
            val removable = runCatching { Environment.isExternalStorageRemovable(d) }.getOrDefault(i > 0)
            val loc = if (removable) {
                StorageLocation(
                    "Kartu SD",
                    File(d, "projects"),
                    "Folder aplikasi di kartu SD. Tidak butuh izin tambahan; ikut terhapus kalau aplikasi di-uninstall."
                )
            } else {
                StorageLocation(
                    "Memori HP (folder aplikasi)",
                    File(d, "projects"),
                    "Folder aplikasi di memori HP. Tidak butuh izin tambahan; ikut terhapus kalau aplikasi di-uninstall."
                )
            }
            out.add(loc)
        }
        return out
    }

    /** Folder pilihan pengguna dari pemilih folder sistem; null kalau bukan folder di memori HP/kartu SD. */
    fun treeUriToDir(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val path = StorageOps.docIdToPath(docId, Environment.getExternalStorageDirectory().path) ?: return null
        return File(path)
    }

    /** Apakah aplikasi boleh menulis ke folder bebas di luar folder aplikasinya. */
    fun hasFreeFolderAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+: halaman pengaturan "Akses semua file" untuk aplikasi ini. */
    fun allFilesAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun allFilesAccessFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
