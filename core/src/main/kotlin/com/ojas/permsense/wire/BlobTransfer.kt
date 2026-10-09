package com.ojas.permsense.wire

import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Moving a large result across Binder in pieces.
 *
 * The privileged process builds the whole result once, parks it here under an unguessable id, and
 * the app reads it back in chunks small enough for one Binder transaction. The app then checks
 * the length and checksum of what it reassembled, so a truncated or damaged transfer is an
 * error, never a shorter-looking result.
 */
data class BlobInfo(
    val id: Long,
    val totalBytes: Int,
    val crc32: Long,
)

object BlobLimits {

    /** Per transaction. Binder allows about 1 MB shared by every transaction in flight. */
    const val MAX_CHUNK_BYTES = 256 * 1024

    const val MAX_BLOB_BYTES = 64 * 1024 * 1024
}

fun crc32Of(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

class BlobStore(
    private val ttlMs: Long = 120_000L,
    private val maxBlobs: Int = 4,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {

    private class Entry(val bytes: ByteArray, val createdMs: Long)

    private val entries = LinkedHashMap<Long, Entry>()

    @Synchronized
    fun put(bytes: ByteArray): BlobInfo {

        require(bytes.size <= BlobLimits.MAX_BLOB_BYTES) { "Blob is too large: ${bytes.size} bytes" }

        evictExpired()
        while (entries.size >= maxBlobs) {
            entries.remove(entries.keys.first())
        }

        var id: Long
        do {
            id = random.nextLong() and Long.MAX_VALUE
        } while (id == 0L || entries.containsKey(id))

        entries[id] = Entry(bytes, clock())

        return BlobInfo(id, bytes.size, crc32Of(bytes))
    }

    /** A slice of a stored blob, or null when it is unknown or has expired. */
    @Synchronized
    fun read(id: Long, offset: Int, length: Int): ByteArray? {

        evictExpired()
        val entry = entries[id] ?: return null

        if (offset < 0 || length < 0 || offset > entry.bytes.size) return null

        val end = minOf(entry.bytes.size.toLong(), offset.toLong() + minOf(length, BlobLimits.MAX_CHUNK_BYTES))
        return entry.bytes.copyOfRange(offset, end.toInt())
    }

    @Synchronized
    fun release(id: Long) {
        entries.remove(id)
    }

    @Synchronized
    fun count(): Int {
        evictExpired()
        return entries.size
    }

    private fun evictExpired() {
        val now = clock()
        entries.entries.removeAll { now - it.value.createdMs > ttlMs }
    }
}

class BlobTransferException(message: String) : Exception(message)

/** Reassembles chunks in order and refuses a result that is short, long or damaged. */
class BlobAssembler(private val info: BlobInfo) {

    private val buffer = ByteArray(info.totalBytes)
    private var received = 0

    val nextOffset: Int get() = received

    val isComplete: Boolean get() = received == info.totalBytes

    fun accept(offset: Int, chunk: ByteArray) {

        if (offset != received) {
            throw BlobTransferException("Chunk at $offset arrived when $received was expected")
        }
        if (chunk.isEmpty() && !isComplete) {
            throw BlobTransferException("The service returned an empty chunk at $received of ${info.totalBytes}")
        }
        if (received.toLong() + chunk.size > info.totalBytes) {
            throw BlobTransferException("More data arrived than the ${info.totalBytes} bytes announced")
        }

        System.arraycopy(chunk, 0, buffer, received, chunk.size)
        received += chunk.size
    }

    fun result(): ByteArray {

        if (!isComplete) {
            throw BlobTransferException("Transfer incomplete: $received of ${info.totalBytes} bytes")
        }
        if (crc32Of(buffer) != info.crc32) {
            throw BlobTransferException("Transfer damaged: checksum mismatch")
        }
        return buffer
    }
}
