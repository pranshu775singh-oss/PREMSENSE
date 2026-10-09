package com.ojas.permsense.wire

import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class WireFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Compact binary form of a list of observations, for moving them between the privileged Shizuku
 * process and the app.
 *
 * Why this exists: a Binder reply is limited to about 1 MB. The original transfer wrote each
 * observation field by field into a Parcel, with a 64 character hex id as UTF-16 text, about
 * 350 bytes each. A phone with a few thousand AppOps entries exceeds the limit and the T1 scan
 * fails with TransactionTooLargeException. This form shares repeated strings through a table and
 * stores the id as 32 raw bytes, about 70 bytes each, and the result is then moved in chunks.
 */
object ObservationWire {

    private val MAGIC = byteArrayOf(0x50, 0x53, 0x4F, 0x42) // "PSOB"

    const val VERSION = 1
    const val MAX_OBSERVATIONS = 1_000_000

    /** Distinct packages, ops, kinds, tags and results in one payload. A phone has thousands. */
    private const val MAX_TABLE_ENTRIES = 200_000L

    private const val HAS_DURATION = 1
    private const val HAS_ATTRIBUTION = 2
    private const val HAS_OP_MODE = 4
    private const val HAS_UID_STATE = 8
    private const val HAS_RESULT = 16

    fun encode(observations: List<RawObservation>): ByteArray {

        val buffer = ByteArrayOutputStream(observations.size * 72 + 64)
        val out = DataOutputStream(buffer)

        out.write(MAGIC)
        out.writeByte(VERSION)

        val table = LinkedHashMap<String, Int>()
        fun idx(s: String): Int = table.getOrPut(s) { table.size }

        // Intern in a fixed order so the table, and so the whole encoding, is deterministic.
        observations.forEach {
            idx(it.packageName)
            idx(it.op)
            idx(it.accessKind)
            it.attributionTag?.let { tag -> idx(tag) }
            it.accessResult?.let { result -> idx(result) }
        }

        VarInt.writeUnsigned(out, table.size.toLong())
        table.keys.forEach { out.writeUTF(it) }

        VarInt.writeUnsigned(out, observations.size.toLong())

        for (o in observations) {

            out.writeByte(o.tier.ordinal)

            val raw = Hex.decode32(o.sourceEventId)
            if (raw != null) {
                out.writeByte(1)
                out.write(raw)
            } else {
                out.writeByte(0)
                out.writeUTF(o.sourceEventId)
            }

            VarInt.writeSigned(out, o.uid.toLong())
            VarInt.writeUnsigned(out, idx(o.packageName).toLong())
            VarInt.writeUnsigned(out, idx(o.op).toLong())
            VarInt.writeUnsigned(out, idx(o.accessKind).toLong())
            VarInt.writeSigned(out, o.timestampMs)

            var mask = 0
            if (o.durationMs != null) mask = mask or HAS_DURATION
            if (o.attributionTag != null) mask = mask or HAS_ATTRIBUTION
            if (o.opMode != null) mask = mask or HAS_OP_MODE
            if (o.uidState != null) mask = mask or HAS_UID_STATE
            if (o.accessResult != null) mask = mask or HAS_RESULT
            out.writeByte(mask)

            o.durationMs?.let { VarInt.writeSigned(out, it) }
            o.attributionTag?.let { VarInt.writeUnsigned(out, idx(it).toLong()) }
            o.opMode?.let { VarInt.writeSigned(out, it.toLong()) }
            o.uidState?.let { VarInt.writeSigned(out, it.toLong()) }
            o.accessResult?.let { VarInt.writeUnsigned(out, idx(it).toLong()) }

            VarInt.writeSigned(out, o.flags.toLong())
        }

        out.flush()
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): List<RawObservation> {

        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val result = decode(input)
            if (input.available() != 0) {
                throw WireFormatException("Unexpected trailing bytes after the last observation")
            }
            return result
        } catch (e: WireFormatException) {
            throw e
        } catch (e: IOException) {
            throw WireFormatException("Truncated or corrupt observation data", e)
        } catch (e: RuntimeException) {
            throw WireFormatException("Corrupt observation data", e)
        }
    }

    internal fun decode(input: DataInputStream): List<RawObservation> {

        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw WireFormatException("Not observation data")

        val version = input.readUnsignedByte()
        if (version != VERSION) throw WireFormatException("Unsupported observation data version $version")

        val tableSize = VarInt.readUnsigned(input)
        if (tableSize < 0 || tableSize > MAX_TABLE_ENTRIES) throw WireFormatException("Implausible string table size")
        val table = ArrayList<String>(tableSize.toInt())
        repeat(tableSize.toInt()) { table += input.readUTF() }

        fun str(): String {
            val i = VarInt.readUnsigned(input)
            if (i < 0 || i >= table.size) throw WireFormatException("String index out of range")
            return table[i.toInt()]
        }

        val count = VarInt.readUnsigned(input)
        if (count < 0 || count > MAX_OBSERVATIONS) throw WireFormatException("Implausible observation count $count")

        val tiers = Tier.values()
        val result = ArrayList<RawObservation>(count.toInt())

        repeat(count.toInt()) {

            val tierIndex = input.readUnsignedByte()
            val tier = tiers.getOrNull(tierIndex) ?: throw WireFormatException("Invalid tier $tierIndex")

            val id = when (val form = input.readUnsignedByte()) {
                1 -> {
                    val raw = ByteArray(32)
                    input.readFully(raw)
                    Hex.encode(raw)
                }
                0 -> input.readUTF()
                else -> throw WireFormatException("Invalid id form $form")
            }

            val uid = VarInt.readSigned(input).toInt()
            val pkg = str()
            val op = str()
            val kind = str()
            val timestamp = VarInt.readSigned(input)

            val mask = input.readUnsignedByte()

            val duration = if (mask and HAS_DURATION != 0) VarInt.readSigned(input) else null
            val attribution = if (mask and HAS_ATTRIBUTION != 0) str() else null
            val opMode = if (mask and HAS_OP_MODE != 0) VarInt.readSigned(input).toInt() else null
            val uidState = if (mask and HAS_UID_STATE != 0) VarInt.readSigned(input).toInt() else null
            val accessResult = if (mask and HAS_RESULT != 0) str() else null

            val flags = VarInt.readSigned(input).toInt()

            result += RawObservation(
                tier = tier,
                sourceEventId = id,
                uid = uid,
                packageName = pkg,
                op = op,
                accessKind = kind,
                timestampMs = timestamp,
                durationMs = duration,
                attributionTag = attribution,
                flags = flags,
                opMode = opMode,
                uidState = uidState,
                accessResult = accessResult,
            )
        }

        return result
    }
}

