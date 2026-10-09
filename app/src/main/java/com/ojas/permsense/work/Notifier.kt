package com.ojas.permsense.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ojas.permsense.MainActivity
import com.ojas.permsense.R
import com.ojas.permsense.engine.AuditEngine
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.i18n.DigestText
import com.ojas.permsense.i18n.Lang
import java.util.Locale

/**
 * Every notification PermSense posts.
 *
 * The wording is neutral on purpose and comes from :core, where a test pins it: it names no app and
 * no category of risk, because a notification may light up while someone else is holding the phone.
 * They are also hidden from the lock screen.
 */
object Notifier {

    private const val CHANNEL_ALERTS = "alerts"
    private const val CHANNEL_DIGEST = "digest"

    private const val ID_ALERT = 4001
    private const val ID_DIGEST = 4002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_alerts_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, context.getString(R.string.channel_digest), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_digest_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
        )
    }

    fun languageOf(engine: AuditEngine): Lang =
        Lang.fromTag(engine.languageTag() ?: Locale.getDefault().language)

    /** True when a notification can be shown: allowed in settings and, on Android 13+, permitted. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Announces serious findings not yet announced, once each. */
    fun announceCritical(context: Context, engine: AuditEngine, findings: List<Finding>) {
        val fresh = engine.takeUnannouncedCritical(findings)
        if (fresh.isEmpty()) return
        post(context, CHANNEL_ALERTS, ID_ALERT, DigestText.alert(fresh.size, languageOf(engine)))
    }

    fun postDigest(context: Context, notification: DigestText.Notification) =
        post(context, CHANNEL_DIGEST, ID_DIGEST, notification)

    private fun post(context: Context, channel: String, id: Int, text: DigestText.Notification) {

        if (!canPost(context)) return

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_permsense)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post. Nothing to do.
        }
    }
}
