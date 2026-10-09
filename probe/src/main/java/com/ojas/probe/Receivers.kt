package com.ojas.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test hook for adb. Short ad-hoc steps, run from a broadcast (so: no screen, no service).
 *
 *   adb shell am broadcast -n com.ojas.quickcash/com.ojas.probe.ProbeReceiver \
 *       -a com.ojas.quickcash.RUN --es op CONTACTS --ei times 3 --el spacingMs 2000
 *
 * A broadcast only has a few seconds, so keep times x spacing small. Longer runs use the job:
 * add `--es mode job` to schedule a one-shot job instead, then `cmd jobscheduler run -f`.
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val app = context.applicationContext
        val op = intent.getStringExtra("op")

        if (intent.getStringExtra("mode") == "job" && op != null) {
            BackgroundJobService.scheduleOneShot(
                app, op, intent.getIntExtra("times", 1), intent.getLongExtra("spacingMs", 0L),
            )
            return
        }

        val pending = goAsync()

        Thread {
            try {
                val runner = Runner(app)
                if (op != null) {
                    runner.run(
                        Step(
                            id = "adhoc-$op",
                            op = ProbeOp.valueOf(op),
                            runIn = setOf(Trigger.BROADCAST),
                            times = intent.getIntExtra("times", 1),
                            spacingMs = intent.getLongExtra("spacingMs", 0L),
                        ),
                        Trigger.BROADCAST,
                    )
                } else {
                    runner.runScript(Trigger.BROADCAST)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * After an update the app sets up its own background work, the way a real update would. In v1 there
 * is none; in v2 this is where the recurring contacts job gets scheduled, with no screen and no
 * prompt. That difference between the two builds is the whole demonstration.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.PROBE_GENERATION >= 2) {
            BackgroundJobService.schedulePeriodic(context.applicationContext)
        }
    }
}
