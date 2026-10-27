package com.app.forge.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.app.forge.R
import com.app.forge.app
import com.app.forge.data.Storage
import com.app.forge.data.StorageOps
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/** Pengaturan: token GitHub, tema dan lokasi penyimpanan proyek. */
class SettingsActivity : AppCompatActivity() {
    private val store get() = app.store
    private lateinit var token: TextInputLayout
    private lateinit var rootText: TextView

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val tb = MaterialToolbar(this)
        tb.title = "Pengaturan"
        tb.setNavigationIcon(R.drawable.ic_close)
        tb.setNavigationOnClickListener { finish() }
        root.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(8), dp(16), dp(32))
        val scroll = ScrollView(this)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        fun add(v: View, topDp: Int = 8) = body.addView(
            v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(topDp) }
        )
        fun header(t: String) {
            val h = TextView(this)
            h.text = t
            h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            add(h, 24)
        }

        header("GitHub")
        token = field("Personal access token (scope repo + workflow)", store.token, secret = true)
        add(token)
        val tokenHint = TextView(this)
        tokenHint.text = "Dipakai untuk mengirim proyek, menjalankan workflow, dan mengunduh APK hasil build. Hanya tersimpan di perangkat ini."
        tokenHint.textSize = 12f
        add(tokenHint, 4)

        header("Tampilan")
        val dark = MaterialSwitch(this)
        dark.text = "Mode gelap"
        dark.isChecked = store.darkTheme
        dark.setOnCheckedChangeListener { _, c ->
            store.darkTheme = c
            AppCompatDelegate.setDefaultNightMode(if (c) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        add(dark)

        header("Penyimpanan proyek")
        rootText = TextView(this)
        add(rootText)
        val change = Button(this)
        change.text = "Ubah lokasi…"
        change.setOnClickListener { showLocations() }
        add(change)
        refreshRoot()

        header("Tentang")
        val about = TextView(this)
        about.text = "AppForge Build — kirim proyek ke GitHub, build lewat GitHub Actions, pasang APK. Edit kodenya di editor pilihanmu (mis. Acode)."
        add(about)
    }

    override fun onPause() {
        super.onPause()
        store.token = token.value.trim()
    }

    private fun refreshRoot() {
        rootText.text = "Lokasi: ${store.root.path}" + (store.rootWarning?.let { "\n$it" } ?: "")
    }

    private fun showLocations() {
        val built = Storage.builtInLocations(this)
        val labels = built.map { it.label + "\n" + it.dir.path } + "Folder bebas (pilih sendiri)…"
        MaterialAlertDialogBuilder(this)
            .setTitle("Lokasi penyimpanan proyek")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < built.size) chooseRoot(built[i].dir) else pickFreeFolder()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickFreeFolder() {
        if (!Storage.hasFreeFolderAccess(this)) {
            toast("Izinkan \"Akses semua file\" untuk aplikasi ini, lalu pilih folder lagi")
            try { startActivity(Storage.allFilesAccessIntent(this)) }
            catch (e: Exception) { runCatching { startActivity(Storage.allFilesAccessFallbackIntent()) } }
            return
        }
        pickTree.launch(null)
    }

    private fun onTree(uri: Uri) {
        val dir = Storage.treeUriToDir(uri)
        if (dir == null) toast("Pilih folder di memori HP atau kartu SD") else chooseRoot(dir)
    }

    private fun chooseRoot(dir: File) {
        bg({ StorageOps.checkWritable(dir) }) { r ->
            val err = r.getOrNull()
            if (err != null) { toast(err); return@bg }
            if (dir == store.root) return@bg
            if (store.projects().isEmpty()) {
                store.setRoot(dir); refreshRoot(); return@bg
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Pindahkan proyek?")
                .setMessage("Proyek yang sudah ada bisa ikut dipindah ke ${dir.path}.")
                .setPositiveButton("Pindahkan") { _, _ ->
                    toast("Memindahkan…")
                    bg({ val m = store.moveProjectsTo(dir); store.setRoot(dir); m }) { m ->
                        m.onSuccess { toast("${it.moved.size} proyek dipindah" + if (it.skipped.isNotEmpty()) ", ${it.skipped.size} dilewati" else "") }
                            .onFailure { toast("Gagal: ${it.msg()}") }
                        refreshRoot()
                    }
                }
                .setNeutralButton("Jangan pindahkan") { _, _ -> store.setRoot(dir); refreshRoot() }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
