package com.app.forge.template

/** Pilihan di layar Proyek Baru. [tpl] = id template workflow (Templates.ids); [id] membedakan varian starter. */
class TemplateOption(
    val id: String,
    val tpl: String,
    val category: String,
    val title: String,
    val desc: String,
    val letter: String,
    val android: Boolean = false,
    val languageChoice: Boolean = false
)

/** Pengaturan proyek Android baru. */
class NewSpec(
    val pkg: String,
    val kotlin: Boolean = true,
    val compose: Boolean = false,
    val minSdk: Int = 21,
    val targetSdk: Int = 34
)

object NewProject {
    val options = listOf(
        TemplateOption(
            "android-app", "android", "Android", "Android App",
            "Aplikasi Android dengan satu Activity (Views biasa). Pilih Java atau Kotlin.", "A", android = true, languageChoice = true
        ),
        TemplateOption(
            "android-compose", "android", "Android", "Jetpack Compose App",
            "Aplikasi Android Kotlin dengan UI Jetpack Compose dan Material 3.", "C", android = true
        ),
        TemplateOption(
            "flutter", "flutter", "Flutter", "Flutter App",
            "Aplikasi Flutter dengan satu layar. Build: flutter build apk. Folder android/ dibuat otomatis.", "F"
        ),
        TemplateOption(
            "node", "node", "Node.js", "Node.js Project",
            "Proyek Node.js dengan skrip build dan tes. Hasil build berupa ZIP folder dist/.", "N"
        ),
        TemplateOption(
            "python", "python", "Python", "Python Project",
            "Proyek Python dengan pytest. Hasil build berupa ZIP folder dist/.", "P"
        ),
        TemplateOption(
            "custom", "custom", "Lainnya", "Kustom / Lainnya",
            "Workflow generik: edit langkah Build sendiri untuk Go, Rust, Java, dan lainnya.", "K"
        )
    )

    val categories: List<String> = options.map { it.category }.distinct()

    /** Min SDK yang ditawarkan, sama dengan referensi. */
    val minSdks = listOf(21, 23, 24, 26, 28, 30, 33, 34, 35, 36)
    val targetSdks = listOf(30, 33, 34, 35, 36)

    fun androidName(api: Int): String = when (api) {
        21 -> "Android 5.0"; 23 -> "Android 6.0"; 24 -> "Android 7.0"; 26 -> "Android 8.0"; 28 -> "Android 9.0"
        30 -> "Android 11"; 33 -> "Android 13"; 34 -> "Android 14"; 35 -> "Android 15"; 36 -> "Android 16"
        else -> "Android"
    }

    fun sdkLabel(api: Int) = "API $api · ${androidName(api)}"

    fun byId(id: String): TemplateOption = options.firstOrNull { it.id == id } ?: options.first()

    /** Teks filter pencarian: cocokkan ke judul, deskripsi, dan kategori. */
    fun matches(o: TemplateOption, q: String): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return true
        return o.title.contains(t, ignoreCase = true) || o.desc.contains(t, ignoreCase = true) || o.category.contains(t, ignoreCase = true)
    }
}
