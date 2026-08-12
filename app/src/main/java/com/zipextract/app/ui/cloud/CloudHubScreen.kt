package com.zipextract.app.ui.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.zipextract.app.data.FileActions
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

    fun importCloudUri(uri: Uri, tryPersist: Boolean = true) {
        busyMessage = "Mengimpor file…"
        scope.launch {
            val (file, error) = SafCloudAccess.copyUriToCache(
                context,
                uri,
                tryPersist = tryPersist,
            )
            busyMessage = null
            if (file == null) {
                Toast.makeText(context, error ?: "Gagal membuka file cloud", Toast.LENGTH_LONG).show()
            } else {
                onOpenImportedFile(file)
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.OpenCloudDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importCloudUri(uri, tryPersist = true)
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.GetCloudContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // GET_CONTENT is one-shot; skip persistable take (Drive often blocks it as "system protected").
        importCloudUri(uri, tryPersist = false)
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.OpenCloudTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val persisted = SafCloudAccess.tryTakePersistable(context, uri, allowWrite = true)
        val label = SafCloudAccess.labelForTree(context, uri)
        if (!persisted) {
            Toast.makeText(
                context,
                "Folder terbuka sementara. Provider ini membatasi tautan permanen (umum di Google Drive).",
                Toast.LENGTH_LONG,
            ).show()
        }
        val next = (bookmarks.filterNot { it.uri == uri.toString() } + SafBookmark(uri.toString(), label))
            .distinctBy { it.uri }
        onBookmarksChanged(next)
        refreshSafChildren(uri.toString())
        Toast.makeText(context, "Folder ditautkan: $label", Toast.LENGTH_SHORT).show()
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.CreateCloudDocument(),
    ) { uri ->
        val source = onExportLocalFile
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        SafCloudAccess.tryTakePersistable(context, uri, allowWrite = true)
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
                if (ok) "Berhasil disimpan ke cloud" else "Gagal menyimpan. Coba lokasi lain (Download/Files).",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Even on cancel, parse intent — DEVELOPER_ERROR (10) often returns RESULT_CANCELED.
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(ApiException::class.java)
            refreshAuth()
            Toast.makeText(context, "Login Google Drive berhasil", Toast.LENGTH_SHORT).show()
        } catch (e: ApiException) {
            val message = when {
                e.statusCode == 12501 -> "Login dibatalkan"
                result.resultCode != Activity.RESULT_OK && e.statusCode == 4 ->
                    "Login dibatalkan / belum pilih akun"
                else -> driveClient.describeSignInError(e.statusCode)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            refreshAuth()
        } catch (t: Throwable) {
            if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(context, "Login Google dibatalkan", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, t.message ?: "Login gagal", Toast.LENGTH_LONG).show()
            }
            refreshAuth()
        }
    }

    fun launchGoogleSignIn() {
        val availability = GoogleApiAvailability.getInstance()
        val code = availability.isGooglePlayServicesAvailable(context)
        if (code != ConnectionResult.SUCCESS) {
            val activity = context.findActivity()
            if (activity != null && availability.isUserResolvableError(code)) {
                availability.getErrorDialog(activity, code, 2404)?.show()
            } else {
                Toast.makeText(
                    context,
                    "Google Play Services tidak tersedia (kode $code)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        Toast.makeText(context, "Membuka login Google…", Toast.LENGTH_SHORT).show()
        runCatching {
            driveSignInLauncher.launch(driveClient.signInIntent())
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Tidak bisa membuka login Google: ${err.message ?: err.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
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
                SectionCard(title = "Google Drive", subtitle = "Login native & browse file") {
                    when (val auth = authState) {
                        is CloudAuthState.NeedsSetup,
                        is CloudAuthState.SignedOut,
                        -> {
                            Button(
                                onClick = { launchGoogleSignIn() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Login Google Drive")
                            }
                            val hint = driveClient.setupHintOrNull()
                                ?: (auth as? CloudAuthState.NeedsSetup)?.message
                            if (!hint.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                        is CloudAuthState.Error -> {
                            Text(auth.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { launchGoogleSignIn() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Coba login lagi")
                            }
                        }
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
                            modifier = Modifier.weight(1f),
                        )
                        if (driveState.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                driveState.error?.let { err ->
                    item {
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                items(driveState.files, key = { "drive-${it.id}" }) { file ->
                    CloudRow(
                        item = file,
                        onClick = {
                            if (file.isFolder) {
                                driveState = driveState.copy(
                                    parentStack = driveState.parentStack +
                                        (driveState.folderId to driveState.folderName),
                                )
                                refreshDrive(file.id, file.name)
                            } else {
                                busyMessage = "Mengunduh ${file.name}…"
                                scope.launch {
                                    val result = driveClient.downloadFile(file.id, file.name, file.mimeType)
                                    busyMessage = null
                                    result.fold(
                                        onSuccess = { local -> onOpenImportedFile(local) },
                                        onFailure = { e ->
                                            Toast.makeText(
                                                context,
                                                e.message ?: "Gagal unduh",
                                                Toast.LENGTH_LONG,
                                            ).show()
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
                                            val result = driveClient.downloadFile(
                                                file.id,
                                                file.name,
                                                file.mimeType,
                                            )
                                            busyMessage = null
                                            result.fold(
                                                onSuccess = { local ->
                                                    FileActions.shareFile(context, local)
                                                },
                                                onFailure = { e ->
                                                    Toast.makeText(
                                                        context,
                                                        e.message ?: "Gagal unduh",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                },
                                            )
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
                        OutlinedButton(
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
                                        onFailure = { e ->
                                            Toast.makeText(
                                                context,
                                                e.message ?: "Upload gagal",
                                                Toast.LENGTH_LONG,
                                            ).show()
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

            item { HorizontalDivider() }

            item {
                SectionCard(
                    title = "Cloud via sistem (SAF)",
                    subtitle = "Untuk Google Drive, pakai tombol Get Content dulu",
                ) {
                    Button(
                        onClick = { getContentLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buka file cloud (disarankan)")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                openDocumentLauncher.launch(
                                    arrayOf(
                                        "*/*",
                                        "application/pdf",
                                        "image/*",
                                        "application/zip",
                                        "application/vnd.android.package-archive",
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Documents")
                        }
                        OutlinedButton(
                            onClick = { openTreeLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Folder")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Catatan: beberapa file Drive dilindungi sistem. Jika gagal, unduh dulu di app Drive atau pakai Login Google Drive native.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                val (file, error) = SafCloudAccess.copyUriToCache(
                                    context,
                                    Uri.parse(child.id),
                                    child.name,
                                )
                                busyMessage = null
                                if (file != null) onOpenImportedFile(file)
                                else Toast.makeText(context, error ?: "Gagal impor", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
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

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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
