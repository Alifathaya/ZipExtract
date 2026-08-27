package com.zipextract.app.license

import android.content.Context
import com.zipextract.app.MainActivity
import com.zipextract.app.data.FileActions
import com.zipextract.app.data.FileActions.InstallApkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class AppUpdateDownloadResult {
    data object InstalledStarted : AppUpdateDownloadResult()
    data object NeedInstallPermission : AppUpdateDownloadResult()
    data class Failed(val message: String) : AppUpdateDownloadResult()
}

/**
 * Downloads the APK (with progress) into app cache, then opens the system installer
 * automatically — user does not need to tap the Downloads notification.
 */
object AppUpdateDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        val url = MainActivity.resolveUpdateDownloadUrl(update)
            ?: return@withContext AppUpdateDownloadResult.Failed("no_url")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "FileNest-${update.version}.apk")
        if (dest.exists()) dest.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive,*/*")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppUpdateDownloadResult.Failed("http_${response.code}")
                }
                val body = response.body ?: return@withContext AppUpdateDownloadResult.Failed("empty")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastEmit = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            if (total > 0L) {
                                val pct = ((readTotal * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastEmit) {
                                    lastEmit = pct
                                    withContext(Dispatchers.Main.immediate) {
                                        onProgress(pct / 100f)
                                    }
                                }
                            } else if (readTotal % (512L * 1024L) < buffer.size) {
                                val pulse = (0.2f + (readTotal % 5_000_000L) / 10_000_000f)
                                    .coerceAtMost(0.95f)
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(pulse)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) { onProgress(1f) }
        } catch (e: Exception) {
            dest.delete()
            return@withContext AppUpdateDownloadResult.Failed(e.message ?: "download_failed")
        }

        if (!dest.exists() || dest.length() <= 0L) {
            return@withContext AppUpdateDownloadResult.Failed("empty_file")
        }

        when (val result = FileActions.installApk(context.applicationContext, dest)) {
            is InstallApkResult.Started -> AppUpdateDownloadResult.InstalledStarted
            is InstallApkResult.NeedInstallPermission -> AppUpdateDownloadResult.NeedInstallPermission
            is InstallApkResult.Failed -> AppUpdateDownloadResult.Failed(result.reason)
        }
    }
}
