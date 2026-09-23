package com.zipextract.app.data.cloud

import android.net.Uri

data class SafBookmark(
    val uri: String,
    val label: String,
)

fun Uri.toSafBookmark(label: String): SafBookmark {
    return SafBookmark(uri = toString(), label = label)
}
