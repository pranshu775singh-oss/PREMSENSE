package com.ojas.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Runs the SERVICE steps from a foreground service the person can see a notification for. The
 * stratum PermSense should report is FOREGROUND_SERVICE (VISIBLE if the activity is still in front).
 */
class ProbeForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "QuickCash test", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("QuickCash test app")
            .setContentText("Running scripted accesses for PermSense")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val op = intent?.getStringExtra("op")
        val times = intent?.getIntExtra("times", 1) ?: 1
        val spacing = intent?.getLongExtra("spacingMs", 0L) ?: 0L

        Thread {
            try {
                val runner = Runner(applicationContext)
                if (op != null) {
                    runner.run(
                        Step("adhoc-$op", ProbeOp.valueOf(op), setOf(Trigger.SERVICE), times, spacing),
                        Trigger.SERVICE,
                    )
                } else {
                    runner.runScript(Trigger.SERVICE)
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "probe"
        const val NOTIFICATION_ID = 77
    }
}
