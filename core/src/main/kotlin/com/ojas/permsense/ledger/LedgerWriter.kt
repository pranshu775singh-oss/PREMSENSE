package com.ojas.permsense.ledger

import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.Episode
import com.ojas.permsense.model.RawObservation

sealed interface ChainVerification {

    data object Valid :
        ChainVerification

    data class Broken(
        val atIndex: Int,
        val reason: String
    ) : ChainVerification
}

/**
 * How the first remaining row of the chain is anchored.
 *
 * A ledger that has never been pruned starts at [GENESIS]. After retention removes old rows, the
 * first remaining row chains from the hash of the last removed one, which a checkpoint records:
 * [SIGNED_ANCHOR] when that checkpoint carries a signature, [UNSIGNED_ANCHOR] when pruning ran
 * without a signing key. [UNANCHORED] means the start matches nothing, which verification rejects.
 */
enum class ChainStart {
    GENESIS,
    SIGNED_ANCHOR,
    UNSIGNED_ANCHOR,
    UNANCHORED
}

/**
 * Checks that every row's hash matches its content and links to the row before it.
 *
 * The first row must link to [GENESIS_HASH] or to one of [anchorHashes], the hashes recorded by
 * checkpoints when retention removed the rows before it.
 */
fun verifyChain(
    rows: List<LedgerRecord>,
    anchorHashes: Set<String> = emptySet()
): ChainVerification {

    var expectedPrev: String? = null

    rows.forEachIndexed { index, row ->

        val linkOk =
            if (index == 0) {
                row.prevHash == GENESIS_HASH || row.prevHash in anchorHashes
            } else {
                row.prevHash == expectedPrev
            }

        if (!linkOk) {
            return ChainVerification.Broken(atIndex = index, reason = "prevHash mismatch")
        }

        if (row.computeHash() != row.hash) {
            return ChainVerification.Broken(atIndex = index, reason = "hash mismatch")
        }

        expectedPrev = row.hash
    }

    return ChainVerification.Valid
}

