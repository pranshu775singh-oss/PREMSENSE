package com.ojas.permsense.ledger

import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.Episode
import com.ojas.permsense.model.RawObservation

import java.security.MessageDigest

const val GENESIS_HASH =
    "0000000000000000000000000000000000000000000000000000000000000000"

private const val LEDGER_SCHEMA_VERSION =
    3

internal const val EPISODE_SEAL_DELAY_MS =
    61_500L

internal const val EPISODE_RECONCILE_MS =
    60_000L

internal val PENDING_ORDER: Comparator<ContextualisedEpisode> =
    compareBy<ContextualisedEpisode>(
        { it.episode.startMs },
        { it.episode.uid },
        { it.episode.packageName },
        { it.episode.op },
        { it.stratum.ordinal },
        { it.episode.flags },
        { it.episode.endMs },
        { it.episode.sourceEventIds.joinToString(",") }
    )

enum class LedgerRecordKind {
    EPISODE,
    GAP,
    BACKFILL,
    REMEDIATION,

    /** Daily per-cell episode count that replaces raw rows once they age out of retention. */
    AGGREGATE
}

/** Most source ids one row keeps. A longer episode keeps a count and digest of the rest. */
const val MAX_STORED_SOURCE_IDS = 64

/** The ids stored for a row: all of them when few, otherwise the first ones plus a digest token. */
fun capSourceIds(ids: List<String>): List<String> {

    val distinct = ids.distinct().sorted()
    if (distinct.size <= MAX_STORED_SOURCE_IDS) return distinct

    val kept = distinct.take(MAX_STORED_SOURCE_IDS - 1)
    val rest = distinct.drop(MAX_STORED_SOURCE_IDS - 1)
    val digest = sha256Hex(rest.joinToString(",").toByteArray(Charsets.UTF_8)).take(32)

    return kept + "more:${rest.size}:$digest"
}

data class LedgerRecord(
    val timestampMs: Long,
    val uid: Int,
    val packageName: String,
    val versionCode: Long,

    val op: String,
    val accessKind: String,

    val durationMs: Long?,
    val attributionTag: String?,
    val flags: Int,

    val visibilityStratum: String,
    val screenState: String,

    val sourceTier: String,
    val observationCount: Int,

    val accessResult: String?,

    val sourceEventIds: List<String>,

    val recordKind: String =
        LedgerRecordKind.EPISODE.name,

    val userId: Int =
        uid / 100000,

    val schemaVersion: Int =
        LEDGER_SCHEMA_VERSION,

    val prevHash: String,

    val hash: String = ""
) {

    val stratum: String
        get() = visibilityStratum
}

/**
 * One field of a row's canonical form: its length in characters, a colon, then the text.
 *
 * A missing value is written as a lone "N". The first version wrote the four letters "NULL" for
 * it, which a string field holding exactly "NULL" also produced, so a missing attribution tag
 * could be swapped for the text "NULL" without changing the row's hash. "N" cannot be confused
 * with anything else: every present value starts with a digit.
 */
private fun canonicalField(
    value: Any?
): String {

    if (value == null) {
        return "N"
    }

    val text =
        value.toString()

    return "${text.length}:$text"
}

fun LedgerRecord.canonicalBytes():
        ByteArray {

    val fields =
        listOf(
            schemaVersion,
            recordKind,
            timestampMs,
            userId,
            uid,
            packageName,
            versionCode,
            op,
            accessKind,
            durationMs,
            attributionTag,
            flags,
            visibilityStratum,
            screenState,
            sourceTier,
            observationCount,
            accessResult,
            sourceEventIds
                .distinct()
                .sorted()
                .joinToString(",")
        )

    return fields
        .joinToString("|") {
            canonicalField(it)
        }
        .toByteArray(
            Charsets.UTF_8
        )
}

fun sha256Hex(
    input: ByteArray
): String {

    return MessageDigest
        .getInstance("SHA-256")
        .digest(input)
        .joinToString("") {
            "%02x".format(it)
        }
}

fun LedgerRecord.computeHash():
        String {

    val previous =
        prevHash.toByteArray(
            Charsets.UTF_8
        )

    val separator =
        byteArrayOf(
            0x1F
        )

    return sha256Hex(
        previous +
                separator +
                canonicalBytes()
    )
}

fun sealRecord(
    unsealed: LedgerRecord
): LedgerRecord {

    return unsealed.copy(
        hash =
            unsealed.computeHash()
    )
}

