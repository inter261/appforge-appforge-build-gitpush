package com.app.forge.template

import android.content.Context
import com.app.forge.data.ProjectConfig

const val WORKFLOW_PATH = ".github/workflows/build.yml"

object Templates {
    val ids = listOf("android", "flutter", "node", "python", "custom")

    fun label(id: String) = when (id) {
        "android" -> "Android (Gradle)"
        "flutter" -> "Flutter"
        "node" -> "Node.js"
        "python" -> "Python"
        else -> "Kustom / lainnya"
    }

    fun hint(id: String) = when (id) {
        "android" -> "Menjalankan gradle assembleDebug/assembleRelease. Hasil: APK yang bisa langsung diinstal."
        "flutter" -> "flutter build apk. Folder android/ dibuat otomatis kalau belum ada. Hasil: APK."
        "node" -> "npm ci, npm test, npm run build. Hasil: isi folder dist/, build/, atau out/ (ZIP)."
        "python" -> "pip install, pytest, lalu python -m build kalau ada pyproject.toml/setup.py. Hasil: folder dist/ (ZIP)."
        else -> "Workflow generik. Edit langkah Build sesuai bahasa/tool Anda (Go, Rust, Java, dst). Hasil: folder out/ (ZIP)."
    }

    fun hasBuildType(id: String) = id == "android" || id == "flutter" || id == "node"
    fun hasVersion(id: String) = id == "android" || id == "flutter"
    fun hasSigning(id: String) = id == "android" || id == "flutter"
    fun hasRelease(id: String) = id == "android" || id == "flutter"

    /** Template yang wajib menghasilkan APK; kalau tidak, build dianggap gagal. */
    fun needsArtifact(id: String) = id == "android" || id == "flutter"

    fun detect(files: List<String>): String {
        val root = files.filter { !it.contains('/') }.toSet()
        return when {
            "pubspec.yaml" in root -> "flutter"
            root.any { it.startsWith("settings.gradle") || it.startsWith("build.gradle") } -> "android"
            "package.json" in root -> "node"
            root.any { it == "requirements.txt" || it == "pyproject.toml" || it == "setup.py" || it.endsWith(".py") } -> "python"
            else -> "custom"
        }
    }

    /** Bawaan AppForge untuk proyek Android baru: com.appforge.<nama proyek>, dibuat unik terhadap [used]. */
    fun defaultPackage(projectName: String, used: Set<String> = emptySet()): String {
        var seg = projectName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (seg.isEmpty() || seg[0] in '0'..'9') seg = "app$seg"
        val base = "com.appforge.$seg"
        var pkg = base
        var n = 2
        while (pkg in used) pkg = "$base${n++}"
        return pkg
    }

    private val PACKAGE_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    /** Package Android yang sah: minimal dua segmen huruf kecil/angka/garis bawah, tiap segmen diawali huruf. */
    fun validPackage(pkg: String) = PACKAGE_RE.matches(pkg)

    /** File awal template [id]; [spec] dan [appName] hanya dipakai proyek Android dan Flutter/Node (nama paket). */
    fun starter(id: String, spec: NewSpec? = null, appName: String = "Hello"): Map<String, String> = when (id) {
        "flutter" -> Starters.flutter.mapValues { it.value.replace("hello_flutter", identName(appName, '_', "hello_flutter")) }
        "node" -> Starters.node.mapValues { it.value.replace("hello-node", identName(appName, '-', "hello-node")) }
        "python" -> Starters.python
        "custom" -> Starters.custom
        else -> Starters.androidApp(
            spec ?: NewSpec(defaultPackage(appName)), appName
        )
    }

