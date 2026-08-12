package com.zipextract.app.ui.cloud

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.cloud.SafCloudAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudHubScreen(
    bookmarks: List<com.zipextract.app.data.cloud.SafBookmark> = emptyList(),
    onClose: () -> Unit,
    onOpenImportedFile: (File) -> Unit,
    onBookmarksChanged: (List<com.zipextract.app.data.cloud.SafBookmark>) -> Unit = {},
    onExportLocalFile: File? = null,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busyMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    fun importCloudUri(uri: Uri) {
        if (importing) {
            Toast.makeText(context, "Sedang membuka file, tunggu sebentar…", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        busyMessage = "Mengimpor file…"
        importJob?.cancel()
        importJob = scope.launch {
            try {
                val (file, error) = withContext(Dispatchers.IO) {
                    SafCloudAccess.copyUriToCache(
                        context = context,
                        uri = uri,
                        tryPersist = false,
                    )
                }
                if (file == null) {
                    Toast.makeText(
                        context,
                        error ?: "Gagal membuka file cloud",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    // Hand off on main after copy fully finished to avoid race / laggy open.
                    onOpenImportedFile(file)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Newer pick cancelled this import.
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    t.message ?: "Gagal membuka file cloud",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                importing = false
                busyMessage = null
            }
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.GetCloudContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importCloudUri(uri)
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.CreateCloudDocument(),
    ) { uri ->
        val source = onExportLocalFile
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        if (importing) {
            Toast.makeText(context, "Tunggu proses sebelumnya selesai", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        importing = true
        busyMessage = "Menyimpan ke cloud…"
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        SafCloudAccess.tryTakePersistable(context, uri, allowWrite = true)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            source.inputStream().use { input -> input.copyTo(out) }
                        } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) "Berhasil disimpan ke cloud" else "Gagal menyimpan. Coba lokasi lain.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                importing = false
                busyMessage = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cloud", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Buka file via penyimpanan sistem",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !importing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
                SectionCard(
                    title = "Buka dari cloud / Files",
                    subtitle = "Pilih file dari Google Drive, Downloads, atau penyedia lain",
                ) {
                    Button(
                        onClick = {
                            if (importing) {
                                Toast.makeText(
                                    context,
                                    "Sedang membuka file, tunggu sebentar…",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@Button
                            }
                            getContentLauncher.launch("*/*")
                        },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buka file cloud")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Jika file Drive gagal dibuka, unduh dulu di app Files/Drive lalu pilih lagi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onExportLocalFile != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (importing) return@OutlinedButton
                                createDocumentLauncher.launch(onExportLocalFile.name)
                            },
                            enabled = !importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Simpan ${onExportLocalFile.name} ke cloud")
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
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
