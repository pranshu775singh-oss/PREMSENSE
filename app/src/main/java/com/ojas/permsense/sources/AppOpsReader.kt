package com.ojas.permsense.sources

import android.app.AppOpsManager
import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import com.ojas.permsense.ledger.sha256Hex
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.UidStates
import com.ojas.permsense.wire.SnapshotPayload
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Isolates the one fragile thing this app depends on: reading another app's AppOps state through
 * hidden framework methods.
 *
 * Inside the Shizuku process there are no hidden-API restrictions, so nothing is needed. In the
 * app's own process the restrictions apply and a bypass library is used. Android can break a
 * bypass in any release, so every use goes through here and failure is reported, never assumed.
 */
internal object HiddenApi {

    @Volatile
    private var attempted = false

    @Volatile
    private var available = false

    /** Asks for access to AppOpsManager's hidden members. True when they can now be used. */
    @Synchronized
    fun enableForAppOps(): Boolean {
        if (attempted) return available
        attempted = true
        available = try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/AppOpsManager")
        } catch (_: Throwable) {
            false
        }
        return available
    }
}

/** A small, stable name for what went wrong, so the UI can say something useful. */
internal class AppOpsReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the AppOps state of the operations PermSense judges: for each (app, op), when it was last
 * used and last refused.
 *
 * Shared by the privileged service and by the app's own process (when the one-time grant of
 * GET_APP_OPS_STATS has been made), so there is a single implementation to keep correct.
 *
 * One query per operation, never one for everything. The system answers a query in a single
 * Binder reply, and Binder refuses any reply over about 1 MB. Asking for every operation of every
 * app on a phone with a few hundred apps produces a reply well past that, and fails with a
 * TransactionTooLargeException. A reply for a single operation is a small fraction of the limit.
 */
internal class AppOpsSnapshotReader(private val context: Context) {

    fun scan(nowMs: Long = System.currentTimeMillis()): SnapshotPayload {

        val appOps = context.getSystemService(AppOpsManager::class.java)
            ?: throw AppOpsReadException("AppOpsManager is not available.")

        val audited = try {
            auditedOps()
        } catch (e: Exception) {
            throw AppOpsReadException("The operation codes could not be looked up: ${e.brief()}", e)
        }
        if (audited.isEmpty()) {
            throw AppOpsReadException("None of the audited operations exist on this Android version.")
        }

        val query = packagesForOpsMethod()
        val reader = EntryReader()
        val probe = UidStateProbe(reader)

        val observations = ArrayList<RawObservation>()
        val packages = HashSet<String>()
        val skipped = ArrayList<String>()
        var operationCount = 0
        var accessedCount = 0
        var rejectedCount = 0

        for ((name, code) in audited) {

            val entries = try {
                query(appOps, code)
            } catch (e: SecurityException) {
                // Not a per-operation problem: the caller lacks GET_APP_OPS_STATS.
                throw e
            } catch (e: Exception) {
                skipped += "$name: ${e.brief()}"
                continue
            }

            for (packageEntry in entries) {

                val packageName = reader.get(packageEntry, "getPackageName") as? String ?: continue
                val uid = reader.get(packageEntry, "getUid") as? Int ?: continue
                packages += packageName

                val ops = (reader.get(packageEntry, "getOps") as? Iterable<*>)?.filterNotNull().orEmpty()

                for (opEntry in ops) {

                    val op = reader.get(opEntry, "getOpStr") as? String ?: continue
                    if (op != name) continue

                    operationCount++

                    val lastAccess = reader.lastAccess(opEntry)
                    val lastReject = reader.lastReject(opEntry)
                    val duration = reader.lastDuration(opEntry).takeIf { it > 0L }
                    val mode = reader.get(opEntry, "getMode") as? Int ?: 0

                    if (lastAccess > 0L) {
                        accessedCount++
                        observations += RawObservation(
                            tier = Tier.T1,
                            sourceEventId = stableId(uid.toString(), packageName, op, "access", lastAccess.toString()),
                            uid = uid,
                            packageName = packageName,
                            op = op,
                            accessKind = "presence",
                            timestampMs = lastAccess,
                            durationMs = duration,
                            attributionTag = null,
                            flags = 0,
                            opMode = mode,
                            // Which process state the app was in at that last access, when the OS says.
                            uidState = probe.stateOf(opEntry, lastAccess),
                            // The last SUCCESSFUL access; rejections have their own time. The op's
                            // CURRENT mode says nothing about how that past access ended.
                            accessResult = "ALLOWED",
                        )
                    }

                    if (lastReject > 0L) {
                        rejectedCount++
                        observations += RawObservation(
                            tier = Tier.T1,
                            sourceEventId = stableId(uid.toString(), packageName, op, "reject", lastReject.toString()),
                            uid = uid,
                            packageName = packageName,
                            op = op,
                            accessKind = "rejected",
                            timestampMs = lastReject,
                            durationMs = null,
                            attributionTag = null,
                            flags = 0,
                            opMode = mode,
                            uidState = null,
                            accessResult = "REJECTED",
                        )
                    }
                }
            }
        }

        // Nothing at all is how Android refuses without saying so. It is never "no activity".
        if (operationCount == 0) {
            throw AppOpsReadException(
                if (skipped.isNotEmpty()) {
                    "Every AppOps query failed: ${skipped.take(3).joinToString("; ")}" +
                            if (skipped.size > 3) " (and ${skipped.size - 3} more)" else ""
                } else {
                    "AppOps returned nothing for any of the ${audited.size} audited operations. " +
                            "Android refuses silently when the caller lacks GET_APP_OPS_STATS, so this is not " +
                            "reported as 'no activity'."
                }
            )
        }

        return SnapshotPayload(
            capturedAtMs = nowMs,
            packageCount = packages.size,
            operationCount = operationCount,
            accessedOperationCount = accessedCount,
            rejectedOperationCount = rejectedCount,
            observations = observations.sortedWith(compareBy({ it.timestampMs }, { it.sourceEventId })),
            skipped = skipped,
        )
    }

