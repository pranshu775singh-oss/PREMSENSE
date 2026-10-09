package com.ojas.probe

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle

/**
 * The scheduled job: no screen, no service, no activity. This is where a background harvest runs,
 * and the stratum PermSense should report for it is BACKGROUND, or SCREEN_OFF on a locked phone.
 *
 * From adb, run it at once with: `adb shell cmd jobscheduler run -f com.ojas.quickcash 1001`
 */
class BackgroundJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {

        val context = applicationContext
        val extras = params.extras

        Thread {
            try {
                val runner = Runner(context)
                val op = extras.getString(EXTRA_OP)
                if (op != null) {
                    val step = Step(
                        id = "adhoc-$op",
                        op = ProbeOp.valueOf(op),
                        runIn = setOf(Trigger.JOB),
                        times = extras.getInt(EXTRA_TIMES, 1),
                        spacingMs = extras.getLong(EXTRA_SPACING_MS, 0L),
                    )
                    runner.run(step, Trigger.JOB)
                } else {
                    runner.runScript(Trigger.JOB)
                }
            } finally {
                jobFinished(params, false)
            }
        }.start()

        return true     // work continues on the thread above
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {

        const val JOB_ID_PERIODIC = 1001
        const val JOB_ID_ONE_SHOT = 1002

        const val EXTRA_OP = "op"
        const val EXTRA_TIMES = "times"
        const val EXTRA_SPACING_MS = "spacingMs"

        private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L

        /** The recurring job. Only the v2 build arranges it, which is exactly what makes v2 different. */
        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID_PERIODIC) != null) return

            scheduler.schedule(
                JobInfo.Builder(JOB_ID_PERIODIC, component(context))
                    .setPeriodic(FIFTEEN_MINUTES_MS)
                    .setPersisted(false)
                    .build()
            )
        }

        /** A single run with its own steps, for the accuracy tests. */
        fun scheduleOneShot(context: Context, op: String, times: Int, spacingMs: Long) {
            val extras = PersistableBundle().apply {
                putString(EXTRA_OP, op)
                putInt(EXTRA_TIMES, times)
                putLong(EXTRA_SPACING_MS, spacingMs)
            }
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID_ONE_SHOT, component(context))
                    .setExtras(extras)
                    .setMinimumLatency(0)
                    .setOverrideDeadline(1_000)
                    .build()
            )
        }

        private fun component(context: Context) = ComponentName(context, BackgroundJobService::class.java)
    }
}
