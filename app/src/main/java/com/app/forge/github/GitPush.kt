package com.app.forge.github

import org.eclipse.jgit.api.Git as JGit
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.FileInputStream

/**
 * Kirim proyek dengan protokol git sungguhan (seperti `git push` di Termux), bukan satu-satu lewat REST API.
 * File dibaca sebagai aliran dari disk (tidak dimuat penuh ke RAM), dikompres jadi satu paket, lalu dikirim sekali jalan.
 * Repo git lokal (bare) disimpan di [gitRoot] dan dipakai ulang, jadi build berikutnya hanya mengirim objek yang baru.
 *
 * PERHATIAN: memakai force push. Hanya dipakai untuk repo buatan AppForge, bukan repo milik user.
 */
class GitPush(private val gitRoot: File, private val token: String) {

    init {
        // JGit mencari ~/.gitconfig; di Android user.home sering kosong.
        if (System.getProperty("user.home").isNullOrBlank()) System.setProperty("user.home", gitRoot.absolutePath)
    }

    fun gitDirFor(owner: String, repo: String) = File(gitRoot, "$owner--$repo.git")

    /** Mengembalikan jumlah file baru/berubah yang dikirim; 0 = remote sudah sama persis. */
    fun push(
        owner: String, repo: String, branch: String,
        workDir: File, paths: List<String>, inMemory: Map<String, ByteArray>,
        message: String,
        note: (String) -> Unit = {},
        progress: (UploadProgress) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Int {
        gitRoot.mkdirs()
        val gitDir = gitDirFor(owner, repo)
        val repository = FileRepositoryBuilder().setGitDir(gitDir).setBare().build()
        try {
            if (!File(gitDir, "HEAD").exists()) repository.create(true)
            // Kompresi ringan: file biner (.so/.apk/.aar) sudah terkompres, level tinggi hanya membuang CPU dan baterai.
            repository.config.apply {
                setInt("core", null, "compression", 1)
                setInt("pack", null, "compression", 1)
                save()
            }

            val ins = repository.newObjectInserter()
            val odb = repository.objectDatabase
            val dc = DirCache.newInCore()
            val builder = dc.builder()
            var changed = 0
            var changedBytes = 0L

            for (p in paths) {
                if (cancelled()) error("Dibatalkan")
                val mem = inMemory[p]
                val id: ObjectId
                if (mem != null) {
                    id = ins.idFor(Constants.OBJ_BLOB, mem)
                    if (!odb.has(id)) { ins.insert(Constants.OBJ_BLOB, mem); changed++; changedBytes += mem.size }
                } else {
                    val f = File(workDir, p)
                    val len = f.length()
                    id = FileInputStream(f).use { ins.idFor(Constants.OBJ_BLOB, len, it) }
                    if (!odb.has(id)) {
                        FileInputStream(f).use { ins.insert(Constants.OBJ_BLOB, len, it) }
                        changed++; changedBytes += len
                    }
                }
                val e = DirCacheEntry(p)
                e.setFileMode(FileMode.REGULAR_FILE)
                e.setObjectId(id)
                builder.add(e)
            }
            builder.finish()
            val treeId = dc.writeTree(ins)
            note("Berubah: $changed dari ${paths.size} file, total ${fmtSize(changedBytes)}")

            val ref = "refs/heads/$branch"
            val head = repository.resolve(ref)
            val commitId: ObjectId =
                if (head != null && RevWalk(repository).use { it.parseCommit(head).tree.id } == treeId) head
                else {
                    val who = PersonIdent("AppForge", "appforge@users.noreply.github.com")
                    val cb = CommitBuilder()
                    cb.setTreeId(treeId)
                    if (head != null) cb.setParentId(head)
                    cb.setAuthor(who)
                    cb.setCommitter(who)
                    cb.setMessage(message)
                    ins.insert(cb)
                }
            ins.flush()
            ins.close()
            repository.updateRef(ref).apply { setNewObjectId(commitId); setForceUpdate(true); update() }

            progress(UploadProgress(0, changed, 0, changedBytes))
            note("Mengirim paket ke GitHub (git push)...")
            val monitor = object : ProgressMonitor {
                private var title = ""
                private var work = 0
                private var done = 0
                override fun start(totalTasks: Int) {}
                override fun endTask() {}
                override fun beginTask(title: String?, totalWork: Int) {
                    this.title = title ?: ""; work = totalWork; done = 0
                    if (this.title.isNotEmpty()) note("  ${this.title}${if (totalWork > 0) " ($totalWork)" else ""}")
                }
                override fun update(completed: Int) {
                    done += completed
                    // Hanya fase "Writing objects" yang benar-benar mengirim; hitungannya objek (blob+tree+commit), bukan file.
                    if (title.startsWith("Writing") && work > 0) {
                        val d = done.coerceAtMost(work)
                        progress(UploadProgress(d, work, changedBytes * d / work, changedBytes))
                    }
                }
                override fun isCancelled() = cancelled()
            }
            val results = JGit(repository).push()
                .setRemote("https://github.com/$owner/$repo.git")
                .setRefSpecs(RefSpec("+$ref:$ref"))
                .setForce(true)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                .setProgressMonitor(monitor)
                .setTimeout(900)
                .call()

            var anyNew = false
            for (r in results) for (u in r.remoteUpdates) {
                when (u.status) {
                    RemoteRefUpdate.Status.OK -> anyNew = true
                    RemoteRefUpdate.Status.UP_TO_DATE -> {}
                    else -> error("Push ditolak (${u.status}): ${u.message ?: ""}")
                }
            }
            progress(UploadProgress(changed, changed, changedBytes, changedBytes))
            return if (anyNew) changed.coerceAtLeast(1) else 0
        } finally {
            repository.close()
        }
    }
}
