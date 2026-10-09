package com.ojas.permsense

import android.app.Application
import com.ojas.permsense.engine.AuditEngine
import com.ojas.permsense.work.Notifier
import com.ojas.permsense.work.Schedule
import rikka.shizuku.Shizuku

class PermSenseApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Notifier.createChannels(this)

        val engine = AuditEngine.of(this)

        // Only once the person has been through setup: until then nothing runs in the background.
        if (engine.onboardingDone()) {
            Schedule.ensureScheduled(this, engine.digestEnabled())
        }

        // Shizuku does not survive a restart of the phone. When it is started again it sends this
        // app its connection, which also starts this process if it was not running. That is the
        // moment to pick the live collector back up, without waiting for the next scheduled check.
        try {
            Shizuku.addBinderReceivedListenerSticky {
                if (engine.onboardingDone()) Schedule.runSoon(this)
            }
        } catch (_: Throwable) {
            // Shizuku's client library is only a convenience; the app works without it.
        }
    }
}
