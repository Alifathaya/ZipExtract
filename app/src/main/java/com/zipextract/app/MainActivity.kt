package com.zipextract.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zipextract.app.ui.FileBrowserScreen
import com.zipextract.app.ui.FileBrowserViewModel
import com.zipextract.app.ui.theme.ZipExtractTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: FileBrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ZipExtractTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                val legacyPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.setStorageGranted(hasStorageAccess())
                }

                val manageStorageLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.setStorageGranted(hasStorageAccess())
                }

                fun requestStorageAccess() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            manageStorageLauncher.launch(intent)
                        } catch (_: Exception) {
                            manageStorageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        }
                    } else {
                        legacyPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.setStorageGranted(hasStorageAccess())
                    handleIncomingIntent(intent)
                }

                LaunchedEffect(viewModel) {
                    viewModel.events.collect { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    FileBrowserScreen(
                        state = state,
                        onOpen = viewModel::openDirectory,
                        onOpenItem = viewModel::openItem,
                        onGoUp = viewModel::goUp,
                        onRefresh = viewModel::refresh,
                        onToggleSelect = viewModel::toggleSelect,
                        onToggleSelectionMode = viewModel::toggleSelectionMode,
                        onSelectAll = viewModel::selectAll,
                        onClearSelection = viewModel::clearSelection,
                        onCopy = viewModel::copySelected,
                        onCut = viewModel::cutSelected,
                        onPaste = viewModel::paste,
                        onDelete = viewModel::deleteSelected,
                        onCreateFolder = viewModel::createFolder,
                        onRename = viewModel::renameSelected,
                        onCreateZip = viewModel::createZip,
                        onOpenExtract = { viewModel.openExtractDialog(it.file) },
                        onToggleSort = viewModel::toggleSort,
                        onRequestPermission = { requestStorageAccess() },
                        onCloseViewer = viewModel::closeViewer,
                        onCloseExtract = viewModel::closeExtractDialog,
                        onToggleExtractEntry = viewModel::toggleExtractEntry,
                        onSelectAllExtractEntries = viewModel::selectAllExtractEntries,
                        onDeselectAllExtractEntries = viewModel::deselectAllExtractEntries,
                        onDeleteOriginalZipChange = viewModel::setDeleteOriginalZip,
                        onConfirmExtract = viewModel::confirmExtract,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setStorageGranted(hasStorageAccess())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val path = uri.path ?: return
        val file = when (uri.scheme) {
            "file" -> File(path)
            else -> null
        } ?: return
        if (!file.exists()) return

        val item = com.zipextract.app.data.FileItem(file)
        when {
            item.isArchive -> viewModel.extractZipFile(file)
            item.isPdf || item.isImage -> {
                viewModel.navigateTo(file.parentFile ?: return)
                viewModel.openViewerFile(file)
            }
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            read && write
        }
    }
}