fun Episode.toLedgerRecord(
    previousHash: String,
    screenState: String,
    appVersionAtAccess: Long
): LedgerRecord {

    val accessKind =
        when {
            "started" in accessKinds ->
                "started"

            "active" in accessKinds ->
                "active"

            "noted" in accessKinds ->
                "noted"

            "finished" in accessKinds ->
                "finished"

            "presence" in accessKinds ->
                "presence"

            "rejected" in accessKinds ->
                "rejected"

            else ->
                accessKinds
                    .sorted()
                    .firstOrNull()
                    ?: "unknown"
        }

    val accessResult =
        when {
            accessResults.any {
                it.equals(
                    "ERRORED",
                    ignoreCase = true
                )
            } ->
                "ERRORED"

            accessResults.any {
                it.equals(
                    "IGNORED",
                    ignoreCase = true
                )
            } ->
                "IGNORED"

            accessResults.any {
                it.equals(
                    "REJECTED",
                    ignoreCase = true
                )
            } ->
                "REJECTED"

            accessResults.any {
                it.equals(
                    "OBSERVED",
                    ignoreCase = true
                )
            } ->
                "OBSERVED"

            else ->
                accessResults
                    .sorted()
                    .firstOrNull()
        }

    return LedgerRecord(
        timestampMs =
            startMs,

        uid =
            uid,

        packageName =
            packageName,

        versionCode =
            appVersionAtAccess,

        op =
            op,

        accessKind =
            accessKind,

        durationMs =
            durationMs
                ?: (
                        endMs - startMs
                        ).takeIf {
                        it > 0L
                    },

        attributionTag =
            attributionTags
                .sorted()
                .firstOrNull(),

        flags =
            flags,

        visibilityStratum =
            stratum.name,

        screenState =
            screenState,

        sourceTier =
            tiers
                .sortedBy {
                    it.ordinal
                }
                .joinToString(","),

        observationCount =
            observationCount,

        accessResult =
            accessResult,

        sourceEventIds =
            capSourceIds(sourceEventIds),

        recordKind =
            LedgerRecordKind.EPISODE.name,

        prevHash =
            previousHash
    )
}

fun ContextualisedEpisode.toLedgerRecord(
    previousHash: String
): LedgerRecord {

    // The resolved stratum lives on THIS object. Episode.stratum can still be UNKNOWN when the
    // episode was grouped without usage context and joinContext() resolved it afterwards.
    return episode.copy(stratum = stratum).toLedgerRecord(
        previousHash =
            previousHash,

        screenState =
            screenState,

        appVersionAtAccess =
            appVersionAtAccess
    )
}

fun createGapRecord(
    previousHash: String,
    startMs: Long,
    endMs: Long,
    reason: String,
    sourceTier: String = "T2"
): LedgerRecord {
    return LedgerRecord(
        timestampMs = startMs,
        uid = 1000,
        packageName = "android.framework",
        versionCode = 0L,
        op = "system:collector_gap",
        accessKind = reason,
        durationMs = (endMs - startMs).coerceAtLeast(0L),
        attributionTag = null,
        flags = 0,
        visibilityStratum = "UNKNOWN",
        screenState = "UNKNOWN",
        sourceTier = sourceTier,
        observationCount = 1,
        accessResult = "GAP",
        sourceEventIds = listOf("gap-$startMs-$endMs"),
        recordKind = LedgerRecordKind.GAP.name,
        prevHash = previousHash
    )
}

fun createBackfillRecord(
    previousHash: String,
    observation: RawObservation,
    versionCode: Long = 0L
): LedgerRecord {
    return LedgerRecord(
        timestampMs = observation.timestampMs,
        uid = observation.uid,
        packageName = observation.packageName,
        versionCode = versionCode,
        op = observation.op,
        accessKind = observation.accessKind,
        durationMs = observation.durationMs,
        attributionTag = observation.attributionTag,
        flags = observation.flags,
        visibilityStratum = "UNKNOWN",
        screenState = "UNKNOWN",
        sourceTier = observation.tier.name,
        observationCount = 1,
        accessResult = observation.accessResult ?: "OBSERVED",
        sourceEventIds = listOf(observation.sourceEventId),
        recordKind = LedgerRecordKind.BACKFILL.name,
        prevHash = previousHash
    )
}

fun createRemediationRecord(
    previousHash: String,
    targetPackage: String,
    uid: Int,
    opOrPermission: String,
    action: String,
    priorState: String,
    newState: String,
    success: Boolean,
    timestampMs: Long,
    /** INTENT is written before a change is attempted, RESULT after it is read back. */
    phase: String = "RESULT",
    /** SUCCESS, FAILED, NOT_APPLIED or PENDING. Defaults from [success]. */
    result: String = if (success) "SUCCESS" else "FAILED",
    /** Hash of the earlier change this one reverses, if it is an undo. */
    undoes: String? = null
): LedgerRecord {
    val details = listOfNotNull(
        "prior=$priorState",
        "new=$newState",
        "phase=$phase",
        undoes?.let { "undoes=$it" }
    ).joinToString(";")

    return LedgerRecord(
        timestampMs = timestampMs,
        uid = uid,
        packageName = targetPackage,
        versionCode = 0L,
        op = opOrPermission,
        accessKind = action,
        durationMs = null,
        attributionTag = details,
        flags = if (result == "SUCCESS") 1 else 0,
        visibilityStratum = "REMEDIATION",
        screenState = "UNKNOWN",
        sourceTier = "T2",
        observationCount = 1,
        accessResult = result,
        sourceEventIds = listOf("rem-$phase-$timestampMs-$targetPackage-$opOrPermission-$action"),
        recordKind = LedgerRecordKind.REMEDIATION.name,
        prevHash = previousHash
    )
}

