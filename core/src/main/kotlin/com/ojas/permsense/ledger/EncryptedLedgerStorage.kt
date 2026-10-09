package com.ojas.permsense.ledger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * High-performance, crash-resilient persistent storage for the PermSense Ledger.
 * Provides AES-256-GCM authenticated encryption at rest, atomic transactions,
 * and key-invalidation detection.
 */
class EncryptedLedgerStorage(
    private val storageFile: File,
    private val encryptionKey: SecretKey
) : LedgerStorage {

    companion object {
        private val FILE_MAGIC = byteArrayOf(0x50, 0x4C, 0x45, 0x47) // "PLEG"
        private val FRAME_MAGIC = byteArrayOf(0x50, 0x46, 0x52, 0x4D) // "PFRM"
        private const val STORAGE_VERSION = 1

        // "PFRM" magic (4) + type (1) + payload length (4)
        private const val FRAME_HEADER_LENGTH = 9
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val CIPHER_ALGO = "AES/GCM/NoPadding"

        private const val FRAME_TYPE_RECORD: Byte = 0x01
        private const val FRAME_TYPE_CHECKPOINT: Byte = 0x02

        private val KEY_VERIFICATION_PLAINTEXT =
            "PERMSENSE_VERIFIED_KEY_TOKEN_V1".toByteArray(Charsets.UTF_8)
    }

    private val lock = Any()

    // Set while loading: end of the last complete frame, and the file length that was read.
    private var goodEnd = 0L
    private var fileEnd = 0L

    private val rows = mutableListOf<LedgerRecord>()
    private val checkpoints = mutableListOf<SignedCheckpoint>()
    private val random = SecureRandom()

    init {
        synchronized(lock) {
            initOrLoad()
        }
    }

    private fun initOrLoad() {
        // Shorter than a complete file header means the process died while the file was first
        // being created, before any record could exist. Start it again.
        val minimumHeader = FILE_MAGIC.size + 4 + GCM_IV_LENGTH + 4 + 1
        if (!storageFile.exists() || storageFile.length() < minimumHeader) {
            createNewStorageFile()
            return
        }

        RandomAccessFile(storageFile, "r").use { raf ->
            // 1. Verify Magic
            val magic = ByteArray(FILE_MAGIC.size)
            raf.readFully(magic)
            if (!magic.contentEquals(FILE_MAGIC)) {
                throw LedgerStorageException.CorruptedStorageException(
                    "Invalid magic header in storage file: ${storageFile.absolutePath}"
                )
            }

            // 2. Verify Version
            val version = raf.readInt()
            if (version != STORAGE_VERSION) {
                throw LedgerStorageException.CorruptedStorageException(
                    "Unsupported storage version: $version"
                )
            }

            // 3. Verify Key
            val keyIv = ByteArray(GCM_IV_LENGTH)
            raf.readFully(keyIv)
            val tokenLen = raf.readInt()
            val encryptedToken = ByteArray(tokenLen)
            raf.readFully(encryptedToken)

            try {
                val decrypted = decrypt(encryptedToken, keyIv)
                if (!decrypted.contentEquals(KEY_VERIFICATION_PLAINTEXT)) {
                    throw LedgerStorageException.KeyInvalidatedException(
                        "Encryption key verification failed: invalid token"
                    )
                }
            } catch (e: Exception) {
                if (e is LedgerStorageException) throw e
                throw LedgerStorageException.KeyInvalidatedException(
                    "Database encryption key is invalid or has been invalidated",
                    e
                )
            }

            // 4. Read Frames
            val fileLength = raf.length()
            goodEnd = raf.filePointer
            while (raf.filePointer < fileLength) {
                val frameStartPos = raf.filePointer
                if (fileLength - frameStartPos < FRAME_HEADER_LENGTH + GCM_IV_LENGTH) {
                    // Truncated trailing bytes from incomplete write
                    break
                }

                val frameMagic = ByteArray(FRAME_MAGIC.size)
                raf.readFully(frameMagic)
                if (!frameMagic.contentEquals(FRAME_MAGIC)) {
                    // Frame magic mismatch - stop or report corrupt
                    throw LedgerStorageException.CorruptedStorageException(
                        "Corrupted frame magic at offset $frameStartPos"
                    )
                }

                val frameType = raf.readByte()
                val payloadLen = raf.readInt()
                if (payloadLen < 0) {
                    throw LedgerStorageException.CorruptedStorageException(
                        "Corrupted frame length at offset $frameStartPos"
                    )
                }
                if (raf.filePointer + GCM_IV_LENGTH + payloadLen > fileLength) {
                    // Truncated frame from interrupted write
                    break
                }

                val frameIv = ByteArray(GCM_IV_LENGTH)
                raf.readFully(frameIv)

                val ciphertext = ByteArray(payloadLen)
                raf.readFully(ciphertext)

                val plaintext = try {
                    decrypt(ciphertext, frameIv)
                } catch (e: Exception) {
                    throw LedgerStorageException.CorruptedStorageException(
                        "Failed to authenticate frame at offset $frameStartPos",
                        e
                    )
                }

                val dis = DataInputStream(ByteArrayInputStream(plaintext))
                when (frameType) {
                    FRAME_TYPE_RECORD -> {
                        rows.add(readRecordFromStream(dis))
                    }
                    FRAME_TYPE_CHECKPOINT -> {
                        checkpoints.add(readCheckpointFromStream(dis))
                    }
                }
                goodEnd = raf.filePointer
            }
            fileEnd = fileLength
        }

        // A crash mid-write leaves a partial frame at the end. Cut it off now. Otherwise the next
        // append lands after the garbage, and on the following load the garbage is mistaken for a
        // frame header, which silently hides every row written after it.
        if (goodEnd in 1 until fileEnd) {
            RandomAccessFile(storageFile, "rw").use { it.setLength(goodEnd) }
        }
    }

    private fun createNewStorageFile() {
        storageFile.parentFile?.mkdirs()
        RandomAccessFile(storageFile, "rw").use { raf ->
            raf.setLength(0L)
            raf.write(FILE_MAGIC)
            raf.writeInt(STORAGE_VERSION)

            val iv = ByteArray(GCM_IV_LENGTH)
            random.nextBytes(iv)
            val encryptedToken = encrypt(KEY_VERIFICATION_PLAINTEXT, iv)

            raf.write(iv)
            raf.writeInt(encryptedToken.size)
            raf.write(encryptedToken)
            raf.channel.force(true)
        }
        rows.clear()
        checkpoints.clear()
    }

    private fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun insertBatch(records: List<LedgerRecord>) {
        if (records.isEmpty()) return

        synchronized(lock) {
            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)

            val frameDataList = mutableListOf<Pair<Byte, ByteArray>>()
            for (record in records) {
                bos.reset()
                writeRecordToStream(record, dos)
                dos.flush()
                frameDataList.add(FRAME_TYPE_RECORD to bos.toByteArray())
            }

            appendFrames(frameDataList)
            rows.addAll(records)
        }
    }

    override fun insertCheckpoint(checkpoint: SignedCheckpoint) {
        synchronized(lock) {
            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)
            writeCheckpointToStream(checkpoint, dos)
            dos.flush()

            appendFrames(listOf(FRAME_TYPE_CHECKPOINT to bos.toByteArray()))
            checkpoints.add(checkpoint)
        }
    }

    private fun appendFrames(frames: List<Pair<Byte, ByteArray>>) {
        try {
            RandomAccessFile(storageFile, "rw").use { raf ->
                raf.seek(raf.length())

                for ((frameType, plaintext) in frames) {
                    val iv = ByteArray(GCM_IV_LENGTH)
                    random.nextBytes(iv)
                    val ciphertext = encrypt(plaintext, iv)

                    raf.write(FRAME_MAGIC)
                    raf.writeByte(frameType.toInt())
                    raf.writeInt(ciphertext.size)
                    raf.write(iv)
                    raf.write(ciphertext)
                }

                raf.channel.force(true)
            }
        } catch (e: Exception) {
            throw LedgerStorageException.WriteTransactionException(
                "Failed to commit atomic batch to ledger storage",
                e
            )
        }
    }

    override fun allRows(): List<LedgerRecord> {
        synchronized(lock) {
            return rows.toList()
        }
    }

    override fun latestHash(): String? {
        synchronized(lock) {
            return rows.lastOrNull()?.hash
        }
    }

    override fun recordCount(): Long {
        synchronized(lock) {
            return rows.size.toLong()
        }
    }

    override fun rowsByCell(uid: Int, packageName: String, op: String): List<LedgerRecord> {
        synchronized(lock) {
            return rows.filter { it.uid == uid && it.packageName == packageName && it.op == op }
        }
    }

    override fun rowsSince(sinceMs: Long): List<LedgerRecord> {
        synchronized(lock) {
            return rows.filter { it.timestampMs >= sinceMs }
        }
    }

    override fun replaceLeading(count: Int, appended: List<LedgerRecord>): Int {
        synchronized(lock) {
            val removed = count.coerceIn(0, rows.size)
            if (removed == 0 && appended.isEmpty()) return 0

            val remaining = rows.subList(removed, rows.size).toList() + appended

            // Rewrite into a temporary file and swap it in atomically, so a crash leaves either
            // the old ledger or the new one, never a mixture.
            val tempFile = File(storageFile.parentFile, "${storageFile.name}.prune.tmp")
            if (tempFile.exists()) tempFile.delete()   // leftover from an interrupted earlier attempt

            val rewritten = EncryptedLedgerStorage(tempFile, encryptionKey)
            rewritten.insertBatch(remaining)
            for (cp in checkpoints) {
                rewritten.insertCheckpoint(cp)
            }

            Files.move(
                tempFile.toPath(),
                storageFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            rows.clear()
            rows.addAll(remaining)
            return removed
        }
    }

    override fun allCheckpoints(): List<SignedCheckpoint> {
        synchronized(lock) {
            return checkpoints.toList()
        }
    }

    override fun latestCheckpoint(): SignedCheckpoint? {
        synchronized(lock) {
            return checkpoints.lastOrNull()
        }
    }

    override fun wipeAll() {
        synchronized(lock) {
            if (storageFile.exists()) {
                RandomAccessFile(storageFile, "rw").use { raf ->
                    val zeros = ByteArray(1024)
                    var remaining = raf.length()
                    raf.seek(0)
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.channel.force(true)
                }
                storageFile.delete()
            }
            createNewStorageFile()
        }
    }

    // --- Stream serialization helpers ---

    private fun writeRecordToStream(r: LedgerRecord, out: DataOutputStream) {
        out.writeLong(r.timestampMs)
        out.writeInt(r.uid)
        out.writeUTF(r.packageName)
        out.writeLong(r.versionCode)
        out.writeUTF(r.op)
        out.writeUTF(r.accessKind)

        out.writeBoolean(r.durationMs != null)
        if (r.durationMs != null) out.writeLong(r.durationMs)

        out.writeBoolean(r.attributionTag != null)
        if (r.attributionTag != null) out.writeUTF(r.attributionTag)

        out.writeInt(r.flags)
        out.writeUTF(r.visibilityStratum)
        out.writeUTF(r.screenState)
        out.writeUTF(r.sourceTier)
        out.writeInt(r.observationCount)

        out.writeBoolean(r.accessResult != null)
        if (r.accessResult != null) out.writeUTF(r.accessResult)

        out.writeInt(r.sourceEventIds.size)
        for (id in r.sourceEventIds) {
            out.writeUTF(id)
        }

        out.writeUTF(r.recordKind)
        out.writeInt(r.userId)
        out.writeInt(r.schemaVersion)
        out.writeUTF(r.prevHash)
        out.writeUTF(r.hash)
    }

    private fun readRecordFromStream(input: DataInputStream): LedgerRecord {
        val timestampMs = input.readLong()
        val uid = input.readInt()
        val packageName = input.readUTF()
        val versionCode = input.readLong()
        val op = input.readUTF()
        val accessKind = input.readUTF()

        val hasDuration = input.readBoolean()
        val durationMs = if (hasDuration) input.readLong() else null

        val hasAttr = input.readBoolean()
        val attributionTag = if (hasAttr) input.readUTF() else null

        val flags = input.readInt()
        val visibilityStratum = input.readUTF()
        val screenState = input.readUTF()
        val sourceTier = input.readUTF()
        val observationCount = input.readInt()

        val hasResult = input.readBoolean()
        val accessResult = if (hasResult) input.readUTF() else null

        val sourceIdCount = input.readInt()
        val sourceEventIds = ArrayList<String>(sourceIdCount)
        for (i in 0 until sourceIdCount) {
            sourceEventIds.add(input.readUTF())
        }

        val recordKind = input.readUTF()
        val userId = input.readInt()
        val schemaVersion = input.readInt()
        val prevHash = input.readUTF()
        val hash = input.readUTF()

        return LedgerRecord(
            timestampMs = timestampMs,
            uid = uid,
            packageName = packageName,
            versionCode = versionCode,
            op = op,
            accessKind = accessKind,
            durationMs = durationMs,
            attributionTag = attributionTag,
            flags = flags,
            visibilityStratum = visibilityStratum,
            screenState = screenState,
            sourceTier = sourceTier,
            observationCount = observationCount,
            accessResult = accessResult,
            sourceEventIds = sourceEventIds,
            recordKind = recordKind,
            userId = userId,
            schemaVersion = schemaVersion,
            prevHash = prevHash,
            hash = hash
        )
    }

    private fun writeCheckpointToStream(cp: SignedCheckpoint, out: DataOutputStream) {
        out.writeUTF(cp.hash)
        out.writeLong(cp.timestampMs)
        out.writeLong(cp.rowCount)
        out.writeUTF(cp.keyAlias)

        out.writeInt(cp.signatureBytes.size)
        out.write(cp.signatureBytes)

        out.writeBoolean(cp.publicKeyDer != null)
        if (cp.publicKeyDer != null) {
            out.writeInt(cp.publicKeyDer.size)
            out.write(cp.publicKeyDer)
        }
    }

    private fun readCheckpointFromStream(input: DataInputStream): SignedCheckpoint {
        val hash = input.readUTF()
        val timestampMs = input.readLong()
        val rowCount = input.readLong()
        val keyAlias = input.readUTF()

        val sigLen = input.readInt()
        val signatureBytes = ByteArray(sigLen)
        input.readFully(signatureBytes)

        val hasPk = input.readBoolean()
        val pkDer = if (hasPk) {
            val pkLen = input.readInt()
            val pkBytes = ByteArray(pkLen)
            input.readFully(pkBytes)
            pkBytes
        } else null

        return SignedCheckpoint(
            hash = hash,
            signatureBytes = signatureBytes,
            timestampMs = timestampMs,
            rowCount = rowCount,
            keyAlias = keyAlias,
            publicKeyDer = pkDer
        )
    }
}
