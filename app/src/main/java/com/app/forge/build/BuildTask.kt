package com.app.forge.build

import com.app.forge.data.ProjectConfig
import com.app.forge.template.Templates

/** Pilihan "tugas" saat menekan Run: varian (debug/release) dan format hasil (APK atau AAB). */
data class BuildTask(val id: String, val title: String, val release: Boolean, val bundle: Boolean) {
    companion object {
        fun tasks(cfg: ProjectConfig): List<BuildTask> {
            val debug = BuildTask("run-debug", "Run Debug", false, false)
            val release = BuildTask("run-release", "Run Release", true, false)
            val bundleDebug = BuildTask("bundle-debug", "bundleDebug (.aab)", false, true)
            val bundleRelease = BuildTask("bundle-release", "bundleRelease (.aab)", true, true)
            return when {
                cfg.template == "android" ->
                    if (cfg.release) listOf(release, bundleRelease, debug, bundleDebug)
                    else listOf(debug, bundleDebug, release, bundleRelease)
                Templates.hasBuildType(cfg.template) ->
                    if (cfg.release) listOf(release, debug) else listOf(debug, release)
                else -> listOf(BuildTask("run", "Run build", false, false))
            }
        }

        fun default(cfg: ProjectConfig): BuildTask = tasks(cfg).first { !it.bundle }
    }
}
