package com.zipextract.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zipextract.app.data.AppPreferences
import com.zipextract.app.data.LocaleHelper
import com.zipextract.app.data.ThemeMode
import com.zipextract.app.ui.FileBrowserScreen
import com.zipextract.app.ui.FileBrowserViewModel
import com.zipextract.app.ui.theme.FileNestTheme

/**
 * AppCompatActivity so [AppCompatDelegate.setApplicationLocales] applies reliably
 * and dialogs / selection actions follow the chosen language.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: FileBrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved locale before Compose inflates UI.
        LocaleHelper.applyFromPreferences(AppPreferences(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            key(state.appLanguage) {
                FileNestTheme(darkTheme = darkTheme) {
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
                        onOpenExtract = viewModel::openExtractDialogForItem,
                        onOpenCategory = viewModel::openCategory,
                        onBrowseAll = viewModel::browseAllFiles,
                        onGoHome = viewModel::goHome,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onClearSearch = viewModel::clearSearch,
                        onOpenFileAnywhere = viewModel::openFileFromAnywhere,
                        onSetFileFilter = viewModel::setFileFilter,
                        onToggleSort = viewModel::toggleSort,
                        onRequestPermission = { requestStorageAccess() },
                        onCloseViewer = {
                            if (viewModel.closeViewer()) {
                                finish()
                            }
                        },
                        onCloseExtract = viewModel::closeExtractDialog,
                        onDeleteOriginalZipChange = viewModel::setDeleteOriginalZip,
                        onExtractPasswordChange = viewModel::setExtractPassword,
                        onConfirmExtract = viewModel::confirmExtract,
                        onDismissExtractResult = viewModel::dismissExtractResult,
                        onOpenExtractResultFolder = viewModel::openExtractResultFolder,
                        onShareSelected = { viewModel.shareSelected(context) },
                        onOpenWithSelected = { viewModel.openWithSelected(context) },
                        onToggleFavoriteSelected = viewModel::toggleFavoriteSelected,
                        onShowSelectedDetails = viewModel::showSelectedDetails,
                        onCloseFileDetails = viewModel::closeFileDetails,
                        onOpenParentOfDetails = viewModel::openParentOfDetails,
                        onOpenFavorites = viewModel::openFavorites,
                        onOpenLargestFiles = viewModel::openLargestFiles,
                        onSetThemeMode = viewModel::setThemeMode,
                        onSetAppLanguage = viewModel::setAppLanguage,
                        onSetLibrarySubFilter = viewModel::setLibrarySubFilter,
                        onSetMediaAlbum = viewModel::setMediaAlbum,
                        onFindDuplicates = viewModel::findDuplicates,
                        onCloseDuplicates = viewModel::closeDuplicates,
                        onDeleteDuplicateExtras = viewModel::deleteDuplicateExtras,
                        onCancelProgress = viewModel::cancelActiveJob,
                        onToggleFavoritePath = viewModel::toggleFavorite,
                        onShowFileDetails = viewModel::showFileDetails,
                        onOpenCloud = { viewModel.openCloud() },
                        onCloseCloud = viewModel::closeCloud,
                        onUpdateSafBookmarks = viewModel::updateSafBookmarks,
                        onOpenImportedCloudFile = viewModel::openImportedCloudFile,
                    )
                }
                }
            }
        }
    }

    override fun onStop() {
        // Persist home/media snapshot while process is still alive so next open is instant.
        viewModel.persistHomeCache()
        super.onStop()
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
        if (intent == null) return
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val uri = extractIncomingUri(intent) ?: return
        val mimeType = intent.type ?: contentResolver.getType(uri)
        viewModel.openSharedUri(this, uri, mimeType)
    }

    private fun extractIncomingUri(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
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
