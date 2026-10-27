package com.app.forge.ui

import android.app.Activity
import java.util.concurrent.Executors

private val pool = Executors.newCachedThreadPool()

/** Jalankan [work] di thread latar belakang, lalu [done] di thread utama (dilewati kalau layarnya sudah ditutup). */
fun <T> Activity.bg(work: () -> T, done: (Result<T>) -> Unit) {
    pool.execute {
        val r = runCatching { work() }
        runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
    }
}

fun Throwable.msg(): String = message ?: toString()
