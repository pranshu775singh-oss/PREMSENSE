package com.ojas.permsense.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** What runs in the background, and when. All of it on this phone; none of it needs a network. */
object Schedule {

    private const val POLL = "permsense-poll"
    private const val NIGHTLY = "permsense-nightly"
    private const val DIGEST = "permsense-digest"
    private const val NOW = "permsense-now"

    /** Safe to call on every launch: existing schedules are kept, not restarted. */
    fun ensureScheduled(context: Context, digestEnabled: Boolean) {

        val work = WorkManager.getInstance(context)

        // 15 minutes is the least WorkManager allows. Polling faster would only cost battery: a
        // snapshot can say an access happened since the last look, never how many.
        work.enqueueUniquePeriodicWork(
            POLL,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build(),
        )

        work.enqueueUniquePeriodicWork(
            NIGHTLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NightlyWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build(),
        )

        setDigest(context, digestEnabled)
    }

    fun setDigest(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            work.enqueueUniquePeriodicWork(
                DIGEST,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DigestWorker>(7, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .build(),
            )
        } else {
            work.cancelUniqueWork(DIGEST)
        }
    }

    /** One quick check as soon as possible, for example when Shizuku has just come back. */
    fun runSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PollWorker>().build(),
        )
    }
}
