package com.ojas.permsense.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ojas.permsense.engine.AuditEngine
import com.ojas.permsense.engine.RunMode
import com.ojas.permsense.i18n.DigestText

/*
 * None of these assume exact timing. Android may delay or batch scheduled work to save battery, so
 * nothing correct depends on a worker running at a particular moment: a late run simply reads
 * everything since the last one. Every worker returns success even if the check had trouble; the
 * trouble is recorded in the report, and the next run is the retry.
 */

/** Every 15 minutes: read what apps did, store it, apply the policy rules. Cheap on purpose. */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = AuditEngine.of(applicationContext)
        if (!engine.onboardingDone()) return Result.success()

        val report = engine.run(RunMode.POLL)
        Notifier.announceCritical(applicationContext, engine, report.findings)
        return Result.success()
    }
}

/** Once a day, when the battery is fine: the full check, including the statistical layers. */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = AuditEngine.of(applicationContext)
        if (!engine.onboardingDone()) return Result.success()

        val report = engine.run(RunMode.FULL)
        Notifier.announceCritical(applicationContext, engine, report.findings)
        return Result.success()
    }
}

/** Once a week: a short plain-language summary, within the alert budget. Nothing to say, nothing sent. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = AuditEngine.of(applicationContext)
        if (!engine.onboardingDone() || !engine.digestEnabled()) return Result.success()

        val report = engine.run(RunMode.FULL)
        val digest = engine.digestOf(report.findings)

        DigestText.notification(digest, Notifier.languageOf(engine))?.let {
            Notifier.postDigest(applicationContext, it)
        }
        return Result.success()
    }
}
