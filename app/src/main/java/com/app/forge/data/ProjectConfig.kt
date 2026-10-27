package com.app.forge.data

/** Pengaturan build per proyek. versionName/versionCode kosong = pakai bawaan proyek. */
data class ProjectConfig(
    val template: String = "android",
    val release: Boolean = false,
    val versionName: String = "",
    val versionCode: String = "",
    val sign: Boolean = false,
    val alias: String = "",
    val storePass: String = "",
    val keyPass: String = "",
    /** Rilis GitHub setelah build: "none", "new" (rilis baru), atau "update" (perbarui rilis dengan tag yang sama). */
    val releaseMode: String = "none",
    val releaseTag: String = ""
)
