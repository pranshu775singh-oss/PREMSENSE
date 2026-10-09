package com.ojas.permsense.engine

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ojas.permsense.baseline.BaselineReport
import com.ojas.permsense.capability.SourceResult
import com.ojas.permsense.export.EvidenceExport
import com.ojas.permsense.export.EvidenceReport
import com.ojas.permsense.export.ExportInput
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.FindingRanking
import com.ojas.permsense.findings.TriageRanker
import com.ojas.permsense.i18n.Digest
import com.ojas.permsense.i18n.DigestBuilder
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.judgement.AuditInput
import com.ojas.permsense.judgement.Auditor
import com.ojas.permsense.judgement.LendingRegistry
import com.ojas.permsense.judgement.PolicyEngine
import com.ojas.permsense.judgement.PolicyPack
import com.ojas.permsense.judgement.PolicyPackLoader
import com.ojas.permsense.ledger.AndroidKeystoreSigner
import com.ojas.permsense.ledger.AndroidLedgerKeyProvider
import com.ojas.permsense.ledger.ChainStart
import com.ojas.permsense.ledger.ChainVerification
import com.ojas.permsense.ledger.EncryptedLedgerStorage
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerStorageException
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.ledger.createGapRecord
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.pipeline.CollectorObservation
import com.ojas.permsense.pipeline.CollectorState
import com.ojas.permsense.pipeline.GapTracker
import com.ojas.permsense.pipeline.IngestionPipeline
import com.ojas.permsense.remediation.RemediationExecutor
import com.ojas.permsense.remediation.RemediationOutcome
import com.ojas.permsense.remediation.RemediationRequest
import com.ojas.permsense.remediation.UndoEntry
import com.ojas.permsense.sources.DeviceSensing
import com.ojas.permsense.sources.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

enum class RunMode {
    /**
     * Cheap and frequent: read what apps did, store it, and apply the policy rules so a serious
     * finding surfaces within minutes. No statistics.
     */
    POLL,

    /** Everything: the OS history backfill, retention, signing, and all three judgement layers. */
    FULL,
}

/** Everything the UI needs to show one run. */
data class AuditReport(
    val runAtMs: Long,
    val mode: RunMode,
    val findings: List<Finding>,
    val inventory: List<AppInventory>,
    /** Sources that actually delivered data this run. */
    val tiers: Set<Tier>,
    /** Plain notes about what could not be seen. Never empty when a source failed. */
    val notes: List<String>,
    val ledgerRows: Int,
    val chain: ChainVerification?,
    val chainStart: ChainStart?,
    /** Set when the ledger could not be opened. Findings are then inventory-only. */
    val ledgerError: String?,
    val baseline: BaselineReport?,
    val liveCollectorRunning: Boolean,
    /** True when the app read AppOps itself (the one-time grant), with no Shizuku. */
    val readDirectly: Boolean,
    val undoable: List<UndoEntry>,
    val rulesAwaitingReview: List<String>,
)

class ExportFiles(val evidenceUri: Uri, val reportUri: Uri, val digest: String)

/**
 * One full pass: sense -> normalise -> contextualise -> ledger -> judge.
 *
 * Android glue only. Every decision lives in :core, which is unit tested on the JVM. A source that
 * cannot deliver is reported in [AuditReport.notes]; "no data" is never presented as "nothing
 * happened".
 *
 * There is exactly one of these per process ([of]). The screen and the background workers share
 * it; two writers on one ledger file would each hold their own copy of the chain and corrupt it.
 */
class AuditEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val sensing = DeviceSensing(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val runLock = Mutex()
    private val keys = AndroidLedgerKeyProvider(appContext)

    private var ledger: LedgerWriter? = null
    private var ledgerFailure: String? = null
    private var signer: AndroidKeystoreSigner? = null

    @Volatile
    private var lastReport: AuditReport? = null

    private val pack: PolicyPack by lazy { PolicyPackLoader.loadBundled() }

    private var triage: TriageRanker = TriageRanker.fromJson(prefs.getString(KEY_TRIAGE, null))

    fun sensing(): DeviceSensing = sensing

    fun labelOf(packageName: String): String = sensing.labelOf(packageName)

    /** The newest full check. Frequent quick checks never replace it. */
    fun lastReport(): AuditReport? = lastReport

    fun uidOf(packageName: String): Int? = sensing.uidOf(packageName)

    // ------------------------------------------------------------------ small settings

    fun userTaggedLending(): Set<String> =
        prefs.getStringSet(KEY_LENDING, emptySet()).orEmpty().toSet()

    fun isLendingTagged(packageName: String): Boolean =
        packageName in LendingRegistry.packages(userTaggedLending(), pack)

    fun setLendingTag(packageName: String, lending: Boolean) {
        val next = userTaggedLending().let { if (lending) it + packageName else it - packageName }
        prefs.edit().putStringSet(KEY_LENDING, next).apply()
    }

    fun appLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)
    fun setAppLock(enabled: Boolean) = prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()

    fun onboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDED, false)
    fun setOnboardingDone(done: Boolean) = prefs.edit().putBoolean(KEY_ONBOARDED, done).apply()

    fun languageTag(): String? = prefs.getString(KEY_LANG, null)
    fun setLanguageTag(tag: String?) = prefs.edit().putString(KEY_LANG, tag).apply()

    fun digestEnabled(): Boolean = prefs.getBoolean(KEY_DIGEST, true)
    fun setDigestEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DIGEST, enabled).apply()

    /**
     * T1-only mode: never request or use the broad DUMP permission. The OS history backfill (T1+)
     * is off, and the live collector and per-app checks still work.
     */
    fun t1OnlyMode(): Boolean = prefs.getBoolean(KEY_T1_ONLY, false)
    fun setT1OnlyMode(enabled: Boolean) = prefs.edit().putBoolean(KEY_T1_ONLY, enabled).apply()

    fun liveCollectorEnabled(): Boolean = prefs.getBoolean(KEY_T2_ENABLED, true)

    /** Turning the collector off also stops it and lets go of the privileged process. */
    suspend fun setLiveCollectorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_T2_ENABLED, enabled).apply()
        if (!enabled) {
            runLock.withLock {
                withContext(Dispatchers.IO) {
                    sensing.stopT2()
                    sensing.releaseShizukuService()
                }
            }
        }
    }

    /** When the last check finished, for the "last checked" line. 0 if never. */
    fun lastRunMs(): Long = prefs.getLong(KEY_LAST_RUN, 0L)

    // ------------------------------------------------------------------ feedback and snoozing

    /** The user reacted to a finding. Teaches the ranker; a dismissal also snoozes a non-critical one. */
    fun feedback(finding: Finding, useful: Boolean) {
        triage.learn(finding, useful)
        prefs.edit().putString(KEY_TRIAGE, triage.toJson()).apply()
        if (!useful && finding.severity < 3) snooze(finding.dedupKey, SNOOZE_MS)
    }

    private fun snooze(key: String, forMs: Long) {
        val now = System.currentTimeMillis()
        val kept = prefs.getStringSet(KEY_SNOOZED, emptySet()).orEmpty()
            .filter { (it.substringAfterLast('|').toLongOrNull() ?: 0L) > now }
            .toMutableSet()
        kept += "$key|${now + forMs}"
        prefs.edit().putStringSet(KEY_SNOOZED, kept).apply()
    }

    private fun snoozedKeys(now: Long): Set<String> =
        prefs.getStringSet(KEY_SNOOZED, emptySet()).orEmpty()
            .filter { (it.substringAfterLast('|').toLongOrNull() ?: 0L) > now }
            .map { it.substringBeforeLast('|') }
            .toSet()

    // ------------------------------------------------------------------ announcements

    /**
     * Critical findings that have not yet been announced with a notification. They are marked
     * announced here, so each is announced once however many times it is seen. A finding that
     * disappears and comes back later is announced again after [ANNOUNCE_AGAIN_MS].
     */
    fun takeUnannouncedCritical(findings: List<Finding>, now: Long = System.currentTimeMillis()): List<Finding> {

        val announced = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty()
            .mapNotNull { line ->
                val at = line.substringAfterLast('|').toLongOrNull() ?: return@mapNotNull null
                if (now - at > ANNOUNCE_AGAIN_MS) null else line.substringBeforeLast('|') to at
            }
            .toMap()

        val fresh = findings.filter { it.severity >= 3 && it.dedupKey !in announced }

        if (fresh.isNotEmpty() || announced.size != (prefs.getStringSet(KEY_ANNOUNCED, emptySet())?.size ?: 0)) {
            val next = announced.toMutableMap()
            fresh.forEach { next[it.dedupKey] = now }
            prefs.edit().putStringSet(KEY_ANNOUNCED, next.map { "${it.key}|${it.value}" }.toSet()).apply()
        }

        return fresh
    }

    /** The weekly digest of a set of findings, within the alert budget. */
    fun digestOf(findings: List<Finding>): Digest = DigestBuilder.build(findings)

    // ------------------------------------------------------------------ running

    suspend fun run(mode: RunMode = RunMode.FULL, nowMs: Long = System.currentTimeMillis()): AuditReport =
        runLock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    runLocked(mode, nowMs).also {
                        if (mode == RunMode.FULL) lastReport = it
                        prefs.edit().putLong(KEY_LAST_RUN, nowMs).apply()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A run must always produce a report the screen can show.
                    emptyReport(mode, nowMs, "The check could not finish: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

    private suspend fun runLocked(mode: RunMode, now: Long): AuditReport {

        val offset = TimeZone.getDefault().getOffset(now).toLong()
        val notes = mutableListOf<String>()
        val tiers = linkedSetOf<Tier>()

        // ---- op-level sensing: what apps did
        val observations = mutableListOf<RawObservation>()
        val liveIds = mutableListOf<String>()
        var opLevel = false
        var readDirectly = false
        var collector: CollectorObservation = CollectorObservation.Unreachable(now)
        var historyCoverageStart: Long? = null

        val viaShizuku = sensing.isShizukuPermissionGranted()

        when (val t1 = sensing.scanT1()) {
            is SourceResult.Ok -> {
                tiers += Tier.T1
                opLevel = true
                observations += t1.data.observations
                readDirectly = !viaShizuku
                if (t1.data.skipped.isNotEmpty()) {
                    notes += "Android would not answer for ${t1.data.skipped.size} kind(s) of activity, so those were not checked: " +
                            t1.data.skipped.take(3).joinToString("; ")
                }
            }
            is SourceResult.NeedsSetup -> notes += "App activity (T1) needs setup: ${t1.missing}."
            is SourceResult.Broken -> notes += "App activity (T1) failed: ${t1.reason}"
        }

        // The OS history is heavier, so it runs in full mode and otherwise at most every few hours.
        // In T1-only mode it never runs, so the broad DUMP permission is never used.
        val historyDue = !t1OnlyMode() && (mode == RunMode.FULL || now - prefs.getLong(KEY_LAST_HISTORY, 0L) > HISTORY_EVERY_MS)
        if (historyDue) {
            when (val h = sensing.scanT1Plus()) {
                is SourceResult.Ok -> {
                    tiers += Tier.T1_PLUS
                    opLevel = true
                    observations += h.data.observations
                    historyCoverageStart = h.data.capturedAtMs - h.data.coverageMs
                    prefs.edit().putLong(KEY_LAST_HISTORY, now).apply()
                }
                is SourceResult.NeedsSetup -> notes += "The OS history backfill is off (${h.missing} not granted)."
                is SourceResult.Broken -> notes += "The OS history backfill failed: ${h.reason}"
            }
        }

        if (!liveCollectorEnabled()) {
            notes += "The live collector is switched off in settings."
        } else if (viaShizuku) {
            when (val s = sensing.startT2()) {
                is SourceResult.Ok -> {
                    collector = CollectorObservation.Running(s.data.startedAtMs, s.data.droppedEvents, now)
                    tiers += Tier.T2
                    drainLiveCollector(observations, liveIds, notes)
                }
                is SourceResult.NeedsSetup -> notes += "The live collector needs setup: ${s.missing}."
                is SourceResult.Broken -> notes += "The live collector did not start: ${s.reason}"
            }
        } else {
            notes += if (readDirectly) {
                "Shizuku is off, so the live collector is paused. App activity is still checked on a schedule."
            } else {
                "Shizuku is not running, so only installed apps and their permissions are checked. " +
                        "Start Shizuku to see what apps actually do."
            }
        }

        // ---- T0: installed apps, and what was on screen when the observations happened
        val oldest = observations.minOfOrNull { it.timestampMs }
        val lookback = if (oldest == null) MIN_LOOKBACK_MS else (now - oldest + CONTEXT_MARGIN_MS).coerceIn(MIN_LOOKBACK_MS, MAX_LOOKBACK_MS)

        val snapshot = sensing.scan(
            lookbackMs = lookback,
            lendingPackages = LendingRegistry.packages(userTaggedLending(), pack),
        )

        val inventory: List<AppInventory> = when (val r = snapshot.inventory) {
            is SourceResult.Ok -> { tiers += Tier.T0; r.data }
            is SourceResult.NeedsSetup -> { notes += "Installed apps could not be read: ${r.missing} is needed."; emptyList() }
            is SourceResult.Broken -> { notes += "Installed apps could not be read: ${r.reason}"; emptyList() }
        }
        if (inventory.isNotEmpty()) cachedInventory = inventory.associateBy { it.packageName }

        val usage = when (val r = snapshot.usage) {
            is SourceResult.Ok -> r.data
            is SourceResult.NeedsSetup -> {
                notes += "Usage Access is off, so PermSense cannot always tell whether an app was on screen. " +
                        "Accesses are still recorded."
                null
            }
            is SourceResult.Broken -> { notes += "Screen and app-visibility history is unavailable: ${r.reason}"; null }
        }

        // ---- ledger
        val writer = openLedger()
        var chain: ChainVerification? = null
        var chainStart: ChainStart? = null
        var previousCollector: CollectorState? = null

        // What the statistics may rely on: since when has real counting been possible?
        recordSensingStart(opLevel, collector, historyCoverageStart, now)

        if (writer != null) {

            previousCollector = collectorState()
            recordCollectorGaps(writer, previousCollector, collector)

            val known = loadKnownVersions()

            val ingest = IngestionPipeline(writer).ingest(
                observations = observations,
                context = usage,
                inventory = inventory,
                nowMs = now,
                knownVersions = known,
                zoneOffsetMs = offset,
            )
            writer.flush(now)

            // Tell the collector to forget only what is safely stored. Anything still being
            // grouped stays in its buffer and is sent again; the ledger ignores the repeat.
            val safe = writer.knownAmong(liveIds)
            if (safe.isNotEmpty()) sensing.acknowledgeT2(safe)

            saveKnownVersions(known, inventory)

            pruneIfDue(writer, offset, now, notes)
            checkpointIfDue(writer, now, notes)

            chain = writer.verify()
            chainStart = writer.chainStart()
            if (chain is ChainVerification.Broken) {
                notes += "The ledger failed its integrity check at row ${chain.atIndex}: ${chain.reason}."
            }
            if (ingest.observationsDropped > 0) {
                notes += "${ingest.observationsDropped} record(s) older than ${Retention.RETENTION_DAYS} days were ignored."
            }
        }

        val rows = writer?.all().orEmpty()

        // ---- judgement
        val findings: List<Finding>
        var baseline: BaselineReport? = null

        if (mode == RunMode.FULL) {

            // A collector outage still in progress is not stored yet, but the statistics must know of it.
            val outage = GapTracker.openGap(previousCollector, collector)
            val analysed =
                if (outage != null) rows + createGapRecord("open-gap", outage.startMs, outage.endMs, outage.reason, "T2")
                else rows

            val result = Auditor(PolicyEngine.fromPack(pack)).analyse(
                AuditInput(
                    ledgerRows = analysed,
                    inventory = inventory,
                    nowMs = now,
                    observingSinceMs = longPref(KEY_OBSERVING_SINCE),
                    zoneOffsetMs = offset,
                    countingSinceMs = longPref(KEY_COUNTING_SINCE),
                    indicatorSinceMs = longPref(KEY_INDICATOR_SINCE),
                    versionHistory = versionHistory(),
                )
            )
            baseline = result.baseline
            findings = presentable(result.findings, now)

        } else {
            findings = presentable(
                Auditor(PolicyEngine.fromPack(pack)).audit(rows, inventory, now, longPref(KEY_OBSERVING_SINCE)),
                now,
            )
        }

        return AuditReport(
            runAtMs = now,
            mode = mode,
            findings = findings,
            inventory = inventory,
            tiers = tiers,
            notes = notes,
            ledgerRows = rows.size,
            chain = chain,
            chainStart = chainStart,
            ledgerError = if (writer == null) ledgerFailure else null,
            baseline = baseline,
            liveCollectorRunning = collector is CollectorObservation.Running,
            readDirectly = readDirectly,
            undoable = executor()?.undoLog().orEmpty(),
            rulesAwaitingReview = pack.rulesAwaitingReview(),
        )
    }

    private fun emptyReport(mode: RunMode, now: Long, note: String) = AuditReport(
        runAtMs = now, mode = mode, findings = emptyList(), inventory = emptyList(), tiers = emptySet(),
        notes = listOf(note), ledgerRows = 0, chain = null, chainStart = null, ledgerError = null,
        baseline = null, liveCollectorRunning = false, readDirectly = false, undoable = emptyList(),
        rulesAwaitingReview = emptyList(),
    )

    /** Snoozed findings out, then the ranker orders the rest. Critical findings are never snoozed. */
    private fun presentable(findings: List<Finding>, now: Long): List<Finding> {
        val snoozed = snoozedKeys(now)
        val visible = findings.filter { it.severity >= 3 || it.dedupKey !in snoozed }
        return triage.order(FindingRanking.rank(visible))
    }

    /**
     * Reads everything the live collector has buffered, batch by batch, without removing any of it.
     * Removal happens only after the events are in the ledger.
     */
    private suspend fun drainLiveCollector(
        into: MutableList<RawObservation>,
        ids: MutableList<String>,
        notes: MutableList<String>,
    ) {
        var offset = 0
        while (offset < MAX_LIVE_EVENTS_PER_RUN) {
            when (val b = sensing.getT2Batch(Protocol.T2_BATCH_SIZE, offset)) {
                is SourceResult.Ok -> {
                    into += b.data
                    ids += b.data.map { it.sourceEventId }
                    offset += b.data.size
                    if (b.data.size < Protocol.T2_BATCH_SIZE) return
                }
                is SourceResult.NeedsSetup -> return
                is SourceResult.Broken -> { notes += "The live collector read failed: ${b.reason}"; return }
            }
        }
        notes += "The live collector has more events waiting than one check reads; the rest are read next time."
    }

    // ------------------------------------------------------------------ counters and gaps

    private fun longPref(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun recordSensingStart(
        opLevel: Boolean,
        collector: CollectorObservation,
        historyCoverageStart: Long?,
        now: Long,
    ) {
        val edit = prefs.edit()

        if (opLevel && !prefs.contains(KEY_OBSERVING_SINCE)) edit.putLong(KEY_OBSERVING_SINCE, now)

        if (collector is CollectorObservation.Running && !prefs.contains(KEY_COUNTING_SINCE)) {
            edit.putLong(KEY_COUNTING_SINCE, collector.startedAtMs)
        }

        // Camera, microphone and location can be judged from the OS history alone.
        val counting = longPref(KEY_COUNTING_SINCE) ?: (collector as? CollectorObservation.Running)?.startedAtMs
        val indicatorCandidate = listOfNotNull(historyCoverageStart, counting).minOrNull()
        if (indicatorCandidate != null) {
            val existing = longPref(KEY_INDICATOR_SINCE)
            if (existing == null || indicatorCandidate < existing) edit.putLong(KEY_INDICATOR_SINCE, indicatorCandidate)
        }

        edit.apply()
    }

    private fun collectorState(): CollectorState? =
        if (prefs.contains(KEY_COLLECTOR_ALIVE)) {
            CollectorState(
                lastAliveMs = prefs.getLong(KEY_COLLECTOR_ALIVE, 0L),
                sessionStartedAtMs = prefs.getLong(KEY_COLLECTOR_SESSION, 0L),
                droppedEvents = prefs.getLong(KEY_COLLECTOR_DROPPED, 0L),
            )
        } else null

    private fun recordCollectorGaps(writer: LedgerWriter, previous: CollectorState?, observation: CollectorObservation) {

        val step = GapTracker.update(previous, observation)

        for (gap in step.gaps) {
            writer.recordGap(gap.startMs, gap.endMs, gap.reason, "T2")
        }

        step.state?.let {
            prefs.edit()
                .putLong(KEY_COLLECTOR_ALIVE, it.lastAliveMs)
                .putLong(KEY_COLLECTOR_SESSION, it.sessionStartedAtMs)
                .putLong(KEY_COLLECTOR_DROPPED, it.droppedEvents)
                .apply()
        }
    }

    // ------------------------------------------------------------------ ledger lifecycle

    private fun openLedger(): LedgerWriter? {

        ledger?.let { return it }

        return try {
            val key = keys.loadOrCreate()
            val storage = EncryptedLedgerStorage(File(appContext.filesDir, LEDGER_FILE), key)
            LedgerWriter(storage).also {
                ledger = it
                ledgerFailure = null
            }
        } catch (e: LedgerStorageException) {
            ledgerFailure = e.message ?: "The ledger could not be opened."
            null
        } catch (e: java.security.GeneralSecurityException) {
            ledgerFailure = "The ledger key could not be used: ${e.message}"
            null
        } catch (e: java.io.IOException) {
            ledgerFailure = "The ledger file could not be read: ${e.message}"
            null
        }
    }

    /** The hardware-backed signing key, or null when this phone cannot provide one. */
    private fun signerOrNull(): AndroidKeystoreSigner? {
        signer?.let { return it }
        return try {
            AndroidKeystoreSigner(appContext).also { signer = it }
        } catch (_: Exception) {
            null
        }
    }

    /** Retention: raw rows older than 90 days collapse into daily counts. At most daily. */
    private fun pruneIfDue(writer: LedgerWriter, offset: Long, now: Long, notes: MutableList<String>) {

        if (now - prefs.getLong(KEY_LAST_PRUNE, 0L) < PRUNE_EVERY_MS) return

        val cutoff = Retention.cutoffFor(now, offset)
        val result = writer.pruneBefore(cutoff, signerOrNull(), offset, now)

        prefs.edit().putLong(KEY_LAST_PRUNE, now).apply()

        if (result.prunedCount > 0 && result.anchorCheckpoint?.signatureBytes?.isEmpty() == true) {
            notes += "Old records were summarised, but the secure signing key was unavailable, so the start of the chain is not signed."
        }
    }

    /**
     * A signed checkpoint, at most hourly and only when rows were added since the last one. Signing
     * every row would cost battery for no extra proof; an export always signs a fresh one. A signing
     * failure is reported, never hidden.
     */
    private fun checkpointIfDue(writer: LedgerWriter, now: Long, notes: MutableList<String>) {

        val latest = writer.latestCheckpoint()

        if (writer.all().isEmpty()) return
        if ((latest?.rowCount ?: 0L) >= writer.absoluteRowCount()) return
        if (latest != null && now - latest.timestampMs < CHECKPOINT_EVERY_MS && latest.signatureBytes.isNotEmpty()) return

        val key = signerOrNull()
        if (key == null) {
            notes += "The ledger could not be signed with this phone's secure key. Exported reports will not be verifiable until that is fixed."
            return
        }
        writer.createCheckpoint(key, now)
    }

    // ------------------------------------------------------------------ version memory

    private fun loadKnownVersions(): Map<String, List<AppVersionPoint>> {

        val out = HashMap<String, MutableList<AppVersionPoint>>()

        prefs.getStringSet(KEY_VERSIONS, emptySet()).orEmpty().forEach { line ->
            // package|versionCode|effectiveFromMs
            val parts = line.split('|')
            if (parts.size == 3) {
                val version = parts[1].toLongOrNull() ?: return@forEach
                val from = parts[2].toLongOrNull() ?: return@forEach
                out.getOrPut(parts[0]) { mutableListOf() } += AppVersionPoint(version, from)
            }
        }

        return out.mapValues { (_, v) -> v.distinct().sortedWith(compareBy({ it.effectiveFromMs }, { it.versionCode })) }
    }

    private fun versionHistory(): Map<String, List<AppVersionPoint>> = loadKnownVersions()

    /** Remember every (version, update time) seen, so later runs can place old accesses in a version. */
    private fun saveKnownVersions(known: Map<String, List<AppVersionPoint>>, inventory: List<AppInventory>) {
        val lines = HashSet<String>()
        known.forEach { (pkg, points) -> points.forEach { lines += "$pkg|${it.versionCode}|${it.effectiveFromMs}" } }
        inventory.filter { it.lastUpdateTimeMs > 0L }.forEach {
            lines += "${it.packageName}|${it.versionCode}|${it.lastUpdateTimeMs}"
        }
        prefs.edit().putStringSet(KEY_VERSIONS, lines).apply()
    }

    // ------------------------------------------------------------------ per-app timeline

    /** The newest ledger rows that concern one app, newest first, for its timeline. */
    fun timelineFor(packageName: String, limit: Int = 400): List<LedgerRecord> =
        openLedger()?.all().orEmpty()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.timestampMs }
            .take(limit)

    // ------------------------------------------------------------------ remediation

    // The inventory of the latest check, so the allow-list can tell a user app from a system app.
    @Volatile
    private var cachedInventory: Map<String, AppInventory> = emptyMap()

    private fun executor(): RemediationExecutor? {
        val writer = openLedger() ?: return null
        return RemediationExecutor(
            channel = sensing.remediationChannel(),
            ledger = writer,
            selfPackage = appContext.packageName,
            isRemediable = { pkg -> cachedInventory[pkg]?.isSystemApp == false },
        )
    }

    /** Changes one permission or op mode through Shizuku, verified and recorded. */
    suspend fun remediate(request: RemediationRequest, uid: Int): RemediationOutcome =
        runLock.withLock {
            withContext(Dispatchers.IO) {
                executor()?.execute(request, uid)
                    ?: RemediationOutcome.Failed("The ledger is not available, so nothing was changed.", null)
            }
        }

    suspend fun undo(entry: UndoEntry): RemediationOutcome =
        runLock.withLock {
            withContext(Dispatchers.IO) {
                executor()?.undo(entry)
                    ?: RemediationOutcome.Failed("The ledger is not available, so nothing was changed.", null)
            }
        }

    // ------------------------------------------------------------------ evidence export

    /** Writes the sealed evidence file and its readable twin, and returns shareable links. */
    suspend fun exportEvidence(lang: Lang, findings: List<Finding>, inventory: List<AppInventory>): ExportFiles? =
        runLock.withLock {
            withContext(Dispatchers.IO) {
                val writer = openLedger() ?: return@withContext null
                val key = signerOrNull() ?: return@withContext null

                val now = System.currentTimeMillis()
                if (writer.all().isNotEmpty()) writer.createCheckpoint(key, now)

                val input = ExportInput(
                    rows = writer.all(),
                    checkpoints = writer.allCheckpoints(),
                    chainStart = writer.chainStart(),
                    findings = findings,
                    pack = pack,
                    signer = key,
                    nowMs = now,
                    appVersion = appVersion(),
                    device = mapOf("androidSdk" to android.os.Build.VERSION.SDK_INT.toString()),
                )

                val export = EvidenceExport.build(input)
                val labels = inventory.associate { it.packageName to labelOf(it.packageName) }
                val report = EvidenceReport.render(input, export.digest, lang, { labels[it] ?: it })

                val dir = File(appContext.cacheDir, EXPORT_DIR).also {
                    it.mkdirs()
                    it.listFiles()?.forEach { f -> f.delete() }    // only the latest export is kept
                }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now))
                val evidence = File(dir, "permsense-evidence-$stamp.json").also { it.writeBytes(export.file) }
                val readable = File(dir, "permsense-report-$stamp.html").also { it.writeText(report, Charsets.UTF_8) }

                ExportFiles(uriFor(evidence), uriFor(readable), export.digest)
            }
        }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.exports", file)

    private fun appVersion(): String =
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    // ------------------------------------------------------------------ wipe

    /**
     * One-tap wipe: the ledger and its keys, exported files, the live collector's buffer, and
     * everything remembered about earlier runs. Only the language choice is kept.
     */
    suspend fun wipeEverything() = runLock.withLock {
        withContext(Dispatchers.IO) {
            ledger?.wipeAll()
            ledger = null
            File(appContext.filesDir, LEDGER_FILE).delete()
            keys.destroy()
            signer = null
            AndroidKeystoreSigner.destroy(appContext)
            File(appContext.cacheDir, EXPORT_DIR).deleteRecursively()
            runCatching { sensing.stopT2() }       // also discards whatever it had buffered
            sensing.releaseShizukuService()
            val keep = prefs.getString(KEY_LANG, null)
            prefs.edit().clear().apply()
            keep?.let { prefs.edit().putString(KEY_LANG, it).apply() }
            triage = TriageRanker()
            ledgerFailure = null
            lastReport = null
            cachedInventory = emptyMap()
        }
    }

    companion object {

        private const val PREFS = "permsense_engine"
        private const val LEDGER_FILE = "ledger.plg"
        private const val EXPORT_DIR = "exports"

        private const val KEY_LENDING = "lending_tagged"
        private const val KEY_OBSERVING_SINCE = "observing_since_ms"
        private const val KEY_COUNTING_SINCE = "counting_since_ms"
        private const val KEY_INDICATOR_SINCE = "indicator_since_ms"
        private const val KEY_VERSIONS = "known_versions"
        private const val KEY_COLLECTOR_ALIVE = "collector_last_alive"
        private const val KEY_COLLECTOR_SESSION = "collector_session"
        private const val KEY_COLLECTOR_DROPPED = "collector_dropped"
        private const val KEY_LAST_PRUNE = "last_prune_ms"
        private const val KEY_LAST_HISTORY = "last_history_ms"
        private const val KEY_LAST_RUN = "last_run_ms"
        private const val KEY_TRIAGE = "triage"
        private const val KEY_SNOOZED = "snoozed"
        private const val KEY_ANNOUNCED = "announced"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_LANG = "language"
        private const val KEY_DIGEST = "digest"
        private const val KEY_T1_ONLY = "t1_only"
        private const val KEY_T2_ENABLED = "t2_enabled"

        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS

        /** Enough usage history to place the newest accesses, and as much as the oldest one needs. */
        private const val MIN_LOOKBACK_MS = 6L * HOUR_MS
        private const val MAX_LOOKBACK_MS = 8L * DAY_MS
        private const val CONTEXT_MARGIN_MS = 10L * 60L * 1000L

        private const val HISTORY_EVERY_MS = 6L * HOUR_MS
        private const val PRUNE_EVERY_MS = 20L * HOUR_MS
        private const val CHECKPOINT_EVERY_MS = HOUR_MS
        private const val SNOOZE_MS = 7L * DAY_MS
        private const val ANNOUNCE_AGAIN_MS = 3L * DAY_MS
        private const val MAX_LIVE_EVENTS_PER_RUN = 12_000

        @Volatile
        private var instance: AuditEngine? = null

        /** The saved language choice, readable before the engine exists (the activity needs it first). */
        fun savedLanguageTag(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)

        /** The one engine for this process. */
        fun of(context: Context): AuditEngine =
            instance ?: synchronized(this) {
                instance ?: AuditEngine(context).also { instance = it }
            }
    }
}