class LedgerWriter(
    private val storage: LedgerStorage = InMemoryLedgerStorage(),
    private val autoVerifyOnStartup: Boolean = true
) {

    private val lock = Any()

    private val rows = mutableListOf<LedgerRecord>()

    private val pending = mutableListOf<ContextualisedEpisode>()

    // Every source event the ledger has taken responsibility for: sealed into a row, or
    // recognised as a duplicate of one. A collector may drop an event from its buffer once it
    // is in here, and not before.
    private val knownSourceIds = mutableSetOf<String>()

    // Sealed access rows indexed by (uid, package, op) so duplicate checks do not scan the ledger.
    private val rowsByCell = HashMap<Triple<Int, String, String>, MutableList<LedgerRecord>>()

    // Episodes dropped because they re-describe an already sealed row. Never silent.
    private var suppressedDuplicates = 0L

    private var startupVerification: ChainVerification = ChainVerification.Valid

    // Rows removed from the front of the chain by retention. Row n of the whole chain is
    // chainOffset + its index in [rows].
    private var chainOffset = 0L

    private var chainStart = ChainStart.GENESIS

    // Hash to chain the next row from when no rows remain (everything was pruned).
    private var emptyHead = GENESIS_HASH

    init {
        synchronized(lock) {
            val persisted = storage.allRows()
            val checkpoints = storage.allCheckpoints()

            if (persisted.isNotEmpty()) {
                val verification = verifyChain(persisted, checkpoints.map { it.hash }.toSet())
                startupVerification = verification
                if (autoVerifyOnStartup && verification is ChainVerification.Broken) {
                    throw LedgerStorageException.CorruptedStorageException(
                        "Persisted ledger chain is broken at index ${verification.atIndex}: ${verification.reason}"
                    )
                }
                rows.addAll(persisted)
                reindex()
            } else {
                checkpoints.lastOrNull()?.let { emptyHead = it.hash }
            }

            resolveStart(checkpoints)
        }
    }

    private fun resolveStart(checkpoints: List<SignedCheckpoint>) {

        val firstPrev = rows.firstOrNull()?.prevHash ?: emptyHead

        if (firstPrev == GENESIS_HASH) {
            chainOffset = 0L
            chainStart = ChainStart.GENESIS
            return
        }

        val anchor = checkpoints.lastOrNull { it.hash == firstPrev }

        if (anchor == null) {
            chainStart = ChainStart.UNANCHORED
            return
        }

        chainOffset = anchor.rowCount
        chainStart =
            if (anchor.signatureBytes.isEmpty()) ChainStart.UNSIGNED_ANCHOR
            else ChainStart.SIGNED_ANCHOR
    }

    private fun reindex() {
        knownSourceIds.clear()
        rowsByCell.clear()
        rows.forEach { index(it) }
    }

    private fun index(row: LedgerRecord) {
        knownSourceIds += row.sourceEventIds
        if (
            row.recordKind == LedgerRecordKind.EPISODE.name ||
            row.recordKind == LedgerRecordKind.BACKFILL.name
        ) {
            rowsByCell
                .getOrPut(Triple(row.uid, row.packageName, row.op)) { mutableListOf() }
                .add(row)
        }
    }

    private fun head(): String = rows.lastOrNull()?.hash ?: emptyHead

    fun append(
        batch: List<ContextualisedEpisode>,
        nowMs: Long
    ): List<LedgerRecord> {

        synchronized(lock) {

            if (batch.isEmpty()) {
                return emptyList()
            }

            val fresh =
                batch.filter { candidate ->

                    val duplicate = alreadySealed(candidate)

                    if (duplicate) {
                        suppressedDuplicates++
                        knownSourceIds += candidate.episode.sourceEventIds
                    }

                    !duplicate
                }

            addPending(fresh)

            mergePending()

            return sealReady(nowMs)
        }
    }

    fun flush(
        nowMs: Long
    ): List<LedgerRecord> {

        synchronized(lock) {

            mergePending()

            return sealReady(nowMs)
        }
    }

    private fun sealReady(nowMs: Long): List<LedgerRecord> {

        val ready = pending.filter { nowMs - it.episode.endMs > EPISODE_SEAL_DELAY_MS }

        // Identity, not equality: episodes are large value objects.
        val readySet =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ContextualisedEpisode, Boolean>())
        readySet.addAll(ready)

        pending.removeAll { it in readySet }

        return seal(ready)
    }

    /**
     * Adds episodes to the pending set without double counting.
     *
     * The same observations can arrive twice while an episode is still open (a collector that was
     * not acknowledged yet, a re-run of the same scan). An episode whose events are all already
     * pending is dropped, and a pending episode that a new, larger one fully contains is replaced.
     */
    private fun addPending(fresh: List<ContextualisedEpisode>) {

        for (candidate in fresh.sortedWith(PENDING_ORDER)) {

            val ids = candidate.episode.sourceEventIds.toSet()

            if (ids.isNotEmpty() && pending.any { it.episode.sourceEventIds.toSet().containsAll(ids) }) {
                continue
            }

            if (ids.isNotEmpty()) {
                pending.removeAll {
                    val other = it.episode.sourceEventIds
                    other.isNotEmpty() && ids.containsAll(other)
                }
            }

            pending += candidate
        }
    }

    private fun mergePending() {

        if (pending.size < 2) {
            return
        }

        // Group first. Episodes of one cell must merge even when another cell's episode sits
        // between them in time order.
        val merged = mutableListOf<ContextualisedEpisode>()

        for (group in pending.groupBy { mergeKey(it) }.values) {

            var current: ContextualisedEpisode? = null

            for (next in group.sortedWith(PENDING_ORDER)) {

                val previous = current

                current =
                    if (previous != null && canMerge(previous, next)) {
                        mergeEpisodes(previous, next)
                    } else {

                        if (previous != null) {
                            merged += previous
                        }

                        next
                    }
            }

            current?.let { merged += it }
        }

        pending.clear()
        pending.addAll(merged.sortedWith(PENDING_ORDER))
    }

    private fun mergeKey(e: ContextualisedEpisode): List<Any> {

        return listOf(
            e.episode.uid,
            e.episode.packageName,
            e.episode.op,
            e.stratum,
            e.screenState,
            e.appVersionAtAccess,
            e.episode.flags,
            e.episode.accessResults.sorted()
        )
    }

    private fun canMerge(a: ContextualisedEpisode, b: ContextualisedEpisode): Boolean {

        return a.episode.uid == b.episode.uid &&
                a.episode.packageName == b.episode.packageName &&
                a.episode.op == b.episode.op &&
                a.stratum == b.stratum &&
                a.screenState == b.screenState &&
                a.appVersionAtAccess == b.appVersionAtAccess &&
                a.episode.flags == b.episode.flags &&
                a.episode.accessResults == b.episode.accessResults &&
                b.episode.startMs - a.episode.endMs <= EPISODE_RECONCILE_MS
    }

    private fun mergeEpisodes(a: ContextualisedEpisode, b: ContextualisedEpisode): ContextualisedEpisode {

        // Count each distinct observation once, so two overlapping views of the same events
        // do not inflate the count. Hand-built episodes without observations fall back to a sum.
        val observations =
            (a.episode.observations + b.episode.observations)
                .distinctBy { it.tier to it.sourceEventId }

        val observationCount =
            if (a.episode.observations.isEmpty() || b.episode.observations.isEmpty()) {
                a.episode.observationCount + b.episode.observationCount
            } else {
                observations.size
            }

        val mergedEpisode =
            a.episode.copy(
                endMs = maxOf(a.episode.endMs, b.episode.endMs),
                observationCount = observationCount,
                tiers = a.episode.tiers + b.episode.tiers,
                sourceEventIds = (a.episode.sourceEventIds + b.episode.sourceEventIds).distinct().sorted(),
                accessKinds = a.episode.accessKinds + b.episode.accessKinds,
                durationMs = listOfNotNull(a.episode.durationMs, b.episode.durationMs).maxOrNull(),
                attributionTags = a.episode.attributionTags + b.episode.attributionTags,
                accessResults = a.episode.accessResults + b.episode.accessResults,
                observations =
                    observations.sortedWith(compareBy({ it.timestampMs }, { it.sourceEventId }))
            )

        return a.copy(episode = mergedEpisode)
    }

    /**
     * True when [candidate] merely re-describes a sealed row (late backfill, replay).
     * Same cell alone is not enough: a different stratum, flags or version within 60 s is NEW
     * evidence (a screen-off access right after a background one) and must be kept.
     */
    private fun alreadySealed(candidate: ContextualisedEpisode): Boolean {

        val episode = candidate.episode

        if (episode.sourceEventIds.any { it in knownSourceIds }) {
            return true
        }

        val sameCell =
            rowsByCell[Triple(episode.uid, episode.packageName, episode.op)] ?: return false

        // Same field derivation the sealed rows used, so like is compared with like.
        val wouldBe = candidate.toLedgerRecord(previousHash = GENESIS_HASH)

        return sameCell.any { row ->
            row.visibilityStratum == wouldBe.visibilityStratum &&
                    row.flags == wouldBe.flags &&
                    row.accessResult == wouldBe.accessResult &&
                    row.versionCode == wouldBe.versionCode &&
                    gapBetweenMs(row, episode) <= EPISODE_RECONCILE_MS
        }
    }

    /** Distance between a sealed row's covered interval and an episode's (0 when they overlap). */
    private fun gapBetweenMs(row: LedgerRecord, episode: Episode): Long {

        val rowEndMs = row.timestampMs + (row.durationMs ?: 0L).coerceAtLeast(0L)

        return maxOf(
            row.timestampMs - episode.endMs,
            episode.startMs - rowEndMs,
            0L
        )
    }

    private fun seal(episodes: List<ContextualisedEpisode>): List<LedgerRecord> {

        if (episodes.isEmpty()) {
            return emptyList()
        }

        val ordered =
            episodes.sortedWith(
                compareBy(
                    { it.episode.startMs },
                    { it.episode.uid },
                    { it.episode.packageName },
                    { it.episode.op },
                    { it.stratum.ordinal },
                    { it.episode.flags },
                    { it.episode.sourceEventIds.joinToString(",") }
                )
            )

        var previousHash = head()

        val sealed =
            ordered.map { contextualised ->

                val sealedRow =
                    sealRecord(contextualised.toLedgerRecord(previousHash = previousHash))

                previousHash = sealedRow.hash

                sealedRow
            }

        storage.insertBatch(sealed)

        rows += sealed

        sealed.forEach { index(it) }

        // A row keeps a capped list of ids; the collector must be told about every one.
        ordered.forEach { knownSourceIds += it.episode.sourceEventIds }

        return sealed
    }

    /** True when the ledger has taken responsibility for this source event. */
    fun isKnown(sourceEventId: String): Boolean {

        synchronized(lock) {
            return sourceEventId in knownSourceIds
        }
    }

    /** The subset of [ids] that are safely in the ledger and may be acknowledged to a collector. */
    fun knownAmong(ids: Collection<String>): Set<String> {

        synchronized(lock) {
            return ids.filterTo(HashSet()) { it in knownSourceIds }
        }
    }

    fun recordGap(
        startMs: Long,
        endMs: Long,
        reason: String,
        sourceTier: String = "T2"
    ): LedgerRecord {
        synchronized(lock) {
            val raw = createGapRecord(
                previousHash = head(),
                startMs = startMs,
                endMs = endMs,
                reason = reason,
                sourceTier = sourceTier
            )
            val sealed = sealRecord(raw)
            storage.insertRecord(sealed)
            rows += sealed
            index(sealed)
            return sealed
        }
    }

    fun recordBackfill(
        observation: RawObservation,
        versionCode: Long = 0L
    ): LedgerRecord {
        return recordBackfillBatch(listOf(observation), versionCode).single()
    }

    fun recordBackfillBatch(
        observations: List<RawObservation>,
        versionCode: Long = 0L
    ): List<LedgerRecord> {
        synchronized(lock) {
            if (observations.isEmpty()) return emptyList()

            var previousHash = head()
            val sealedList = mutableListOf<LedgerRecord>()
            val taken = HashSet<String>()

            for (obs in observations) {
                if (obs.sourceEventId in knownSourceIds || obs.sourceEventId in taken) {
                    suppressedDuplicates++
                    continue
                }
                val raw = createBackfillRecord(
                    previousHash = previousHash,
                    observation = obs,
                    versionCode = versionCode
                )
                val sealed = sealRecord(raw)
                previousHash = sealed.hash
                sealedList += sealed
                taken += obs.sourceEventId
            }

            if (sealedList.isNotEmpty()) {
                storage.insertBatch(sealedList)
                rows += sealedList
                sealedList.forEach { index(it) }
            }
            return sealedList
        }
    }

    fun recordRemediation(
        targetPackage: String,
        uid: Int,
        opOrPermission: String,
        action: String,
        priorState: String,
        newState: String,
        success: Boolean,
        timestampMs: Long = System.currentTimeMillis(),
        phase: String = "RESULT",
        result: String = if (success) "SUCCESS" else "FAILED",
        undoes: String? = null
    ): LedgerRecord {
        synchronized(lock) {
            val raw = createRemediationRecord(
                previousHash = head(),
                targetPackage = targetPackage,
                uid = uid,
                opOrPermission = opOrPermission,
                action = action,
                priorState = priorState,
                newState = newState,
                success = success,
                timestampMs = timestampMs,
                phase = phase,
                result = result,
                undoes = undoes
            )
            val sealed = sealRecord(raw)
            storage.insertRecord(sealed)
            rows += sealed
            index(sealed)
            return sealed
        }
    }

    fun createCheckpoint(
        signer: LedgerSigner,
        timestampMs: Long = System.currentTimeMillis()
    ): SignedCheckpoint {
        synchronized(lock) {
            val checkpoint = signedCheckpoint(head(), timestampMs, chainOffset + rows.size, signer)
            storage.insertCheckpoint(checkpoint)
            return checkpoint
        }
    }

    private fun signedCheckpoint(
        hash: String,
        timestampMs: Long,
        rowCount: Long,
        signer: LedgerSigner?
    ): SignedCheckpoint {

        if (signer == null) {
            // An anchor with no signature: retention still has to leave the chain verifiable.
            return SignedCheckpoint(
                hash = hash,
                signatureBytes = ByteArray(0),
                timestampMs = timestampMs,
                rowCount = rowCount,
                keyAlias = "unsigned",
                publicKeyDer = null
            )
        }

        return SignedCheckpoint(
            hash = hash,
            signatureBytes = signer.sign(checkpointSignPayload(hash, timestampMs, rowCount)),
            timestampMs = timestampMs,
            rowCount = rowCount,
            keyAlias = signer.keyAlias,
            publicKeyDer = signer.getPublicKeyDer()
        )
    }

    fun verifyCheckpoint(
        checkpoint: SignedCheckpoint,
        signer: LedgerSigner? = null
    ): Boolean {
        if (checkpoint.signatureBytes.isEmpty()) return false
        val payload = checkpointSignPayload(checkpoint.hash, checkpoint.timestampMs, checkpoint.rowCount)
        val testSigner = signer ?: SoftwareEcSigner(checkpoint.keyAlias)
        return testSigner.verify(payload, checkpoint.signatureBytes, checkpoint.publicKeyDer)
    }

    fun allCheckpoints(): List<SignedCheckpoint> {
        synchronized(lock) {
            return storage.allCheckpoints()
        }
    }

    fun latestCheckpoint(): SignedCheckpoint? {
        synchronized(lock) {
            return storage.latestCheckpoint()
        }
    }

    fun startupVerificationResult(): ChainVerification = startupVerification

    /** How the first remaining row is anchored. See [ChainStart]. */
    fun chainStart(): ChainStart {
        synchronized(lock) {
            return chainStart
        }
    }

    /** Rows in the whole chain so far, including those retention has already removed. */
    fun absoluteRowCount(): Long {
        synchronized(lock) {
            return chainOffset + rows.size
        }
    }

    /**
     * Retention. Removes the leading rows older than [cutoffTimestampMs] and replaces them with
     * daily aggregates.
     *
     * Only a prefix of the chain can be removed: deleting rows from the middle would break the
     * hash links. A row stored late (a backfill with an old timestamp) therefore stays until
     * every row before it is old enough too. The first remaining row chains from the hash of
     * the last removed one, which a checkpoint records ([signer] signs it when given).
     */
    fun pruneBefore(
        cutoffTimestampMs: Long,
        signer: LedgerSigner? = null,
        zoneOffsetMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): PruneResult {
        synchronized(lock) {

            var count = 0
            while (count < rows.size && rows[count].timestampMs < cutoffTimestampMs) {
                count++
            }

            if (count == 0) {
                return PruneResult(
                    prunedCount = 0,
                    remainingCount = rows.size.toLong(),
                    anchorCheckpoint = null
                )
            }

            val boundary = rows[count - 1]

            val drafts = Retention.aggregate(rows.subList(0, count), zoneOffsetMs, boundary.hash)

            var previousHash = rows.last().hash
            val aggregates = drafts.map { draft ->
                sealRecord(draft.copy(prevHash = previousHash)).also { previousHash = it.hash }
            }

            val boundaryCount = chainOffset + count
            val anchor = signedCheckpoint(boundary.hash, nowMs, boundaryCount, signer)

            // Anchor first, then the swap. If the process dies between the two, the extra
            // checkpoint is harmless and the next prune simply repeats.
            storage.insertCheckpoint(anchor)
            storage.replaceLeading(count, aggregates)

            rows.subList(0, count).clear()
            rows += aggregates
            chainOffset = boundaryCount
            emptyHead = boundary.hash
            chainStart =
                if (anchor.signatureBytes.isEmpty()) ChainStart.UNSIGNED_ANCHOR
                else ChainStart.SIGNED_ANCHOR

            reindex()

            return PruneResult(
                prunedCount = count,
                remainingCount = rows.size.toLong(),
                anchorCheckpoint = anchor,
                aggregatesWritten = aggregates.size
            )
        }
    }

    fun wipeAll() {
        synchronized(lock) {
            storage.wipeAll()
            rows.clear()
            pending.clear()
            knownSourceIds.clear()
            rowsByCell.clear()
            suppressedDuplicates = 0L
            chainOffset = 0L
            chainStart = ChainStart.GENESIS
            emptyHead = GENESIS_HASH
        }
    }

    fun all(): List<LedgerRecord> {

        synchronized(lock) {
            return rows.toList()
        }
    }

    /** Episodes dropped as duplicates of an already sealed row. Surface this in diagnostics. */
    fun suppressedCount(): Long {

        synchronized(lock) {
            return suppressedDuplicates
        }
    }

    fun pendingCount(): Int {

        synchronized(lock) {
            return pending.size
        }
    }

    fun latestHash(): String {

        synchronized(lock) {
            return head()
        }
    }

    fun verify(): ChainVerification {

        synchronized(lock) {
            return verifyChain(rows, storage.allCheckpoints().map { it.hash }.toSet())
        }
    }

    fun clearForTests() {
        wipeAll()
    }
}