    /** The audited operations this Android version knows, as (public name, internal code). */
    private fun auditedOps(): List<Pair<String, Int>> {
        val strOpToOp = AppOpsManager::class.java.getDeclaredMethod("strOpToOp", String::class.java)
        strOpToOp.isAccessible = true
        return Ops.SENSITIVE.sorted().mapNotNull { name ->
            try {
                name to (strOpToOp.invoke(null, name) as Int)
            } catch (_: Exception) {
                null       // an operation this release of Android does not have
            }
        }
    }

    /**
     * `getPackagesForOps(int[])`. The String[] overload dereferences its argument, so the int[] one
     * is used. Exceptions thrown inside the system call are unwrapped, so a SecurityException
     * reads as one.
     */
    private fun packagesForOpsMethod(): (AppOpsManager, Int) -> List<Any> {
        val method = AppOpsManager::class.java.getDeclaredMethod("getPackagesForOps", IntArray::class.java)
        method.isAccessible = true
        return { appOps, code ->
            val result = try {
                method.invoke(appOps, intArrayOf(code))
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
            (result as? Iterable<*>)?.filterNotNull().orEmpty()
        }
    }

    /** Reflection on the entry classes, with each method looked up once. */
    private class EntryReader {

        private val cache = HashMap<String, Method?>()

        fun get(target: Any, name: String, vararg args: Int): Any? {
            val key = "${target.javaClass.name}#$name/${args.size}"
            val method = cache.getOrPut(key) { find(target.javaClass, name, args.size) } ?: return null
            return try {
                when (args.size) {
                    0 -> method.invoke(target)
                    1 -> method.invoke(target, args[0])
                    else -> method.invoke(target, args[0], args[1], args[2])
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }

        private fun find(type: Class<*>, name: String, intArgs: Int): Method? {
            val params = Array<Class<*>>(intArgs) { Int::class.javaPrimitiveType!! }
            return try {
                type.getMethod(name, *params)
            } catch (_: NoSuchMethodException) {
                try {
                    type.getDeclaredMethod(name, *params).also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    null
                }
            }
        }

        // The flag-taking forms are public from Android 11; the plain ones cover Android 10.
        fun lastAccess(op: Any): Long =
            (get(op, "getLastAccessTime", OP_FLAGS_ALL) as? Long)?.takeIf { it > 0L }
                ?: (get(op, "getTime") as? Long ?: 0L)

        fun lastReject(op: Any): Long =
            (get(op, "getLastRejectTime", OP_FLAGS_ALL) as? Long)?.takeIf { it > 0L }
                ?: (get(op, "getRejectTime") as? Long ?: 0L)

        fun lastDuration(op: Any): Long =
            (get(op, "getLastDuration", OP_FLAGS_ALL) as? Long)?.takeIf { it > 0L }
                ?: (get(op, "getDuration") as? Long ?: 0L)
    }

    /**
     * Works out which process state an app was in at its last access by asking, for each state in
     * turn, when the last access in that state was. Best effort: the method is hidden and its shape
     * has changed between releases, so failure just means no state is recorded.
     */
    private class UidStateProbe(private val reader: EntryReader) {

        private val states = listOf(
            UidStates.PERSISTENT, UidStates.TOP, UidStates.FOREGROUND_SERVICE_LOCATION,
            UidStates.FOREGROUND_SERVICE, UidStates.FOREGROUND, UidStates.BACKGROUND, UidStates.CACHED,
        )

        private var broken = false

        fun stateOf(opEntry: Any, lastAccess: Long): Int? {
            if (broken) return null
            return try {
                states.firstOrNull { (reader.get(opEntry, "getLastAccessTime", it, it, OP_FLAGS_ALL) as? Long) == lastAccess }
            } catch (_: Throwable) {
                broken = true
                null
            }
        }
    }

    private companion object {
        /** AppOpsManager.OP_FLAGS_ALL: self, trusted and untrusted proxy, trusted and untrusted proxied. */
        const val OP_FLAGS_ALL = 0x1F
    }
}

/** `dumpsys appops --history --include-discrete`, captured in-process. */
internal object AppOpsHistoryDump {

    private const val TIMEOUT_MS = 30_000L

    /**
     * @throws AppOpsReadException when the dump cannot be captured
     */
    fun capture(): String {

        val pipe = ParcelFileDescriptor.createPipe()
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val output = pipe[1]

        var dumpError: Throwable? = null

        val thread = Thread {
            try {
                // "0" is the limit on discrete entries per op; 0 means no limit.
                Debug.dumpService("appops", output.fileDescriptor, arrayOf("--history", "--include-discrete", "0"))
            } catch (e: Throwable) {
                dumpError = e
            } finally {
                try {
                    output.close()
                } catch (_: Throwable) {
                }
            }
        }
        thread.start()

        val text = try {
            input.bufferedReader().use { it.readText() }
        } finally {
            try {
                input.close()
            } catch (_: Throwable) {
            }
        }

        thread.join(TIMEOUT_MS)
        if (thread.isAlive) throw AppOpsReadException("The AppOps history dump timed out.")
        dumpError?.let { throw AppOpsReadException("The AppOps history dump failed.", it) }

        return text
    }
}

internal fun stableId(vararg values: String): String =
    sha256Hex(values.joinToString("\u001F").toByteArray(Charsets.UTF_8))
