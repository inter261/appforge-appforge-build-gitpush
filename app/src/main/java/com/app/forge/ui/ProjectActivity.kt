package com.app.forge.ui

import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.app.forge.R
import com.app.forge.app
import com.app.forge.build.BuildError
import com.app.forge.build.BuildManager
import com.app.forge.build.BuildSession
import com.app.forge.build.BuildTask
import com.app.forge.build.installApk
import com.app.forge.build.parseErrors
import com.app.forge.build.cleanLog
import com.app.forge.build.splitStepLogs
import com.app.forge.github.Step
import com.app.forge.data.Entry
import com.app.forge.template.Templates
import com.app.forge.template.WORKFLOW_PATH
import com.app.forge.util.copyText
import com.app.forge.util.fmt
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** Satu proyek: kirim ke GitHub + build + lihat hasil. Mengedit kode dilakukan di editor lain. */
class ProjectActivity : AppCompatActivity() {
    private val store get() = app.store
    private val hist get() = app.hist
    private lateinit var project: String
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var stepsBox: android.widget.LinearLayout
    private lateinit var btnBuild: Button
    private lateinit var btnInstall: Button
    private lateinit var btnCancel: Button
    private lateinit var btnErrors: Button
    private lateinit var btnCopy: Button

    private var errors: List<BuildError> = emptyList()
    private var parsedFor: Long? = null
    private var ksStatus: TextView? = null
    private val doneStates = setOf("success", "failure", "skipped", "cancelled")
    private val expanded = HashSet<String>()
    private var rowList: List<StepRow> = emptyList()
    private val rowKeys: List<String> get() = rowList.map { it.key }
    private lateinit var page: ScrollView
    private lateinit var sticky: LinearLayout
    private var stickyKey: String? = null
    private val seenRunning = HashSet<String>()
    private val shown = HashMap<String, Int>()
    private val openGroups = HashSet<String>()
    private var autoOpenedFor = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (B.running) { renderStatus(); ui.postDelayed(this, 1000) }
        }
    }
    private val B: BuildSession get() = BuildManager.of(project)
    private val listener: () -> Unit = { render() }

    private val pickKeystore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importKeystore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra("project")
        if (p == null) { finish(); return }
        project = p
        setContentView(R.layout.activity_project)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = project
        tb.setNavigationIcon(R.drawable.ic_close)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.project)
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_history -> showHistory()
                R.id.action_open_github -> openRun()
                R.id.action_expand_all -> { rowKeys.forEach { expanded.add(it) }; render() }
                R.id.action_collapse_all -> { expanded.clear(); render() }
                R.id.action_build_settings -> buildSettings()
                R.id.action_settings -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                R.id.action_delete -> confirmDeleteProject(project) { local -> if (local) finish() else refreshInfo() }
            }
            true
        }
        info = findViewById(R.id.info)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        stepsBox = findViewById(R.id.stepsBox)
        page = findViewById(R.id.page)
        sticky = findViewById(R.id.sticky)
        page.setOnScrollChangeListener { _, _, _, _, _ -> updateSticky() }
        btnBuild = findViewById(R.id.btnBuild)
        btnInstall = findViewById(R.id.btnInstall)
        btnCancel = findViewById(R.id.btnCancel)
        btnErrors = findViewById(R.id.btnErrors)
        btnCopy = findViewById(R.id.btnCopy)
        btnBuild.setOnClickListener { runBuild() }
        btnCancel.setOnClickListener { BuildManager.stop(project) }
        btnInstall.setOnClickListener { installLast() }
        btnErrors.setOnClickListener { showErrors() }
        btnCopy.setOnClickListener {
            val t = if (B.running) B.logs.joinToString("\n") else B.finalLog
            copyText(this, t)
            toast("Log disalin")
        }
        btnBuild.setOnLongClickListener { chooseTask(); true }
    }

    override fun onStart() {
        super.onStart()
        refreshInfo()
        BuildManager.addListener(listener)
        render()
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        BuildManager.removeListener(listener)
    }

    private fun refreshInfo() {
        bg({
            val cfg = store.config(project)
            val n = store.files(project).size
            val origin = store.origin(project)
            Triple(cfg, n, origin)
        }) { r ->
            val (cfg, n, origin) = r.getOrNull() ?: return@bg
            info.text = "Jenis: ${Templates.label(cfg.template)}\n" +
                "Folder: ${store.file(project, "").path}\n" +
                "$n file" + (if (origin.isNotEmpty()) "\nAsal: $origin" else "") +
                "\n\nEdit kode di editor lain (mis. Acode) pada folder di atas. Tekan Build: file yang berubah dikirim ke GitHub, " +
                "workflow dijalankan, lalu APK hasilnya bisa langsung diinstal."
        }
    }

    // ---- build --------------------------------------------------------------------------------

    private fun runBuild() {
        if (B.running) { toast("Build proyek ini sedang berjalan"); return }
        if (store.token.isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("GitHub token belum diisi")
                .setMessage("Build berjalan di GitHub Actions, jadi butuh token GitHub. Isi di Pengaturan.")
                .setPositiveButton("Buka Pengaturan") { _, _ -> startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        startBuild(BuildTask.default(store.config(project)))
    }

    /** Tekan lama tombol Build: pilih varian lain (debug/release, APK/AAB). */
    private fun chooseTask() {
        if (B.running) return
        val tasks = BuildTask.tasks(store.config(project))
        if (tasks.size == 1) { runBuild(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Jenis build")
            .setItems(tasks.map { it.title }.toTypedArray()) { _, i ->
                if (store.token.isBlank()) runBuild() else startBuild(tasks[i])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startBuild(task: BuildTask) {
        errors = emptyList()
        parsedFor = null
        expanded.clear(); seenRunning.clear(); shown.clear(); openGroups.clear()
        BuildManager.start(applicationContext, store, hist, project, task)
        render()
    }

    private fun elapsed(): String {
        val t = ((System.currentTimeMillis() - B.startedAt) / 1000).toInt().coerceAtLeast(0)
        return if (t >= 60) "${t / 60}m ${"%02d".format(t % 60)}s" else "${t}s"
    }

    private fun renderStatus() {
        val mine = B.status != "idle"
        val running = B.running
        val st = if (mine) B.status else "idle"
        val steps = B.steps
        status.text = when {
            running -> (if (B.cancelling) "Membatalkan…" else "Berjalan") + " · ${elapsed()}" +
                (steps.firstOrNull { it.state == "in_progress" }?.let { " · ${it.name}" }
                    ?: (if (steps.isEmpty()) B.uploadText()?.let { " · $it" } else null) ?: "")
            st == "success" -> "Berhasil — ${B.finalMessage}"
            st == "failed" -> "Gagal — ${B.finalMessage.lineSequence().firstOrNull().orEmpty()}"
            st == "cancelled" -> "Dibatalkan"
            else -> "Belum ada build di sesi ini"
        }
    }

    private class StepRow(val key: String, val state: String, val title: String, val secs: Int, val lines: List<String>?, val empty: String)

    private val pendingStates = setOf("queued", "pending", "waiting", "")

    private fun rows(mine: Boolean, running: Boolean, st: String): List<StepRow> {
        if (!mine || st == "idle") return emptyList()
        val steps = B.steps
        val out = ArrayList<StepRow>()
        val preState = when {
            steps.isNotEmpty() -> "success"
            running -> "in_progress"
            st == "success" -> "success"
            st == "cancelled" -> "cancelled"
            else -> "failure"
        }
        out.add(
            StepRow(
                "pre", preState, "Kirim file ke GitHub & tunggu runner" + (B.uploadText()?.let { "\n$it" } ?: ""), -1,
                B.preLog.takeIf { it.isNotEmpty() }, "(belum ada keluaran)"
            )
        )
        val raw = B.jobLog
        val split = splitStepLogs(raw, steps)
        steps.forEachIndexed { i, s ->
            val empty = if (s.state in pendingStates) "Belum dimulai" else if (s.state == "skipped") "Dilewati" else "(tidak ada keluaran)"
            out.add(StepRow("s$i", s.state, s.name, s.seconds, split?.get(i), empty))
        }
        if (split == null && raw.isNotBlank()) out.add(StepRow("full", "log", "Log lengkap", -1, cleanLog(raw).lines(), ""))
        return out
    }

    private fun render() {
        val mine = B.status != "idle"
        val running = B.running
        val st = if (mine) B.status else "idle"
        renderStatus()
        ui.removeCallbacks(tick)
        if (running) ui.postDelayed(tick, 1000)
        val steps = B.steps
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val up = B.upload
            if (steps.isEmpty() && up != null && up.bytesTotal > 0) {
                progress.isIndeterminate = false
                progress.max = 1000
                progress.progress = (up.bytesDone * 1000 / up.bytesTotal).toInt()
            } else if (steps.isEmpty()) progress.isIndeterminate = true
            else { progress.isIndeterminate = false; progress.max = steps.size; progress.progress = steps.count { it.state in doneStates } }
        }

        val list = rows(mine, running, st)
        // Buka otomatis langkah pertama yang gagal (sekali per build), seperti di GitHub.
        if (!running && st == "failed" && autoOpenedFor != B.startedAt) {
            list.firstOrNull { it.key.startsWith("s") && it.state == "failure" }?.let { expanded.add(it.key) }
            autoOpenedFor = B.startedAt
        }
        // Langkah yang baru mulai berjalan terbuka sendiri (sekali), seperti di aplikasi GitHub.
        if (running) list.filter { it.state == "in_progress" && it.key.startsWith("s") }.forEach { if (seenRunning.add(it.key)) expanded.add(it.key) }
        rowList = list
        stepsBox.removeAllViews()
        list.forEach { stepsBox.addView(stepView(it)) }
        stepsBox.post { updateSticky() }

        btnBuild.isEnabled = !running
        btnCancel.visibility = if (running && !B.cancelling) View.VISIBLE else View.GONE
        btnInstall.visibility = if (st == "success" && lastOutput()?.second == "apk") View.VISIBLE else View.GONE
        btnErrors.visibility = if (errors.isNotEmpty()) View.VISIBLE else View.GONE
        btnCopy.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        val id = B.lastId
        if (st == "failed" && !running && id != null && parsedFor != id) {
            parsedFor = id
            val text = B.finalLog
            bg({ parseErrors(text, store.files(project).toSet()) }) { r ->
                errors = r.getOrDefault(emptyList())
                btnErrors.visibility = if (errors.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun stepView(r: StepRow): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(10), 0, dp(10))
        head.addView(stepIcon(r.state), LinearLayout.LayoutParams(dp(24), dp(24)))
        val name = TextView(this)
        name.text = r.title
        name.textSize = 14f
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.marginStart = dp(12)
        head.addView(name, nlp)
        if (r.secs >= 0) {
            val t = TextView(this)
            t.text = if (r.secs >= 60) "${r.secs / 60}m ${r.secs % 60}s" else "${r.secs}s"
            t.textSize = 12f
            t.alpha = 0.6f
            head.addView(t)
        }
        val openable = r.state !in setOf("log") || r.lines != null
        if (openable) {
            val chevron = TextView(this)
            chevron.text = if (r.key in expanded) "▾" else "▸"
            chevron.setPadding(dp(8), 0, 0, 0)
            head.addView(chevron)
        }
        box.addView(head)
        if (openable) {
            head.setOnClickListener { if (!expanded.add(r.key)) expanded.remove(r.key); render() }
            if (r.key in expanded) addLog(box, r)
        }
        return box
    }

    /**
     * Header menempel: saat sebuah langkah terbuka dan barisnya sudah tergulung ke atas (tapi lognya masih terlihat),
     * namanya tetap tampil di atas layar. Ketuk untuk menutup langkah itu dan kembali ke barisnya.
     */
    private fun updateSticky() {
        val y = page.scrollY
        var hit = -1
        for (i in 0 until stepsBox.childCount) {
            val child = stepsBox.getChildAt(i)
            val top = stepsBox.top + child.top
            val bottom = stepsBox.top + child.bottom
            val key = rowList.getOrNull(i)?.key ?: continue
            if (key in expanded && top < y && bottom > y + dp(56)) { hit = i; break }
        }
        if (hit < 0) { sticky.visibility = View.GONE; stickyKey = null; return }
        val r = rowList[hit]
        if (stickyKey != r.key || sticky.childCount == 0) {
            stickyKey = r.key
            sticky.removeAllViews()
            sticky.addView(stepIcon(r.state), LinearLayout.LayoutParams(dp(24), dp(24)))
            val name = TextView(this)
            name.text = r.title.lineSequence().first()
            name.textSize = 14f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(12)
            sticky.addView(name, lp)
            if (r.secs >= 0) {
                val t = TextView(this)
                t.text = if (r.secs >= 60) "${r.secs / 60}m ${r.secs % 60}s" else "${r.secs}s"
                t.textSize = 12f
                t.alpha = 0.7f
                sticky.addView(t)
            }
            val idx = hit
            sticky.setOnClickListener {
                expanded.remove(r.key)
                render()
                page.post { page.scrollTo(0, (stepsBox.top + (stepsBox.getChildAt(idx)?.top ?: 0) - dp(8)).coerceAtLeast(0)) }
            }
        }
        sticky.visibility = View.VISIBLE
    }

    private fun openRun() {
        val u = B.runUrl
        if (u == null) { toast("Belum ada run di GitHub"); return }
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(u))) }
            .onFailure { copyText(this, u); toast("Alamat run disalin") }
    }

    private val pageSize = 300

    /** Log berbaris-nomor seperti di GitHub; yang tampil baris terakhir, sisanya bisa dimuat bertahap (supaya tetap ringan). */
    private fun addLog(box: LinearLayout, r: StepRow) {
        val lines = r.lines
        if (lines.isNullOrEmpty()) {
            val tv = TextView(this)
            tv.text = r.empty
            tv.textSize = 12f
            tv.alpha = 0.6f
            tv.setPadding(dp(36), 0, 0, dp(8))
            box.addView(tv)
            return
        }
        val count = (shown[r.key] ?: pageSize).coerceAtMost(lines.size)
        val from = lines.size - count
        if (from > 0) {
            val more = TextView(this)
            more.text = "▲ Muat 500 baris sebelumnya ($from lagi)"
            more.textSize = 12f
            more.setTextColor(0xFF0969DA.toInt())
            more.setPadding(dp(36), dp(4), 0, dp(8))
            more.setOnClickListener { shown[r.key] = count + 500; render() }
            box.addView(more)
        }
        val width = lines.size.toString().length
        var sb = android.text.SpannableStringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            val chunk = TextView(this)
            chunk.typeface = Typeface.MONOSPACE
            chunk.textSize = 11f
            chunk.setTextIsSelectable(true)
            chunk.setPadding(dp(12), 0, 0, dp(2))
            chunk.text = sb
            box.addView(chunk)
            sb = android.text.SpannableStringBuilder()
        }
        var i = from
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.startsWith("##[endgroup]")) { i++; continue }
            val num = (i + 1).toString().padStart(width) + "  "
            if (raw.startsWith("##[group]")) {
                // Grup log dilipat seperti di GitHub: ketuk judulnya untuk membuka isinya.
                flush()
                val gk = r.key + ":" + i
                val open = gk in openGroups
                val h = TextView(this)
                h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                h.textSize = 11f
                h.setPadding(dp(12), dp(3), 0, dp(3))
                h.text = num + (if (open) "▾ " else "▸ ") + raw.removePrefix("##[group]")
                h.setOnClickListener { if (!openGroups.add(gk)) openGroups.remove(gk); render() }
                box.addView(h)
                i++
                if (!open) {
                    while (i < lines.size && !lines[i].startsWith("##[endgroup]")) i++
                    if (i < lines.size) i++
                }
                continue
            }
            val start = sb.length
            sb.append(num)
            sb.setSpan(android.text.style.ForegroundColorSpan(0xFF8C959F.toInt()), start, sb.length, 0)
            var text = raw
            var color = 0
            var bold = false
            when {
                raw.startsWith("##[error]") -> { text = raw.removePrefix("##[error]"); color = 0xFFCF222E.toInt() }
                raw.startsWith("##[warning]") -> { text = raw.removePrefix("##[warning]"); color = 0xFF9A6700.toInt() }
                raw.startsWith("##[command]") || raw.startsWith("[command]") -> { text = raw.removePrefix("##[command]"); bold = true }
            }
            val ts = sb.length
            sb.append(text).append('\n')
            if (color != 0) sb.setSpan(android.text.style.ForegroundColorSpan(color), ts, sb.length, 0)
            if (bold) sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), ts, sb.length, 0)
            i++
        }
        flush()
    }

    private fun stepIcon(state: String): View {
        if (state == "in_progress") {
            val sp = com.google.android.material.progressindicator.CircularProgressIndicator(this)
            sp.isIndeterminate = true
            sp.indicatorSize = dp(18)
            sp.trackThickness = dp(2)
            return sp
        }
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.textSize = 16f
        tv.typeface = Typeface.DEFAULT_BOLD
        when (state) {
            "success" -> { tv.text = "✓"; tv.setTextColor(0xFF2DA44E.toInt()) }
            "failure", "timed_out", "startup_failure" -> { tv.text = "✗"; tv.setTextColor(0xFFCF222E.toInt()) }
            "cancelled" -> { tv.text = "⊘"; tv.setTextColor(0xFF8C959F.toInt()) }
            "skipped" -> { tv.text = "–"; tv.setTextColor(0xFF8C959F.toInt()) }
            "log" -> { tv.text = "≡"; tv.setTextColor(0xFF8C959F.toInt()) }
            else -> { tv.text = "○"; tv.setTextColor(0xFF8C959F.toInt()) }
        }
        return tv
    }

    private fun showErrors() {
        if (errors.isEmpty()) { toast("Tidak ada error yang terbaca di file proyek"); return }
        val labels = errors.map { "${it.path}:${it.line}\n${it.msg}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Error build (ketuk untuk menyalin lokasi)")
            .setItems(labels) { _, i -> copyText(this, "${errors[i].path}:${errors[i].line}"); toast("Lokasi disalin") }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun lastOutput(): Pair<File, String>? {
        val id = B.lastId ?: return null
        val e = hist.get(project, id) ?: return null
        val f = hist.output(e)
        return if (f.exists()) f to e.kind else null
    }

    private fun installLast() {
        val o = lastOutput()
        if (o == null) toast("Hasil build tidak ditemukan") else installApk(this, o.first)
    }

    // ---- pengaturan build & riwayat -----------------------------------------------------------

    private fun buildSettings() {
        val cfg = store.config(project)
        val views = ArrayList<android.view.View>()
        val release = MaterialSwitch(this)
        release.text = "Varian release (bawaan tombol Build)"
        release.isChecked = cfg.release
        if (Templates.hasBuildType(cfg.template)) views.add(release)
        val vn = field("Version name (kosong = bawaan proyek)", cfg.versionName)
        val vc = field("Version code (kosong = bawaan proyek)", cfg.versionCode, number = true)
        if (Templates.hasVersion(cfg.template)) { views.add(vn); views.add(vc) }
        val persistent = MaterialSwitch(this)
        persistent.text = "Repo tetap di GitHub (build berikutnya lebih cepat)"
        persistent.isChecked = store.persistent(project)
        views.add(persistent)
        val pubSwitch = MaterialSwitch(this)
        pubSwitch.text = "Repo publik di GitHub (mati = privat)"
        pubSwitch.isChecked = store.isPublic(project)
        views.add(pubSwitch)
        val relSpin = android.widget.Spinner(this)
        relSpin.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tanpa rilis GitHub", "Buat rilis baru", "Perbarui rilis yang ada (sesuai tag)"))
        relSpin.setSelection(when (cfg.releaseMode) { "new" -> 1; "update" -> 2; else -> 0 })
        val relTag = field("Tag rilis (rilis baru: kosong = build-N)", cfg.releaseTag)
        if (Templates.hasRelease(cfg.template)) { views.add(relSpin); views.add(relTag) }
        val sign = MaterialSwitch(this)
        sign.text = "Tanda tangan dengan keystore sendiri"
        sign.isChecked = cfg.sign
        val alias = field("Alias key", cfg.alias)
        val storePass = field("Password keystore", cfg.storePass, secret = true)
        val keyPass = field("Password key (kosong = sama)", cfg.keyPass, secret = true)
        val ks = TextView(this)
        ksStatus = ks
        ks.text = if (store.hasKeystore(project)) "Keystore: sudah dipilih" else "Keystore: belum dipilih"
        val pick = Button(this)
        pick.text = "Pilih keystore (.jks / .keystore)…"
        pick.setOnClickListener { pickKeystore.launch(arrayOf("*/*")) }
        if (Templates.hasSigning(cfg.template)) views.addAll(listOf(sign, alias, storePass, keyPass, ks, pick))
        val note = TextView(this)
        note.text = "Menyimpan menulis ulang $WORKFLOW_PATH dari pengaturan ini."
        note.textSize = 12f
        views.add(note)
        val scroll = ScrollView(this)
        scroll.addView(column(*views.toTypedArray()))
        MaterialAlertDialogBuilder(this)
            .setTitle("Pengaturan build · ${Templates.label(cfg.template)}")
            .setView(scroll)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val relMode = if (Templates.hasRelease(cfg.template)) listOf("none", "new", "update")[relSpin.selectedItemPosition] else cfg.releaseMode
                val relTagText = relTag.value.trim()
                if (relMode == "update" && relTagText.isEmpty()) { toast("Isi tag rilis yang mau diperbarui"); return@setPositiveButton }
                val wantPublic = pubSwitch.isChecked
                val c2 = cfg.copy(
                    releaseMode = relMode, releaseTag = relTagText,
                    release = if (Templates.hasBuildType(cfg.template)) release.isChecked else cfg.release,
                    versionName = vn.value.trim(), versionCode = vc.value.trim(),
                    sign = sign.isChecked && Templates.hasSigning(cfg.template),
                    alias = alias.value.trim(), storePass = storePass.value, keyPass = keyPass.value
                )
                bg({
                    store.saveConfig(project, c2)
                    store.setPersistent(project, persistent.isChecked)
                    store.setPublic(project, wantPublic)
                    // Kalau repo tetapnya sudah ada (buatan AppForge), visibilitasnya langsung disamakan.
                    runCatching {
                        if (store.token.isNotBlank() && persistent.isChecked) {
                            val gh = com.app.forge.github.GitHub(store.token)
                            val owner = gh.user()
                            val repo = "appforge-" + project.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                            val info = gh.repoInfo(owner, repo)
                            if (info != null && info.optString("description") == "AppForge" && info.optBoolean("private", true) == wantPublic) {
                                gh.setPrivate(owner, repo, !wantPublic)
                            }
                        }
                    }
                    store.applyWorkflow(project)
                }) { r ->
                    r.onSuccess { toast("Tersimpan, build.yml diperbarui") }
                        .onFailure { toast("Gagal: ${it.msg()}") }
                }
            }
            .show()
    }

    private fun importKeystore(uri: Uri) {
        bg({
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File tidak bisa dibaca")
            store.importKeystore(project, bytes)
        }) { r ->
            r.onSuccess { ksStatus?.text = "Keystore: sudah dipilih" }.onFailure { toast(it.msg()) }
        }
    }

    private fun showHistory() {
        val list = hist.list(project)
        if (list.isEmpty()) { toast("Belum ada riwayat build"); return }
        val labels = list.map {
            (if (it.success) "✓ " else "✗ ") + fmt(it.id) + " · ${it.seconds} dtk" +
                (if (it.kind != "none") " · ${it.kind.uppercase()} ${"%.1f".format(it.apkSize / 1048576.0)} MB" else "")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Riwayat build")
            .setItems(labels) { _, i -> historyEntry(list[i]) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun historyEntry(e: Entry) {
        val out = hist.output(e)
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (e.success && e.kind == "apk" && out.exists()) { items.add("Install APK"); acts.add { installApk(this, out) } }
        items.add("Lihat log"); acts.add {
            val tv = TextView(this)
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.textSize = 11f
            tv.setTextIsSelectable(true)
            tv.setPadding(dp(16), dp(8), dp(16), dp(8))
            tv.text = hist.log(e).lines().takeLast(800).joinToString("\n")
            val sv = ScrollView(this)
            sv.addView(tv)
            MaterialAlertDialogBuilder(this).setTitle(fmt(e.id)).setView(sv).setPositiveButton("Tutup", null).show()
        }
        items.add("Hapus dari riwayat"); acts.add { hist.delete(e); toast("Dihapus") }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.message)
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
