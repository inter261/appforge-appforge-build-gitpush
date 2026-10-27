package com.app.forge.build

data class BuildError(val path: String, val line: Int, val msg: String)

// Cocok untuk error Kotlin ("e: file:///.../Foo.kt:12:5 pesan"), Java ("/.../Foo.java:10: error: pesan"),
// dan aapt2/manifest ("/.../x.xml:5: error: pesan").
private val ERR = Regex("""^(?:e: )?(?:file://)?(/\S+?\.(?:java|kt|kts|xml|gradle)):(\d+)(?::\d+)?(?:-\d+(?::\d+)?)?:?\s+(?:error: )?(.*)$""")
private val RUNNER = Regex("^/home/runner/work/[^/]+/[^/]+/")

fun parseErrors(log: String, files: Set<String>): List<BuildError> {
    val out = LinkedHashMap<String, BuildError>()
    for (raw in log.lineSequence()) {
        val l = raw.trim()
        if (l.startsWith("w: ") || l.contains("warning:")) continue
        val m = ERR.find(l) ?: continue
        val rel = m.groupValues[1].replace(RUNNER, "")
        if (rel !in files) continue
        val e = BuildError(rel, m.groupValues[2].toInt(), m.groupValues[3].trim())
        out.putIfAbsent("${e.path}:${e.line}:${e.msg}", e)
    }
    return out.values.toList()
}

fun errorSummary(errors: List<BuildError>, log: String): String =
    if (errors.isNotEmpty()) errors.joinToString("\n") { "${it.path}:${it.line} ${it.msg}" }
    else log.lines().takeLast(60).joinToString("\n")
