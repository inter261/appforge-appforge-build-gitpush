package com.app.forge.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmt(ms: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))
