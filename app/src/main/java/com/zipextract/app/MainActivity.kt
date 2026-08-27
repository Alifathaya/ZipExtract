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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zipextract.app.data.AppPreferences
import com.zipextract.app.data.LocaleHelper
import com.zipextract.app.data.ThemeMode
import com.zipextract.app.license.AppUpdateDownloadResult
import com.zipextract.app.license.AppUpdateDownloader
import com.zipextract.app.license.AppUpdateInfo
import com.zipextract.app.license.LicenseGateStatus
import com.zipextract.app.license.LicenseRepository
import com.zipextract.app.license.LicenseScheduler
import com.zipextract.app.ui.FileBrowserScreen
import com.zipextract.app.ui.FileBrowserViewModel
import com.zipextract.app.ui.license.LicenseLockScreen
import com.zipextract.app.ui.theme.FileNestTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                    val licenseRepo = remember { LicenseRepository.get(context) }
                    val licenseState by licenseRepo.state.collectAsStateWithLifecycle()
                    val scope = rememberCoroutineScope()
                    var updateDownload by remember { mutableStateOf<AppUpdateInfo?>(null) }
                    var updateProgress by remember { mutableFloatStateOf(0f) }

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
                    // Open UI from local cache immediately; one background verify (no poll loop).
                    licenseRepo.applyLocalCache()
                    licenseRepo.silentCheck()
                    LicenseScheduler.schedule(context)
                }

                // Server pushes Block/Unblock over WebSocket while the app is open.
                DisposableEffect(licenseRepo) {
                    licenseRepo.startPushChannel()
                    onDispose { licenseRepo.stopPushChannel() }
                }

                LaunchedEffect(viewModel) {
                    viewModel.events.collect { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    when (licenseState.gate) {
                        LicenseGateStatus.Locked -> {
                            LicenseLockScreen(
                                state = licenseState,
                                repository = licenseRepo,
                                onUnlocked = { },
                            )
                        }
                        // Loading is unused on cold start (local cache opens immediately).
                        LicenseGateStatus.Loading,
                        LicenseGateStatus.Active,
                        LicenseGateStatus.Disabled -> {
                    FileBrowserScreen(
                        state = state,
                        onOpen = viewModel::openDirectory,
                        onOpenItem = viewModel::openItem,
                        onGoUp = viewModel::goUp,
                        onRefresh = viewModel::softRefresh,
                        onFullRescan = viewModel::fullRescan,
                        onToggleSelect = viewModel::toggleSelect,
                        onToggleSelectionMode = viewModel::toggleSelectionMode,
                        onSelectAll = viewModel::selectAll,
                        onClearSelection = viewModel::clearSelection,
                        onCopy = viewModel::copySelected,
                        onCut = viewModel::cutSelected,
                        onPaste = viewModel::paste,
                        onClearClipboard = viewModel::clearClipboard,
                        onDelete = viewModel::deleteSelected,
                        onCreateFolder = viewModel::createFolder,
                        onRename = viewModel::renameSelected,
                        onCreateZip = viewModel::createZip,
                        onOpenExtract = viewModel::openExtractDialogForItem,
                        onOpenCategory = viewModel::openCategory,
                        onBrowseAll = viewModel::browseAllFiles,
                        onOpenStorageVolume = viewModel::openStorageVolume,
                        onOpenExplorerVolume = viewModel::openExplorerVolume,
                        onNavigateTo = viewModel::navigateTo,
                        onGoHome = viewModel::goHome,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onClearSearch = viewModel::clearSearch,
                        onOpenFileAnywhere = viewModel::openFileFromAnywhere,
                        onMissingImages = viewModel::reportMissingImages,
                        onSetFileFilter = viewModel::setFileFilter,
                        onToggleSort = viewModel::toggleSort,
                        onRequestPermission = { requestStorageAccess() },
                        onCloseViewer = {
                            if (viewModel.closeViewer()) {
                                finish()
                            }
                        },
                        onDeleteViewerFile = {
                            if (viewModel.deleteViewerFile()) {
                                finish()
                            }
                        },
                        onViewerPageChanged = viewModel::updateViewerPage,
                        onCloseExtract = viewModel::closeExtractDialog,
                        onDeleteOriginalZipChange = viewModel::setDeleteOriginalZip,
                        onExtractPasswordChange = viewModel::setExtractPassword,
                        onExtractDestinationChange = viewModel::setExtractDestinationChoice,
                        onConfirmExtract = viewModel::confirmExtract,
                        onDismissExtractResult = viewModel::dismissExtractResult,
                        onOpenExtractResultFolder = viewModel::openExtractResultFolder,
                        onShareSelected = { viewModel.shareSelected(context) },
                        onShareSelectedWithPassword = { password ->
                            viewModel.shareSelectedWithPassword(context, password)
                        },
                        selectionUsesPdfPassword = viewModel::selectionUsesPdfPassword,
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
                        onSetAppSubFilter = viewModel::setAppSubFilter,
                        onCompressSelectedApps = viewModel::compressSelectedApps,
                        onUninstallSelectedApps = viewModel::uninstallSelectedApps,
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

                    val pendingUpdate = licenseState.pendingUpdate
                    if (pendingUpdate != null && updateDownload == null) {
                        AlertDialog(
                            onDismissRequest = { licenseRepo.dismissUpdate(pendingUpdate) },
                            title = {
                                Text(
                                    stringResource(
                                        R.string.app_update_title,
                                        pendingUpdate.version,
                                    ),
                                )
                            },
                            text = { Text(pendingUpdate.message) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        licenseRepo.dismissUpdate(pendingUpdate)
                                        updateProgress = 0f
                                        updateDownload = pendingUpdate
                                        scope.launch {
                                            val result = AppUpdateDownloader.downloadAndInstall(
                                                context = context,
                                                update = pendingUpdate,
                                                onProgress = { updateProgress = it },
                                            )
                                            updateDownload = null
                                            when (result) {
                                                AppUpdateDownloadResult.InstalledStarted -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.app_update_installing),
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                                AppUpdateDownloadResult.NeedInstallPermission -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.app_update_need_permission),
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                                is AppUpdateDownloadResult.Failed -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.app_update_open_failed,
                                                        ) + " (${result.message})",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.app_update_action))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { licenseRepo.dismissUpdate(pendingUpdate) },
                                ) {
                                    Text(stringResource(R.string.app_update_later))
                                }
                            },
                        )
                    }

                    val downloading = updateDownload
                    if (downloading != null) {
                        AlertDialog(
                            onDismissRequest = { /* block dismiss while downloading */ },
                            title = {
                                Text(
                                    stringResource(
                                        R.string.app_update_downloading_title,
                                        downloading.version,
                                    ),
                                )
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.app_update_downloading))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { updateProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(
                                            R.string.app_update_progress_pct,
                                            (updateProgress * 100f).roundToInt().coerceIn(0, 100),
                                        ),
                                    )
                                }
                            },
                            confirmButton = {},
                        )
                    }
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
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        val uri = extractIncomingUri(intent) ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
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

    companion object {
        private const val GITHUB_APK_BASE =
            "https://github.com/Alifathaya/ZipExtract/releases/download"

        fun resolveUpdateDownloadUrl(update: AppUpdateInfo): String? {
            val raw = update.url.trim()
            if (raw.startsWith("https://") || raw.startsWith("http://")) {
                return raw
            }
            val version = update.version.trim().removePrefix("v").removePrefix("V")
            if (version.isBlank()) return null
            if (raw.startsWith("/")) {
                val base = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')
                if (base.isNotBlank()) return base + raw
            }
            return "$GITHUB_APK_BASE/v$version/FileNest-$version.apk"
        }
    }
}
