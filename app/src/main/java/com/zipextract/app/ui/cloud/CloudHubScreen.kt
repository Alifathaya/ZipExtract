package com.zipextract.app.ui.cloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.zipextract.app.data.FileActions
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.cloud.CloudAuthState
import com.zipextract.app.data.cloud.CloudFileItem
import com.zipextract.app.data.cloud.DriveBrowseState
import com.zipextract.app.data.cloud.GoogleDriveClient
import com.zipextract.app.data.cloud.SafBookmark
import com.zipextract.app.data.cloud.SafCloudAccess
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudHubScreen(
    bookmarks: List<SafBookmark>,
    onClose: () -> Unit,
    onOpenImportedFile: (File) -> Unit,
    onBookmarksChanged: (List<SafBookmark>) -> Unit,
    onExportLocalFile: File? = null,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveClient = remember { GoogleDriveClient(context) }

    var authState by remember { mutableStateOf(driveClient.authState()) }
    var driveState by remember { mutableStateOf(DriveBrowseState()) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var selectedBookmarkUri by remember { mutableStateOf<String?>(null) }
    var safChildren by remember { mutableStateOf<List<CloudFileItem>>(emptyList()) }

    fun refreshAuth() {
        authState = driveClient.authState()
    }

    fun refreshDrive(folderId: String = driveState.folderId, folderName: String = driveState.folderName) {
        if (authState !is CloudAuthState.SignedIn) return
        driveState = driveState.copy(loading = true, error = null, folderId = folderId, folderName = folderName)
        scope.launch {
            val result = driveClient.listFolder(folderId)
            driveState = result.fold(
                onSuccess = { files ->
                    driveState.copy(loading = false, files = files, error = null)
                },
                onFailure = { err ->
                    driveState.copy(loading = false, error = err.message ?: "Gagal memuat Drive")
                },
            )
        }
    }

    fun refreshSafChildren(uriString: String?) {
        selectedBookmarkUri = uriString
        if (uriString == null) {
            safChildren = emptyList()
            return
        }
        safChildren = SafCloudAccess.listChildren(context, Uri.parse(uriString))
    }

    LaunchedEffect(authState) {
        if (authState is CloudAuthState.SignedIn) {
            refreshDrive("root", "My Drive")
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        SafCloudAccess.takePersistableReadWrite(context, uri, write = false)
        busyMessage = "Mengimpor file…"
        scope.launch {
            val file = SafCloudAccess.copyUriToCache(context, uri)
            busyMessage = null
            if (file == null) {
                Toast.makeText(context, "Gagal membuka file cloud", Toast.LENGTH_SHORT).show()
            } else {
                onOpenImportedFile(file)
            }
        }
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        SafCloudAccess.takePersistableReadWrite(context, uri, write = true)
        val label = SafCloudAccess.labelForTree(context, uri)
        val next = (bookmarks.filterNot { it.uri == uri.toString() } + SafBookmark(uri.toString(), label))
            .distinctBy { it.uri }
        onBookmarksChanged(next)
        refreshSafChildren(uri.toString())
        Toast.makeText(context, "Folder cloud ditautkan: $label", Toast.LENGTH_SHORT).show()
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val source = onExportLocalFile
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        busyMessage = "Menyimpan ke cloud…"
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            busyMessage = null
            Toast.makeText(
                context,
                if (ok) "Berhasil disimpan ke cloud" else "Gagal menyimpan ke cloud",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Login Google dibatalkan", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(ApiException::class.java)
            refreshAuth()
            Toast.makeText(context, "Login Google Drive berhasil", Toast.LENGTH_SHORT).show()
        } catch (e: ApiException) {
            Toast.makeText(context, "Login gagal: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            refreshAuth()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cloud", fontWeight = FontWeight.Bold)
                        Text(
                            text = "SAF + Google Drive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (authState is CloudAuthState.SignedIn) {
                        IconButton(onClick = { refreshDrive() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Drive")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "Cloud via sistem (SAF)", subtitle = "Drive, Dropbox, OneDrive, dll.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buka file")
                        }
                        OutlinedButton(
                            onClick = { openTreeLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Tautkan folder")
                        }
                    }
                    if (onExportLocalFile != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                createDocumentLauncher.launch(onExportLocalFile.name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Simpan ${onExportLocalFile.name} ke cloud")
                        }
                    }
                }
            }

            if (bookmarks.isNotEmpty()) {
                item {
                    Text("Folder tertaut", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(bookmarks, key = { it.uri }) { bookmark ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { refreshSafChildren(bookmark.uri) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.label, fontWeight = FontWeight.Medium)
                                Text(
                                    bookmark.uri,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    SafCloudAccess.releasePersistable(context, Uri.parse(bookmark.uri))
                                    val next = bookmarks.filterNot { it.uri == bookmark.uri }
                                    onBookmarksChanged(next)
                                    if (selectedBookmarkUri == bookmark.uri) refreshSafChildren(null)
                                },
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = "Lepas tautan")
                            }
                        }
                    }
                }
            }

            if (safChildren.isNotEmpty()) {
                item {
                    Text("Isi folder tertaut", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(safChildren, key = { it.id }) { child ->
                    CloudRow(
                        item = child,
                        onClick = {
                            if (child.isFolder) return@CloudRow
                            busyMessage = "Mengimpor ${child.name}…"
                            scope.launch {
                                val file = SafCloudAccess.copyUriToCache(context, Uri.parse(child.id), child.name)
                                busyMessage = null
                                if (file != null) onOpenImportedFile(file)
                                else Toast.makeText(context, "Gagal impor", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            item { HorizontalDivider() }

            item {
                SectionCard(title = "Google Drive", subtitle = "Login native & browse file") {
                    when (val auth = authState) {
                        is CloudAuthState.NeedsSetup -> {
                            Text(auth.message, style = MaterialTheme.typography.bodySmall)
                        }
                        is CloudAuthState.SignedOut -> {
                            Button(
                                onClick = { driveSignInLauncher.launch(driveClient.signInIntent()) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Login Google Drive")
                            }
                        }
                        is CloudAuthState.SignedIn -> {
                            Text("Masuk sebagai ${auth.displayName ?: auth.email}")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    driveClient.signOut {
                                        refreshAuth()
                                        driveState = DriveBrowseState()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Logout, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Keluar")
                            }
                        }
                        is CloudAuthState.Error -> Text(auth.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (authState is CloudAuthState.SignedIn) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (driveState.parentStack.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val stack = driveState.parentStack.toMutableList()
                                    val parent = stack.removeLast()
                                    driveState = driveState.copy(parentStack = stack)
                                    refreshDrive(parent.first, parent.second)
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Folder sebelumnya")
                            }
                        }
                        Text(
                            text = driveState.folderName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (driveState.loading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }

                driveState.error?.let { err ->
                    item { Text(err, color = MaterialTheme.colorScheme.error) }
                }

                items(driveState.files, key = { it.id }) { file ->
                    CloudRow(
                        item = file,
                        onClick = {
                            if (file.isFolder) {
                                val stack = driveState.parentStack + (driveState.folderId to driveState.folderName)
                                driveState = driveState.copy(parentStack = stack)
                                refreshDrive(file.id, file.name)
                            } else {
                                busyMessage = "Mengunduh ${file.name}…"
                                scope.launch {
                                    val result = driveClient.downloadFile(file.id, file.name, file.mimeType)
                                    busyMessage = null
                                    result.fold(
                                        onSuccess = { local ->
                                            Toast.makeText(context, "Diunduh: ${local.name}", Toast.LENGTH_SHORT).show()
                                            onOpenImportedFile(local)
                                        },
                                        onFailure = { err ->
                                            Toast.makeText(context, err.message ?: "Gagal unduh", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                            }
                        },
                        trailing = {
                            if (!file.isFolder) {
                                IconButton(
                                    onClick = {
                                        busyMessage = "Mengunduh ${file.name}…"
                                        scope.launch {
                                            val result = driveClient.downloadFile(file.id, file.name, file.mimeType)
                                            busyMessage = null
                                            result.onSuccess { local ->
                                                FileActions.shareFile(context, local)
                                            }.onFailure {
                                                Toast.makeText(context, it.message ?: "Gagal", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Unduh & bagikan")
                                }
                            }
                        },
                    )
                }

                if (onExportLocalFile != null) {
                    item {
                        Button(
                            onClick = {
                                val local = onExportLocalFile
                                busyMessage = "Mengunggah ${local.name}…"
                                scope.launch {
                                    val result = driveClient.uploadFile(local, driveState.folderId)
                                    busyMessage = null
                                    result.fold(
                                        onSuccess = {
                                            Toast.makeText(context, "Terunggah ke Drive", Toast.LENGTH_SHORT).show()
                                            refreshDrive()
                                        },
                                        onFailure = {
                                            Toast.makeText(context, it.message ?: "Upload gagal", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unggah ${onExportLocalFile.name} ke folder ini")
                        }
                    }
                }
            }

            if (busyMessage != null) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(busyMessage!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun CloudRow(
    item: CloudFileItem,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.isFolder) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    text = if (item.isFolder) "Folder" else (item.mimeType.ifBlank { "File" }),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
    }
}
