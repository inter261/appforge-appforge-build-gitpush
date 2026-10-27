package com.app.forge.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Entry(
    val id: Long,
    val project: String,
    val success: Boolean,
    val seconds: Int,
    val message: String,
    val apkSize: Long,
    /** "apk" = bisa diinstal, "zip" = artifact mentah (Node/Python/dll), "none" = tanpa hasil. */
    val kind: String = "apk"
)

class HistoryStore(private val ctx: Context) {
    private fun dir(p: String) = File(ctx.filesDir, "history/$p").also { it.mkdirs() }
    private fun apkDir() = File(ctx.getExternalFilesDir(null), "apk").also { it.mkdirs() }

    fun output(e: Entry) = File(apkDir(), "${e.project}-${e.id}.${if (e.kind == "zip") "zip" else "apk"}")

    fun list(p: String): List<Entry> =
        (dir(p).listFiles()?.filter { it.extension == "json" } ?: emptyList()).mapNotNull { f ->
            runCatching {
                val j = JSONObject(f.readText())
                Entry(j.getLong("id"), p, j.getBoolean("ok"), j.getInt("sec"), j.optString("msg"), j.optLong("size"), j.optString("kind", "apk"))
            }.getOrNull()
        }.sortedByDescending { it.id }

    fun get(p: String, id: Long): Entry? = list(p).firstOrNull { it.id == id }

    fun log(e: Entry): String =
        runCatching { File(dir(e.project), "${e.id}.log").readText() }.getOrDefault("")

    fun save(e: Entry, log: String) {
        val d = dir(e.project)
        File(d, "${e.id}.json").writeText(
            JSONObject().put("id", e.id).put("ok", e.success).put("sec", e.seconds)
                .put("msg", e.message).put("size", e.apkSize).put("kind", e.kind).toString()
        )
        File(d, "${e.id}.log").writeText(log)
        list(e.project).drop(20).forEach { delete(it) } // simpan 20 build terakhir
    }

    fun delete(e: Entry) {
        val d = dir(e.project)
        File(d, "${e.id}.json").delete()
        File(d, "${e.id}.log").delete()
        output(e).delete()
    }
}
