package com.friday.assistant.intelligence.brief

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BriefScheduler {
    private const val TAG = "BriefScheduler"
    private const val UNIQUE_WORK_NAME = "FridayDailyBriefCrawlWork"

    fun schedulePeriodicCrawl(context: Context) {
        try {
            Log.i(TAG, "Initializing Daily Brief WorkManager scheduler")
            
            // Set constraints: Internet required, device must not be low battery
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            // Run every 2 hours
            val crawlRequest = PeriodicWorkRequestBuilder<BriefWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES) // wait 5 minutes after boot/app start before first crawl
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing schedule if already registered
                crawlRequest
            )
            Log.d(TAG, "Daily Brief periodic crawl successfully enqueued (2-hour interval)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule Daily Brief background work", e)
        }
    }

    fun triggerOneTimeCrawl(context: Context) {
        try {
            Log.i(TAG, "Triggering immediate one-time crawl request")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<BriefWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
            Log.d(TAG, "One-time crawl request enqueued")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue one-time crawl request", e)
        }
    }
}
