package com.zipextract.app.license

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
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
    private const val UNIQUE = "filenest_license_daily"

    /**
     * Rare backup only. Live Block/Unblock uses WebSocket push from the server —
     * do not poll every few seconds.
     */
    fun schedule(context: Context) {
        if (!LicenseApi().isConfigured()) return

        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork("filenest_license_periodic")

        val delayMs = millisUntilNextMidnight()
        val request = PeriodicWorkRequestBuilder<LicenseCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        wm.enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun millisUntilNextMidnight(): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }
}
