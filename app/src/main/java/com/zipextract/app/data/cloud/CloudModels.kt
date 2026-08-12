package com.zipextract.app.data.cloud

import android.net.Uri

data class CloudFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val isFolder: Boolean,
    val modifiedTime: String? = null,
    val webViewLink: String? = null,
)

data class SafBookmark(
    val uri: String,
    val label: String,
)

sealed class CloudAuthState {
    data object SignedOut : CloudAuthState()
    data class SignedIn(val email: String, val displayName: String?) : CloudAuthState()
    data class NeedsSetup(val message: String) : CloudAuthState()
    data class Error(val message: String) : CloudAuthState()
}

data class DriveBrowseState(
    val folderId: String = "root",
    val folderName: String = "My Drive",
    val parentStack: List<Pair<String, String>> = emptyList(),
    val files: List<CloudFileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

fun Uri.toSafBookmark(label: String): SafBookmark {
    return SafBookmark(uri = toString(), label = label)
}
