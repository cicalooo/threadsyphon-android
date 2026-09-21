package com.threadsyphon.android.data.engine

import android.text.Html
import java.util.regex.Pattern

private val BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE)
private val TAG = Pattern.compile("<[^>]+>")
private val SPACES = Pattern.compile("\\s+")

fun stripHtml(value: String?): String {
    if (value.isNullOrBlank()) return ""
    var text = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    // Fallback if fromHtml leaves tags (rare)
    text = BR.matcher(text).replaceAll(" ")
    text = TAG.matcher(text).replaceAll(" ")
    text = SPACES.matcher(text).replaceAll(" ").trim()
    return text
}

fun safeFilename(value: String, fallback: String = "media"): String {
    var v = stripHtml(value)
    v = v.replace(Regex("""[\x00-\x1f<>:"/\\|?*]"""), "_").trim(' ', '.')
    v = SPACES.matcher(v).replaceAll(" ")
    if (v.isEmpty()) v = fallback
    return v.take(160).trimEnd(' ', '.').ifEmpty { fallback }
}
