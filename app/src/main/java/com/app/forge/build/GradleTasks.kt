package com.app.forge.build

/** Satu tugas Gradle yang terbaca dari log build, mis. ":app:compileDebugKotlin UP-TO-DATE". */
data class GradleTask(val path: String, val outcome: String) {
    val failed: Boolean get() = outcome == "FAILED"
}

private val TASK_RE = Regex("> Task (:[\\w:.\\-]+)(?:[ \\t]+(UP-TO-DATE|NO-SOURCE|FROM-CACHE|SKIPPED|FAILED))?")

/**
 * Ambil daftar tugas Gradle (urutan kemunculan pertama) dari log. Baris "> Task :x" yang kemudian muncul lagi
 * dengan hasil (UP-TO-DATE/FAILED/…) memperbarui hasil tugas yang sama.
 */
fun parseGradleTasks(log: String): List<GradleTask> {
    val seen = LinkedHashMap<String, GradleTask>()
    for (l in log.lineSequence()) {
        val m = TASK_RE.find(l) ?: continue
        val path = m.groupValues[1]
        val outcome = m.groupValues[2]
        if (seen[path] == null || outcome.isNotEmpty()) seen[path] = GradleTask(path, outcome)
    }
    return seen.values.toList()
}

/** Label singkat di kanan baris tugas (kosong = tugas dijalankan biasa). */
fun outcomeLabel(outcome: String): String = when (outcome) {
    "UP-TO-DATE" -> "TERKINI"
    "NO-SOURCE" -> "TANPA SUMBER"
    "FROM-CACHE" -> "DARI CACHE"
    "SKIPPED" -> "DILEWATI"
    "FAILED" -> "GAGAL"
    else -> ""
}

private val NOISE_RE = Regex("> Task :[\\w:.\\-]+[ \\t]+(UP-TO-DATE|NO-SOURCE|FROM-CACHE|SKIPPED)")

/** Baris "Task … UP-TO-DATE" dan sejenisnya: tidak ada isinya selain status, disembunyikan tombol mata. */
fun isTaskNoise(line: String): Boolean = NOISE_RE.containsMatchIn(line)
