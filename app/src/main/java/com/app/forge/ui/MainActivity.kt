package com.app.forge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.app.forge.build.BuildManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.forge.R
import com.app.forge.app
import com.app.forge.github.cloneRepo
import com.app.forge.template.NewProject
import com.app.forge.template.NewSpec
import com.app.forge.template.Templates
import com.app.forge.util.folderDisplayName
import com.app.forge.util.importFolder
import com.app.forge.util.importZip
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Daftar proyek: buka, buat dari template, impor dari GitHub/ZIP, hapus. */
class MainActivity : AppCompatActivity() {
    private class Row(val name: String, val kind: String, val origin: String)

    private val store get() = app.store
    private lateinit var adapter: ProjectsAdapter
    private lateinit var empty: View
    private var warned = false
    private val dismissed = HashMap<String, Long>()
    private val ui = Handler(Looper.getMainLooper())
    private val buildListener: () -> Unit = { renderBuild() }
    private val tick = object : Runnable {
        override fun run() {
            if (BuildManager.anyRunning()) { renderBuild(); ui.postDelayed(this, 1000) }
        }
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZipUri(uri)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) askFolderImport(uri)
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.inflateMenu(R.menu.main)
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_import_github -> importGithub()
                R.id.action_import_folder -> pickFolder.launch(null)
                R.id.action_import_zip -> pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            true
        }
        empty = findViewById(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ProjectsAdapter({ open(it.name) }, { confirmDelete(it.name) }, { buildInfo(it) })
        list.adapter = adapter
        findViewById<View>(R.id.fab).setOnClickListener { newProject() }
        findViewById<View>(R.id.banner).setOnClickListener { onBannerClick() }

        // Android 13+: izin notifikasi untuk progres dan hasil build; ditanyakan sekali saja.
        val prefs = getSharedPreferences("forge_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        BuildManager.addListener(buildListener)
        renderBuild()
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        BuildManager.removeListener(buildListener)
    }

    private class BuildInfo(val text: String, val progress: Pair<Int, Int>?, val indeterminate: Boolean)

    private val doneStates = setOf("success", "failure", "skipped", "cancelled")

    /** Keterangan build untuk satu proyek (null bila tidak ada hubungannya dengan build sesi ini). */
    private fun buildInfo(name: String): BuildInfo? {
        val s = BuildManager.of(name)
        if (s.running) {
            val steps = s.steps
            val up = s.upload
            val done = steps.count { it.state in doneStates }
            return BuildInfo(
                (if (s.cancelling) "Membatalkan · " else "Build berjalan · ") + s.elapsed() + " · " + s.currentText(),
                if (steps.isNotEmpty()) done to steps.size
                else if (up != null && up.bytesTotal > 0) (up.bytesDone * 1000 / up.bytesTotal).toInt() to 1000
                else null,
                steps.isEmpty() && (up == null || up.bytesTotal <= 0)
            )
        }
        val id = s.lastId
        if (id != null && dismissed[name] != id) {
            val t = when (s.status) {
                "success" -> "Build terakhir: berhasil"
                "cancelled" -> "Build terakhir: dibatalkan"
                "failed" -> "Build terakhir: gagal"
                else -> return null
            }
            return BuildInfo(t, null, false)
        }
        return null
    }

    private fun onBannerClick() {
        val running = BuildManager.running()
        when {
            running.size == 1 -> open(running[0].project)
            running.size > 1 -> {
                val names = running.map { it.project }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Build yang sedang berjalan")
                    .setItems(names.map { n -> n + "  ·  " + BuildManager.of(n).currentText() }.toTypedArray()) { _, i -> open(names[i]) }
                    .setNeutralButton("Batalkan semua") { _, _ -> BuildManager.stopAll() }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
            else -> BuildManager.lastFinished()?.let { open(it.project) }
        }
    }

    /** Spanduk di atas daftar: build yang sedang jalan (bisa lebih dari satu) atau yang baru selesai; ketuk untuk melihatnya. */
    private fun renderBuild() {
        val banner = findViewById<View>(R.id.banner)
        val running = BuildManager.running()
        val bar = findViewById<LinearProgressIndicator>(R.id.bannerProgress)
        val title = findViewById<TextView>(R.id.bannerTitle)
        val sub = findViewById<TextView>(R.id.bannerSub)
        if (running.isNotEmpty()) {
            banner.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            if (running.size == 1) {
                val p = running[0].project
                val info = buildInfo(p)
                title.text = "Sedang di-build: $p"
                sub.text = (info?.text ?: "") + "  ›"
                if (info == null || info.indeterminate) bar.isIndeterminate = true
                else info.progress?.let { bar.isIndeterminate = false; bar.max = it.second; bar.progress = it.first }
            } else {
                title.text = "${running.size} proyek sedang di-build"
                sub.text = running.joinToString(" · ") { it.project } + "  ›"
                // Progres gabungan: rata-rata langkah selesai dari tiap build.
                var done = 0
                var total = 0
                running.forEach { s -> done += s.steps.count { it.state in doneStates }; total += s.steps.size }
                if (total == 0) bar.isIndeterminate = true
                else { bar.isIndeterminate = false; bar.max = total; bar.progress = done }
            }
        } else {
            val last = BuildManager.lastFinished()
            val info = last?.let { buildInfo(it.project) }
            if (last == null || info == null) banner.visibility = View.GONE
            else {
                banner.visibility = View.VISIBLE
                bar.visibility = View.GONE
                title.text = last.project
                sub.text = info.text + "  ›"
            }
        }
        ui.removeCallbacks(tick)
        if (running.isNotEmpty()) ui.postDelayed(tick, 1000)
        adapter.refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (!warned) {
            warned = true
            store.rootWarning?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun refresh() {
        bg({
            store.projects().map { n ->
                val cfg = store.config(n)
                Row(n, Templates.label(cfg.template), store.origin(n))
            }
        }) { r ->
            val rows = r.getOrDefault(emptyList())
            adapter.submit(rows)
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(name: String) {
        BuildManager.of(name).let { if (!it.running) it.lastId?.let { id -> dismissed[name] = id } }
        startActivity(Intent(this, ProjectActivity::class.java).putExtra("project", name))
    }

    private fun newProject() {
        val opts = NewProject.options
        val name = field("Nama proyek")
        val tpl = Spinner(this)
        tpl.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts.map { "${it.title}  ·  ${it.category}" })
        val lang = Spinner(this)
        lang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Java", "Kotlin"))
        lang.setSelection(1)
        val pkg = field("Package (kosong = otomatis)")
        val hint = TextView(this)
        fun sync() {
            val o = opts[tpl.selectedItemPosition]
            lang.visibility = if (o.languageChoice) View.VISIBLE else View.GONE
            pkg.visibility = if (o.android) View.VISIBLE else View.GONE
            hint.text = o.desc
        }
        tpl.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = sync()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sync()
        MaterialAlertDialogBuilder(this)
            .setTitle("Proyek baru")
            .setView(column(name, tpl, hint, lang, pkg))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat") { _, _ ->
                val raw = name.value.trim()
                if (raw.isEmpty()) { toast("Isi nama proyek"); return@setPositiveButton }
                val o = opts[tpl.selectedItemPosition]
                val pk = pkg.value.trim()
                if (o.android && pk.isNotEmpty() && !Templates.validPackage(pk)) { toast("Package tidak valid"); return@setPositiveButton }
                val kotlin = if (o.languageChoice) lang.selectedItemPosition == 1 else true
                val compose = o.id == "android-compose"
                bg({
                    val spec = if (o.android) NewSpec(pk.ifEmpty { Templates.defaultPackage(raw, store.packagesInUse()) }, kotlin, compose) else null
                    store.create(raw, o.tpl, spec)
                }) { r ->
                    r.onSuccess { open(it) }.onFailure { toast("Gagal: ${it.msg()}") }
                }
            }
            .show()
    }

    private fun importGithub() {
        val repo = field("owner/repo atau URL GitHub")
        val branch = field("Branch (opsional)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Impor dari GitHub")
            .setMessage("Repo privat butuh token GitHub di Pengaturan.")
            .setView(column(repo, branch))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Impor") { _, _ ->
                val input = repo.value.trim()
                if (input.isEmpty()) return@setPositiveButton
                toast("Mengunduh…")
                bg({ cloneRepo(store, input, branch.value.trim().ifEmpty { null }) }) { r ->
                    r.onSuccess { toast(it.summary); refresh() }.onFailure { toast("Gagal: ${it.msg()}") }
                }
            }
            .show()
    }

    private fun askFolderImport(uri: Uri) {
        val name = field("Nama proyek", folderDisplayName(this, uri))
        val pub = com.google.android.material.materialswitch.MaterialSwitch(this)
        pub.text = "Repo publik di GitHub (mati = privat)"
        MaterialAlertDialogBuilder(this)
            .setTitle("Impor folder")
            .setMessage("Isi folder disalin ke proyek baru. Repo GitHub dibuat otomatis saat Build pertama.")
            .setView(column(name, pub))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Impor") { _, _ ->
                val nm = name.value.trim().ifEmpty { "proyek" }
                val isPub = pub.isChecked
                toast("Menyalin folder…")
                bg({ importFolder(this, store, uri, nm, isPub) }) { r ->
                    r.onSuccess { toast(it.summary); refresh() }.onFailure { toast("Gagal: ${it.msg()}") }
                }
            }
            .show()
    }

    private fun importZipUri(uri: Uri) {
        bg({ importZip(this, store, uri) }) { r ->
            r.onSuccess { toast(it.summary); refresh() }.onFailure { toast("Gagal: ${it.msg()}") }
        }
    }

    private fun confirmDelete(name: String) = confirmDeleteProject(name) { refresh() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private class ProjectsAdapter(
        val onClick: (Row) -> Unit,
        val onLong: (Row) -> Unit,
        val info: (String) -> BuildInfo?
    ) : RecyclerView.Adapter<ProjectsAdapter.VH>() {
        private var rows: List<Row> = emptyList()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val bar: LinearProgressIndicator = v.findViewById(R.id.rowProgress)
        }

        fun submit(r: List<Row>) { rows = r; notifyDataSetChanged() }
        fun refresh() { if (rows.isNotEmpty()) notifyItemRangeChanged(0, rows.size) }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.name
            val bi = info(r.name)
            h.subtitle.text = bi?.text ?: if (r.origin.isNotEmpty()) "${r.kind} · ${r.origin}" else r.kind
            val running = BuildManager.of(r.name).running
            h.bar.visibility = if (running) View.VISIBLE else View.GONE
            if (running && bi != null) {
                if (bi.indeterminate) h.bar.isIndeterminate = true
                else bi.progress?.let { h.bar.isIndeterminate = false; h.bar.max = it.second; h.bar.progress = it.first }
            }
            h.itemView.setOnClickListener { onClick(r) }
            h.itemView.setOnLongClickListener { onLong(r); true }
        }
    }
}
