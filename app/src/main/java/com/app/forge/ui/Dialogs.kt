package com.app.forge.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Kolom isian bergaya Material untuk dialog. */
fun Context.field(hint: String, text: String = "", secret: Boolean = false, number: Boolean = false, multiline: Boolean = false): TextInputLayout {
    val til = TextInputLayout(this)
    til.hint = hint
    val et = TextInputEditText(til.context)
    et.setText(text)
    et.inputType = when {
        number -> InputType.TYPE_CLASS_NUMBER
        secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return til
}

val TextInputLayout.value: String get() = editText?.text?.toString().orEmpty()

/** Susun [views] vertikal dengan jarak, untuk isi dialog. */
fun Context.column(vararg views: View): LinearLayout {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(24), dp(8), dp(24), dp(0))
    for (v in views) {
        col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
    }
    return col
}
