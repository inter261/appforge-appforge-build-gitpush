package com.app.forge.util

import java.io.File

enum class FileKind { TEXT, IMAGE, BINARY, TOO_LARGE }

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
private val BINARY_EXT = setOf(
    "ico", "jar", "aar", "apk", "aab", "zip", "gz", "keystore", "jks", "p12", "so", "ttf", "otf", "woff",
    "mp3", "mp4", "ogg", "wav", "m4a", "pdf", "class", "dex", "bin", "7z", "rar"
)

const val MAX_TEXT_BYTES = 2_000_000L

/** Menentukan apakah file bisa dibuka sebagai teks di editor. */
fun fileKind(f: File): FileKind {
    val ext = f.extension.lowercase()
    if (ext in IMAGE_EXT) return FileKind.IMAGE
    if (ext in BINARY_EXT) return FileKind.BINARY
    if (f.length() > MAX_TEXT_BYTES) return FileKind.TOO_LARGE
    // Cari byte NUL di 4 KB pertama sebagai tanda file biner.
    val buf = ByteArray(4096)
    val n = runCatching { f.inputStream().use { it.read(buf) } }.getOrDefault(0)
    return if ((0 until n.coerceAtLeast(0)).any { buf[it].toInt() == 0 }) FileKind.BINARY else FileKind.TEXT
}