/**
 * A whole T1 scan: the counters the UI shows, and every observation.
 *
 * [skipped] names each kind of operation Android would not answer for, with the reason. A scan
 * that could not read some operations is reported as exactly that, never as a clean result.
 */
data class SnapshotPayload(
    val capturedAtMs: Long,
    val packageCount: Int,
    val operationCount: Int,
    val accessedOperationCount: Int,
    val rejectedOperationCount: Int,
    val observations: List<RawObservation>,
    val skipped: List<String> = emptyList(),
)

object SnapshotWire {

    private val MAGIC = byteArrayOf(0x50, 0x53, 0x4E, 0x32) // "PSN2"

    private const val MAX_SKIPPED = 1_000

    fun encode(payload: SnapshotPayload): ByteArray {

        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)

        out.write(MAGIC)
        out.writeLong(payload.capturedAtMs)
        out.writeInt(payload.packageCount)
        out.writeInt(payload.operationCount)
        out.writeInt(payload.accessedOperationCount)
        out.writeInt(payload.rejectedOperationCount)

        val skipped = payload.skipped.take(MAX_SKIPPED)
        out.writeInt(skipped.size)
        skipped.forEach { out.writeUTF(it.take(500)) }

        out.write(ObservationWire.encode(payload.observations))
        out.flush()

        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): SnapshotPayload {

        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))

            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw WireFormatException("Not snapshot data")

            val capturedAt = input.readLong()
            val packages = input.readInt()
            val operations = input.readInt()
            val accessed = input.readInt()
            val rejected = input.readInt()

            val skippedCount = input.readInt()
            if (skippedCount < 0 || skippedCount > MAX_SKIPPED) throw WireFormatException("Implausible skipped count $skippedCount")
            val skipped = ArrayList<String>(skippedCount)
            repeat(skippedCount) { skipped += input.readUTF() }

            val observations = ObservationWire.decode(input)
            if (input.available() != 0) throw WireFormatException("Unexpected trailing bytes after the snapshot")

            return SnapshotPayload(capturedAt, packages, operations, accessed, rejected, observations, skipped)

        } catch (e: WireFormatException) {
            throw e
        } catch (e: IOException) {
            throw WireFormatException("Truncated or corrupt snapshot data", e)
        } catch (e: RuntimeException) {
            throw WireFormatException("Corrupt snapshot data", e)
        }
    }
}

internal object VarInt {

    fun writeUnsigned(out: DataOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.writeByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.writeByte(v.toInt())
    }

    fun readUnsigned(input: DataInputStream): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw WireFormatException("Variable length integer is too long")
        }
    }

    /** Zig-zag, so small negative numbers stay small. */
    fun writeSigned(out: DataOutputStream, value: Long) =
        writeUnsigned(out, (value shl 1) xor (value shr 63))

    fun readSigned(input: DataInputStream): Long {
        val raw = readUnsigned(input)
        return (raw ushr 1) xor -(raw and 1)
    }
}

internal object Hex {

    private const val DIGITS = "0123456789abcdef"

    /** 32 bytes for a 64 character lower-case hex string, otherwise null. */
    fun decode32(text: String): ByteArray? {
        if (text.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = DIGITS.indexOf(text[2 * i])
            val lo = DIGITS.indexOf(text[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() shr 4) and 0xF])
            sb.append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