    /** Nama huruf kecil untuk pubspec/package.json: huruf, angka, dan [sep]; selalu diawali huruf. */
    private fun identName(raw: String, sep: Char, fallback: String): String {
        var n = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else sep }.joinToString("")
            .trim(sep).replace(Regex("${Regex.escape(sep.toString())}+"), sep.toString())
        if (n.isEmpty()) return fallback
        if (n[0] in '0'..'9') n = "app$sep$n"
        return n
    }

    /** Isi build.yml dari template + pengaturan. Template ada di assets/workflows. */
    fun workflow(ctx: Context, cfg: ProjectConfig, bundle: Boolean = false): String {
        fun asset(n: String) =
            ctx.assets.open("workflows/$n").bufferedReader().use { it.readText() }.trimEnd('\n', '\r')

        val sign = cfg.sign && hasSigning(cfg.template)
        val relOn = cfg.releaseMode in setOf("new", "update") && hasRelease(cfg.template)
        val relTag = cfg.releaseTag.filter { it.isLetterOrDigit() || it in "._-" }.take(80)
        val relVariant = if (cfg.release) "release" else "debug"
        val relFind = when {
            cfg.template == "flutter" -> "*/build/app/outputs/flutter-apk/*.apk"
            bundle -> "*/build/outputs/bundle/$relVariant/*.aab"
            else -> "*/build/outputs/apk/$relVariant/*.apk"
        }
        val relSnippet = if (relOn) asset("release.yml")
            .replace("@@RELEASE_MODE@@", cfg.releaseMode)
            .replace("@@RELEASE_TAG@@", relTag)
            .replace("@@RELEASE_FIND@@", relFind) else ""
        val snippets = mapOf(
            "RELEASE" to relSnippet,
            "PERMISSIONS" to if (relOn) "permissions:\n  contents: write" else "",
            "INPUTS" to if (sign) asset("inputs-signing.yml") else "",
            "ANDROID_PREP" to asset("android-prep.yml"),
            "INIT_GRADLE" to asset("init.gradle"),
            "SIGNING" to asset(if (sign) "signing-custom.yml" else "signing-default.yml")
        )
        val tpl = if (cfg.template in ids) cfg.template else "custom"
        val text = expand(asset("$tpl.yml")) { snippets[it] }

        // Nilai disaring supaya aman masuk ke YAML/shell.
        val vn = cfg.versionName.filter { it.isLetterOrDigit() || it in "._+-" }
        val vc = cfg.versionCode.filter { it.isDigit() }.take(9)
        val flutterFlags = buildString {
            append(if (cfg.release) "--release" else "--debug")
            if (vn.isNotEmpty()) append(" --build-name=").append(vn)
            if (vc.isNotEmpty()) append(" --build-number=").append(vc)
        }
        val variant = if (cfg.release) "release" else "debug"
        val gradleTask = if (bundle) (if (cfg.release) "bundleRelease" else "bundleDebug") else (if (cfg.release) "assembleRelease" else "assembleDebug")
        val artifactPath = if (bundle) "**/build/outputs/bundle/$variant/*.aab" else "**/build/outputs/apk/$variant/*.apk"
        return text
            .replace("@@DEBUG_KEYSTORE_B64@@", asset("debug-keystore.b64").trim())
            .replace("@@VERSION_NAME@@", vn)
            .replace("@@VERSION_CODE@@", vc)
            .replace("@@VARIANT@@", if (cfg.release) "release" else "debug")
            .replace("@@GRADLE_TASK@@", gradleTask)
            .replace("@@ARTIFACT_PATH@@", artifactPath)
            .replace("@@FLUTTER_FLAGS@@", flutterFlags)
            .replace("@@NODE_ENV@@", if (cfg.release) "production" else "development") + "\n"
    }

    private val PLACEHOLDER = Regex("^(\\s*)@@(\\w+)@@\\s*$")

    /** Baris yang isinya hanya @@NAMA@@ diganti snippet (indentasinya disamakan); snippet kosong = baris dihapus. */
    private fun expand(text: String, snippet: (String) -> String?): String {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val m = PLACEHOLDER.find(line)
            val body = m?.let { snippet(it.groupValues[2]) }
            if (m == null || body == null) { out += line; continue }
            if (body.isBlank()) continue
            val pad = m.groupValues[1]
            for (l in expand(body, snippet).lines()) out += if (l.isEmpty()) l else pad + l
        }
        return out.joinToString("\n")
    }
}
