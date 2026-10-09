package com.ojas.permsense.judgement

import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.pipeline.IngestionPipeline
import com.ojas.permsense.processing.UsageContext
import com.ojas.permsense.processing.UsageEventLite
import com.ojas.permsense.processing.UsageKind
import com.ojas.permsense.processing.buildUsageContext

/** Shared synthetic-device builders. Real pipeline in, real ledger rows out: no hand-made rows. */
object Fx {

    const val BASE = 1_700_000_000_000L
    const val MIN = 60_000L
    const val HOUR = 60L * MIN
    const val DAY = 24L * HOUR

    const val PKG = "com.ojas.quickcash"
    const val UID = 10200

    private const val P = "android.permission."

    fun obs(
        id: String,
        ts: Long,
        op: String,
        pkg: String = PKG,
        uid: Int = UID,
        tier: Tier = Tier.T2,
    ) = RawObservation(
        tier = tier,
        sourceEventId = id,
        uid = uid,
        packageName = pkg,
        op = op,
        accessKind = "noted",
        timestampMs = ts,
        accessResult = "OBSERVED",
    )

    fun app(
        version: Long = 1L,
        lastUpdate: Long = BASE,
        pkg: String = PKG,
        requested: List<String> = listOf(P + "READ_CONTACTS", P + "CAMERA"),
        granted: List<String> = requested,
        tags: Set<String> = setOf(TAG_LENDING),
        accessibility: Boolean = false,
        admin: Boolean = false,
        listener: Boolean = false,
        system: Boolean = false,
    ) = AppInventory(
        packageName = pkg,
        versionCode = version,
        requestedPermissions = requested,
        grantedPermissions = granted,
        policyTags = tags,
        isAccessibilityEnabled = accessibility,
        isDeviceAdmin = admin,
        isNotificationListener = listener,
        isSystemApp = system,
        lastUpdateTimeMs = lastUpdate,
    )

    fun usage(vararg events: UsageEventLite, from: Long = BASE, to: Long = BASE + 3 * DAY): UsageContext =
        buildUsageContext(events.toList(), from, to)

    fun screenOn(ts: Long) = UsageEventLite(UsageKind.SCREEN_ON, null, ts)
    fun screenOff(ts: Long) = UsageEventLite(UsageKind.SCREEN_OFF, null, ts)
    fun resumed(ts: Long, pkg: String = PKG) = UsageEventLite(UsageKind.RESUMED, pkg, ts)
    fun paused(ts: Long, pkg: String = PKG) = UsageEventLite(UsageKind.PAUSED, pkg, ts)

    /** Screen on for the whole window unless a test adds its own screen events. */
    fun screenAlwaysOn(): UsageEventLite = screenOn(BASE + 1)

    class Device(
        val ledger: LedgerWriter = LedgerWriter(),
    ) {
        private val pipeline = IngestionPipeline(ledger)

        fun ingest(
            observations: List<RawObservation>,
            context: UsageContext?,
            inventory: List<AppInventory>,
            nowMs: Long = BASE + 10 * DAY,
        ): IngestionPipeline.Result {
            val result = pipeline.ingest(observations, context, inventory, nowMs)
            pipeline.flush(nowMs)
            return result
        }

        val rows: List<LedgerRecord> get() = ledger.all()
    }

    val CONTACTS = Ops.CONTACTS
    val CAMERA = Ops.CAMERA
}
