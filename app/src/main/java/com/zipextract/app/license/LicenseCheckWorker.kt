package com.zipextract.app.license

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class LicenseCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            LicenseRepository.get(applicationContext).silentCheck()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object LicenseScheduler {
    private const val UNIQUE = "filenest_license_periodic"

    /** How often the open app asks the server for block/unblock updates. */
    const val FOREGROUND_POLL_MS: Long = 20_000L

    /**
     * Background safety net while the app is closed.
     * WorkManager minimum period is 15 minutes.
     */
    fun schedule(context: Context) {
        if (!LicenseApi().isConfigured()) return

        val request = PeriodicWorkRequestBuilder<LicenseCheckWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
