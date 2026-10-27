package com.app.forge.build

import com.app.forge.github.Step

private val TS = Regex("^\uFEFF?(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d)(?:\\.\\d+)?Z ?")

/**
 * Membagi log mentah satu job GitHub Actions per langkah. Tiap baris log berawalan cap waktu, dan tiap langkah punya
 * started_at dari API, jadi sebuah baris dimasukkan ke langkah terakhir yang sudah mulai pada saat baris itu ditulis.
 * Baris tanpa cap waktu (lanjutan baris sebelumnya) ikut langkah baris di atasnya.
 * Kembalian: indeks langkah -> baris log tanpa cap waktu; null kalau log tidak memuat cap waktu sama sekali.
 */
fun splitStepLogs(raw: String, steps: List<Step>): Map<Int, List<String>>? {
    if (raw.isBlank() || steps.isEmpty()) return null
    val starts = steps.map { (it.startedAt / 1000) * 1000 }
    val out = HashMap<Int, MutableList<String>>()
    var cur = steps.indexOfFirst { it.startedAt > 0 }.coerceAtLeast(0)
    var sawStamp = false
    var lastKey = ""
    var lastMs = 0L
    for (line in raw.lines()) {
        val m = TS.find(line)
        if (m != null) {
            sawStamp = true
            val key = m.groupValues[1]
            if (key != lastKey) {
                lastKey = key
                lastMs = runCatching { java.time.Instant.parse(key + "Z").toEpochMilli() }.getOrDefault(lastMs)
            }
            var idx = cur
            for (i in steps.indices) if (starts[i] > 0 && starts[i] <= lastMs) idx = i
            // Langkah tidak pernah mundur: baris yang lebih tua dari langkah sekarang tetap di langkah sekarang.
            if (idx > cur) cur = idx
        }
        val text = if (m != null) line.substring(m.value.length) else line
        out.getOrPut(cur) { ArrayList() }.add(text)
    }
    return if (sawStamp) out else null
}
