package com.ojas.permsense.ledger

/**
 * Exceptions thrown during Ledger persistence and storage operations.
 */
sealed class LedgerStorageException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class KeyInvalidatedException(
        message: String,
        cause: Throwable? = null
    ) : LedgerStorageException(message, cause)

    class CorruptedStorageException(
        message: String,
        cause: Throwable? = null
    ) : LedgerStorageException(message, cause)

    class WriteTransactionException(
        message: String,
        cause: Throwable? = null
    ) : LedgerStorageException(message, cause)
}

/**
 * Result of a retention pruning operation.
 */
data class PruneResult(
    val prunedCount: Int,
    val remainingCount: Long,
    /** Checkpoint at the last removed row. The first remaining row chains from its hash. */
    val anchorCheckpoint: SignedCheckpoint?,
    /** Daily aggregate rows written in place of the removed raw rows. */
    val aggregatesWritten: Int = 0
)

/**
 * Abstract storage interface for durable, tamper-evident Ledger persistence.
 */
interface LedgerStorage {

    /**
     * Atomically appends a batch of sealed records to persistent storage.
     * All records must be committed in a single transaction or rolled back.
     */
    fun insertBatch(records: List<LedgerRecord>)

    /**
     * Atomically inserts a single sealed record.
     */
    fun insertRecord(record: LedgerRecord) {
        insertBatch(listOf(record))
    }

    /**
     * Loads all persisted ledger records in chronological insertion order.
     */
    fun allRows(): List<LedgerRecord>

    /**
     * Retrieves the latest sealed hash in the storage, or null if storage is empty.
     */
    fun latestHash(): String?

    /**
     * Returns total count of sealed records in storage.
     */
    fun recordCount(): Long

    /**
     * Retrieves all records for a specific (uid, package, op) cell.
     */
    fun rowsByCell(uid: Int, packageName: String, op: String): List<LedgerRecord>

    /**
     * Retrieves all records with timestampMs >= [sinceMs].
     */
    fun rowsSince(sinceMs: Long): List<LedgerRecord>

    /**
     * Atomically removes the first [count] rows of the chain and appends [appended] after the
     * remaining rows, in one step. Retention uses this: a prefix is removed (never rows from the
     * middle, which would break the hash links) and its daily aggregates are written back.
     * Checkpoints are kept. Returns the number of rows removed.
     */
    fun replaceLeading(count: Int, appended: List<LedgerRecord>): Int

    /**
     * Persists a signed checkpoint.
     */
    fun insertCheckpoint(checkpoint: SignedCheckpoint)

    /**
     * Returns all stored checkpoints.
     */
    fun allCheckpoints(): List<SignedCheckpoint>

    /**
     * Returns the most recent checkpoint, if any.
     */
    fun latestCheckpoint(): SignedCheckpoint?

    /**
     * Securely wipes all stored records, checkpoints, and files.
     */
    fun wipeAll()
}

/**
 * In-memory implementation of [LedgerStorage], used for volatile caching and lightweight tests.
 */
class InMemoryLedgerStorage : LedgerStorage {

    private val lock = Any()
    private val rows = mutableListOf<LedgerRecord>()
    private val checkpoints = mutableListOf<SignedCheckpoint>()

    override fun insertBatch(records: List<LedgerRecord>) {
        synchronized(lock) {
            rows += records
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
            rows.subList(0, removed).clear()
            rows += appended
            return removed
        }
    }

    override fun insertCheckpoint(checkpoint: SignedCheckpoint) {
        synchronized(lock) {
            checkpoints += checkpoint
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
            rows.clear()
            checkpoints.clear()
        }
    }
}
