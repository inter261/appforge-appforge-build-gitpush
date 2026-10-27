package com.app.forge.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AppForge", text))
}
