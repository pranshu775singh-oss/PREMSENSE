package com.ojas.probe

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Executes scripted accesses and writes each one, as it is attempted, to the signed intended log.
 *
 * The log records the state the app was ACTUALLY in (screen, lock, process importance) and the
 * stratum PermSense is expected to assign for it. The rule here mirrors PermSense's own definition
 * (on screen / running a visible service / screen off or locked / otherwise background), so the
 * comparison is between two independent readings of the same moment.
 */
class Runner(private val context: Context) {

    private val log = IntendedLog(context)

    /** Runs every step of the built-in script that belongs to [trigger], in order. */
    fun runScript(trigger: Trigger): Int {
        val script = Script.load(context)
        return script.stepsFor(trigger).sumOf { run(it, trigger) }
    }

    /** Runs one step: [Step.times] attempts, [Step.spacingMs] apart. Returns how many were attempted. */
    fun run(step: Step, trigger: Trigger): Int {
        repeat(step.times) { i ->
            attempt(step, trigger)
            if (i < step.times - 1 && step.spacingMs > 0) Thread.sleep(step.spacingMs)
        }
        return step.times
    }

    private fun attempt(step: Step, trigger: Trigger) {

        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)

        val interactive = power.isInteractive
        val locked = keyguard.isKeyguardLocked
        val importance = currentImportance()

        // The log line is written with the state at the moment the access is made, not afterwards.
        val before = System.currentTimeMillis()
        val result = ProbeActions.perform(context, step.op)

        log.record(
            IntendedLog.Access(
                tsMs = before,
                versionCode = BuildConfig.VERSION_CODE,
                op = step.op.opName,
                trigger = trigger.name,
                step = step.id,
                interactive = interactive,
                keyguard = locked,
                importance = importance,
                expectedStratum = expectedStratum(interactive, locked, importance),
                outcome = result.outcome,
                detail = result.detail,
            )
        )
    }

    private fun currentImportance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
    }

    companion object {

        /**
         * What PermSense should call this moment. An app with a resumed activity is VISIBLE. One with
         * a foreground service and no activity is FOREGROUND_SERVICE. Otherwise it is SCREEN_OFF if the
         * screen is off or the phone is locked, and BACKGROUND if not.
         */
        fun expectedStratum(interactive: Boolean, keyguardLocked: Boolean, importance: Int): String =
            when {
                importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "VISIBLE"
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
                !interactive || keyguardLocked -> "SCREEN_OFF"
                else -> "BACKGROUND"
            }
    }
}
