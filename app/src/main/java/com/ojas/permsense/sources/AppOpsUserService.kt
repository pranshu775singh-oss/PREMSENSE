package com.ojas.permsense.sources

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Parcel
import android.os.UserHandle
import androidx.annotation.Keep
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.remediation.AppOpModes
import com.ojas.permsense.remediation.CallerPolicy
import com.ojas.permsense.remediation.RemediationKind
import com.ojas.permsense.remediation.RemediationPolicy
import com.ojas.permsense.remediation.RemediationRequest
import com.ojas.permsense.remediation.Validation
import com.ojas.permsense.wire.BlobLimits
import com.ojas.permsense.wire.BlobStore
import com.ojas.permsense.wire.ObservationWire
import com.ojas.permsense.wire.SnapshotWire
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * The part of PermSense that runs as the shell user, started by Shizuku.
 *
 * It is kept small on purpose: it reads AppOps state, watches AppOps events, and makes the two
 * kinds of change the remediation allow-list permits. It is not a shell and cannot be turned into
 * one. Every transaction re-checks who is calling, and every change re-checks the allow-list,
 * because this process must not rely on its caller having been careful.
 */
@Keep
class AppOpsUserService : Binder {

    private var context: Context? = null

    private val blobs = BlobStore()

    private val t2Lock = Any()
    private val t2Buffer = ArrayDeque<RawObservation>()
    private val t2Sequence = AtomicLong(0L)
    private var t2Running = false
    private var t2StartedAtMs = 0L
    private var t2DroppedEvents = 0L
    private var t2Session = ""

    private var notedProxy: Any? = null
    private var startedProxy: Any? = null
    private var activeProxy: Any? = null

    constructor() : super()

    @Keep
    constructor(context: Context) : super() {
        this.context = context
    }

    // ================================================================== dispatch

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {

        val ours = code in Protocol.PING..Protocol.GRANT_SELF
        if (!ours) return super.onTransact(code, data, reply, flags)

        // These are all calls that expect an answer.
        if (reply == null) return false

        if (!callerAllowed()) {
            reply.writeException(SecurityException("caller uid ${getCallingUid()} is not PermSense"))
            return true
        }

        try {
            data.enforceInterface(Protocol.DESCRIPTOR)

            // Each branch finishes computing before it writes anything, so a failure part-way
            // never leaves a half-written reply to be mistaken for a success.
            when (code) {
                Protocol.PING -> ok(reply) { it.writeInt(Protocol.VERSION) }

                Protocol.SNAPSHOT_BEGIN -> {
                    val payload = AppOpsSnapshotReader(requireContext()).scan()
                    val info = blobs.put(SnapshotWire.encode(payload))
                    ok(reply) { it.writeLong(info.id); it.writeInt(info.totalBytes); it.writeLong(info.crc32) }
                }

                Protocol.HISTORY_BEGIN -> {
                    val text = AppOpsHistoryDump.capture()
                    val info = blobs.put(text.toByteArray(Charsets.UTF_8))
                    val at = System.currentTimeMillis()
                    ok(reply) {
                        it.writeLong(info.id); it.writeInt(info.totalBytes); it.writeLong(info.crc32); it.writeLong(at)
                    }
                }

                Protocol.BLOB_READ -> {
                    val id = data.readLong()
                    val offset = data.readInt()
                    val length = data.readInt().coerceIn(0, BlobLimits.MAX_CHUNK_BYTES)
                    val chunk = blobs.read(id, offset, length)
                        ?: throw AppOpsReadException("That transfer is unknown or has expired.")
                    ok(reply) { it.writeByteArray(chunk) }
                }

                Protocol.BLOB_RELEASE -> {
                    blobs.release(data.readLong())
                    ok(reply) { }
                }

                Protocol.T2_START -> {
                    startT2Watchers()
                    val status = t2Status()
                    ok(reply) { status.writeTo(it) }
                }

                Protocol.T2_BATCH -> {
                    val wanted = data.readInt().coerceIn(1, Protocol.T2_BATCH_SIZE)
                    // Events are only removed by an acknowledgement, so a caller reading several
                    // batches in a row says how many it has already seen.
                    val skip = data.readInt().coerceIn(0, Protocol.T2_MAX_BUFFER)
                    val batch = synchronized(t2Lock) { t2Buffer.drop(skip).take(wanted) }
                    val bytes = ObservationWire.encode(batch)
                    ok(reply) { it.writeByteArray(bytes) }
                }

                Protocol.T2_ACK -> {
                    val count = data.readInt().coerceIn(0, Protocol.T2_MAX_ACK)
                    val ids = HashSet<String>(count)
                    repeat(count) { data.readString()?.takeIf { s -> s.isNotBlank() }?.let { s -> ids += s } }
                    acknowledge(ids)
                    ok(reply) { }
                }

                Protocol.T2_STOP -> {
                    stopT2Watchers()
                    ok(reply) { }
                }

                Protocol.T2_STATUS -> {
                    val status = t2Status()
                    ok(reply) { status.writeTo(it) }
                }

                Protocol.REMEDIATION_STATE -> {
                    val request = readRequest(data)
                    val state = remediationState(request)
                    ok(reply) { it.writeInt(state.first); it.writeInt(state.second) }
                }

                Protocol.REMEDIATION_APPLY -> {
                    val request = readRequest(data)
                    applyRemediation(request)
                    ok(reply) { }
                }

                Protocol.GRANT_SELF -> {
                    val count = data.readInt().coerceIn(0, Protocol.MAX_SELF_GRANTS)
                    val wanted = (0 until count).mapNotNull { data.readString() }
                    val granted = grantSelf(wanted)
                    ok(reply) { it.writeStringList(granted) }
                }

                else -> return super.onTransact(code, data, reply, flags)
            }

        } catch (e: Throwable) {
            error(reply, e)
        }

        return true
    }

    private fun ok(reply: Parcel, write: (Parcel) -> Unit) {
        reply.writeNoException()
        reply.writeInt(Protocol.STATUS_OK)
        write(reply)
    }

    private fun error(reply: Parcel, e: Throwable) {
        reply.setDataSize(0)
        reply.setDataPosition(0)
        reply.writeNoException()
        reply.writeInt(Protocol.STATUS_ERROR)
        reply.writeString(describe(e))
    }

    private fun describe(error: Throwable): String {
        val parts = ArrayList<String>(4)
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && parts.size < 6) {
            val message = current.message?.trim().orEmpty()
            parts += if (message.isEmpty()) current.javaClass.simpleName else "${current.javaClass.simpleName}: $message"
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun requireContext(): Context =
        context ?: throw IllegalStateException("The service has no context.")

    // ================================================================== who may call

    private fun callerAllowed(): Boolean {
        val caller = getCallingUid()
        val packages = try {
            requireContext().packageManager.getPackagesForUid(caller)?.toList()
        } catch (_: Throwable) {
            null
        }
        return CallerPolicy.isAllowed(caller, android.os.Process.myUid(), packages, Protocol.SELF_PACKAGE)
    }

    // ================================================================== live collector (T2)

    private data class T2Status(val running: Boolean, val buffered: Int, val dropped: Long, val startedAtMs: Long) {
        fun writeTo(p: Parcel) {
            p.writeInt(if (running) 1 else 0)
            p.writeInt(buffered)
            p.writeLong(dropped)
            p.writeLong(startedAtMs)
        }
    }

    private fun t2Status(): T2Status = synchronized(t2Lock) {
        T2Status(t2Running, t2Buffer.size, t2DroppedEvents, t2StartedAtMs)
    }

    private fun acknowledge(ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(t2Lock) {
            val remaining = ArrayDeque<RawObservation>()
            while (t2Buffer.isNotEmpty()) {
                val observation = t2Buffer.removeFirst()
                if (observation.sourceEventId !in ids) remaining.addLast(observation)
            }
            t2Buffer.addAll(remaining)
        }
    }

    private fun startT2Watchers() {

        synchronized(t2Lock) {
            if (t2Running) return
            t2Running = true
            t2StartedAtMs = System.currentTimeMillis()
            t2Sequence.set(0L)
            t2Session = stableId(
                t2StartedAtMs.toString(),
                System.nanoTime().toString(),
                android.os.Process.myPid().toString(),
            )
        }

        try {
            val appOps = requireContext().getSystemService(AppOpsManager::class.java)
                ?: throw IllegalStateException("AppOpsManager is not available.")

            // Only the ops PermSense judges. Watching every op would flood the buffer with
            // wake locks and notifications and record far more about the user than the product
            // needs.
            val ops = Ops.SENSITIVE.mapNotNull { strOpToOp(it) }.distinct().sorted().toIntArray()
            if (ops.isEmpty()) throw IllegalStateException("None of the watched ops are known to this Android version.")

            val notedType = Class.forName("android.app.AppOpsManager\$OnOpNotedListener")
            val startedType = Class.forName("android.app.AppOpsManager\$OnOpStartedListener")
            val activeType = Class.forName("android.app.AppOpsManager\$OnOpActiveChangedListener")

            val noted = createNotedProxy(notedType)
            val started = createStartedProxy(startedType)
            val active = createActiveProxy(activeType)

            invokeWatcher(appOps, "startWatchingNoted", notedType, ops, noted)
            notedProxy = noted

            invokeWatcher(appOps, "startWatchingStarted", startedType, ops, started)
            startedProxy = started

            invokeWatcher(appOps, "startWatchingActive", activeType, ops, active)
            activeProxy = active

        } catch (e: Throwable) {
            try {
                stopT2Watchers()
            } catch (_: Throwable) {
            }
            synchronized(t2Lock) {
                t2Running = false
                t2StartedAtMs = 0L
            }
            throw IllegalStateException("The live collector could not register its watchers.", e)
        }
    }

    private fun stopT2Watchers() {

        val appOps = context?.getSystemService(AppOpsManager::class.java)

        if (appOps != null) {
            val notedType = Class.forName("android.app.AppOpsManager\$OnOpNotedListener")
            val startedType = Class.forName("android.app.AppOpsManager\$OnOpStartedListener")
            val activeType = Class.forName("android.app.AppOpsManager\$OnOpActiveChangedListener")

            notedProxy?.let { runCatching { invokeStopWatcher(appOps, "stopWatchingNoted", notedType, it) } }
            startedProxy?.let { runCatching { invokeStopWatcher(appOps, "stopWatchingStarted", startedType, it) } }
            activeProxy?.let { runCatching { invokeStopWatcher(appOps, "stopWatchingActive", activeType, it) } }
        }

        synchronized(t2Lock) {
            t2Running = false
            t2StartedAtMs = 0L
            notedProxy = null
            startedProxy = null
            activeProxy = null
            t2Session = ""
            // Stopping means stopping: nothing watched earlier is kept for a later start.
            t2Buffer.clear()
        }
    }

    private fun invokeWatcher(appOps: AppOpsManager, name: String, listenerType: Class<*>, ops: IntArray, listener: Any) {
        val method = appOps.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == IntArray::class.java && it.parameterTypes[1] == listenerType
        } ?: throw NoSuchMethodException("$name(int[], ${listenerType.simpleName}) does not exist on this Android version")
        method.isAccessible = true
        method.invoke(appOps, ops, listener)
    }

    private fun invokeStopWatcher(appOps: AppOpsManager, name: String, listenerType: Class<*>, listener: Any) {
        val method = appOps.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == listenerType
        } ?: throw NoSuchMethodException("$name(${listenerType.simpleName})")
        method.isAccessible = true
        method.invoke(appOps, listener)
    }

    private fun proxyFor(listenerType: Class<*>, label: String, handler: (name: String, args: Array<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(
            listenerType.classLoader ?: javaClass.classLoader,
            arrayOf(listenerType),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "toString" -> label
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> {
                        try {
                            handler(method.name, args ?: emptyArray())
                        } catch (_: Throwable) {
                            // A callback must never throw into the system server.
                        }
                        null
                    }
                }
            },
        )

    private fun createNotedProxy(type: Class<*>): Any = proxyFor(type, "PermSenseNotedListener") { name, args ->
        if (name != "onOpNoted" || args.size < 6) return@proxyFor
        val opCode = opCode(args[0]) ?: return@proxyFor
        val uid = args[1] as? Int ?: return@proxyFor
        val packageName = args[2] as? String ?: return@proxyFor
        val tag = args[3] as? String

        // The trailing ints are (..., flags, mode); the leading shape differs between releases.
        val ints = args.drop(4).filterIsInstance<Int>()
        val flags = if (ints.size >= 2) ints[ints.lastIndex - 1] else 0
        val mode = ints.lastOrNull() ?: 0

        record(opCode, uid, packageName, tag, if (mode == MODE_ALLOWED) "noted" else "rejected", flags, mode)
    }

    private fun createStartedProxy(type: Class<*>): Any = proxyFor(type, "PermSenseStartedListener") { name, args ->
        if (name != "onOpStarted" || args.size < 6) return@proxyFor
        val opCode = opCode(args[0]) ?: return@proxyFor
        val uid = args[1] as? Int ?: return@proxyFor
        val packageName = args[2] as? String ?: return@proxyFor
        val tag = args[3] as? String

        val (flags, mode) = when {
            args.size == 6 || args.size == 9 -> (args[4] as? Int ?: 0) to (args[5] as? Int ?: 0)
            args.size >= 10 -> (args[5] as? Int ?: 0) to (args[6] as? Int ?: 0)
            else -> return@proxyFor
        }

        record(opCode, uid, packageName, tag, if (mode == MODE_ALLOWED) "started" else "rejected", flags, mode)
    }

    private fun createActiveProxy(type: Class<*>): Any = proxyFor(type, "PermSenseActiveListener") { name, args ->
        if (name != "onOpActiveChanged" || args.size < 4) return@proxyFor
        val opCode = opCode(args[0]) ?: return@proxyFor
        val uid = args[1] as? Int ?: return@proxyFor
        val packageName = args[2] as? String ?: return@proxyFor
        val tag = args[3] as? String

        val activeIndex = args.indexOfFirst { it is Boolean }
        if (activeIndex < 0) return@proxyFor
        val active = args[activeIndex] as Boolean
        val flags = args.drop(activeIndex + 1).filterIsInstance<Int>().firstOrNull() ?: 0

        record(opCode, uid, packageName, tag, if (active) "active" else "finished", flags, MODE_ALLOWED)
    }

    private fun opCode(value: Any?): Int? =
        when (value) {
            is Int -> value
            is String -> strOpToOp(value)
            else -> null
        }

    private fun record(opCode: Int, uid: Int, packageName: String, tag: String?, kind: String, flags: Int, mode: Int) {

        if (packageName.isBlank()) return

        val session = synchronized(t2Lock) {
            if (!t2Running) return
            t2Session
        }

        val timestampMs = System.currentTimeMillis()
        val sequence = t2Sequence.incrementAndGet()
        val op = opName(opCode)

        val observation = RawObservation(
            tier = Tier.T2,
            sourceEventId = stableId(
                session, sequence.toString(), opCode.toString(), uid.toString(),
                packageName, tag.orEmpty(), kind, timestampMs.toString(),
            ),
            uid = uid,
            packageName = packageName,
            op = op,
            accessKind = kind,
            timestampMs = timestampMs,
            durationMs = null,
            attributionTag = tag,
            flags = flags,
            opMode = mode,
            uidState = null,
            accessResult = if (mode == MODE_ALLOWED) "ALLOWED" else "REJECTED",
        )

        synchronized(t2Lock) {
            if (!t2Running) return
            if (t2Buffer.size >= Protocol.T2_MAX_BUFFER) {
                t2Buffer.removeFirst()
                t2DroppedEvents++       // the app turns a change in this counter into a gap record
            }
            t2Buffer.addLast(observation)
        }
    }

    private fun strOpToOp(value: String): Int? =
        try {
            val m = AppOpsManager::class.java.getDeclaredMethod("strOpToOp", String::class.java)
            m.isAccessible = true
            m.invoke(null, value) as? Int
        } catch (_: Throwable) {
            null
        }

    private fun opName(opCode: Int): String {
        val intType = Int::class.javaPrimitiveType ?: Int::class.java
        try {
            val m = AppOpsManager::class.java.getDeclaredMethod("opToPublicName", intType)
            m.isAccessible = true
            (m.invoke(null, opCode) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            val m = AppOpsManager::class.java.getDeclaredMethod("opToName", intType)
            m.isAccessible = true
            (m.invoke(null, opCode) as? String)?.takeIf { it.isNotBlank() }?.let { return "android:${it.lowercase()}" }
        } catch (_: Throwable) {
        }
        return "android:unknown_$opCode"
    }

    // ================================================================== remediation

    private fun readRequest(data: Parcel): RemediationRequest {
        val kind = RemediationKind.values().getOrNull(data.readInt())
            ?: throw IllegalArgumentException("Unknown kind of change.")
        val pkg = data.readString().orEmpty()
        val target = data.readString().orEmpty()
        val mode = data.readInt().takeIf { it >= 0 }
        val user = data.readInt()
        return RemediationRequest(kind, pkg, target, mode, user)
    }

    /** Allow-list check, run again in this process. A caller that skipped it still cannot get past. */
    private fun checked(request: RemediationRequest): RemediationRequest {
        when (val v = RemediationPolicy.validate(request, Protocol.SELF_PACKAGE, ::isRemediable)) {
            is Validation.Rejected -> throw SecurityException("Not allowed: ${v.reason}")
            Validation.Ok -> Unit
        }
        return request
    }

    private fun isRemediable(packageName: String): Boolean =
        try {
            val info = requireContext().packageManager.getApplicationInfo(packageName, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** (permission state, op mode); -1 means not asked or not applicable. */
    private fun remediationState(raw: RemediationRequest): Pair<Int, Int> {

        val request = checked(raw)
        val ctx = requireContext()
        val pm = ctx.packageManager

        if (request.kind == RemediationKind.SET_OP_MODE) {
            val uid = pm.getApplicationInfo(request.packageName, 0).uid
            val appOps = ctx.getSystemService(AppOpsManager::class.java)
                ?: throw IllegalStateException("AppOpsManager is not available.")
            return -1 to appOps.unsafeCheckOpNoThrow(request.target, uid, request.packageName)
        }

        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(request.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(request.packageName, PackageManager.GET_PERMISSIONS)
        }
        val index = info.requestedPermissions?.indexOf(request.target) ?: -1
        if (index < 0) return -1 to -1
        val granted = (info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and
                android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        return (if (granted) 1 else 0) to -1
    }

    private fun applyRemediation(raw: RemediationRequest) {

        val request = checked(raw)
        val ctx = requireContext()
        val pm = ctx.packageManager
        val user = userHandle(request.userId)

        when (request.kind) {
            RemediationKind.REVOKE_PERMISSION -> permissionCall(pm, "revokeRuntimePermission", request.packageName, request.target, user)
            RemediationKind.GRANT_PERMISSION -> permissionCall(pm, "grantRuntimePermission", request.packageName, request.target, user)
            RemediationKind.SET_OP_MODE -> {
                val uid = pm.getApplicationInfo(request.packageName, 0).uid
                val appOps = ctx.getSystemService(AppOpsManager::class.java)
                    ?: throw IllegalStateException("AppOpsManager is not available.")
                val code = strOpToOp(request.target) ?: throw IllegalArgumentException("Unknown op ${request.target}.")
                val mode = request.mode ?: throw IllegalArgumentException("No mode given.")
                if (mode !in setOf(AppOpModes.ALLOWED, AppOpModes.IGNORED, AppOpModes.DEFAULT, AppOpModes.FOREGROUND)) {
                    throw SecurityException("Mode $mode is not allowed.")
                }
                val setMode = AppOpsManager::class.java.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType,
                )
                setMode.invoke(appOps, code, uid, request.packageName, mode)
            }
        }
    }

    private fun permissionCall(pm: PackageManager, method: String, pkg: String, permission: String, user: UserHandle) {
        PackageManager::class.java
            .getMethod(method, String::class.java, String::class.java, UserHandle::class.java)
            .invoke(pm, pkg, permission, user)
    }

    private fun userHandle(userId: Int): UserHandle =
        UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle

    /**
     * Grants PermSense itself the few permissions behind T1 and T1+, once, so those tiers keep
     * working after Shizuku is gone. Only this package, only these permissions.
     */
    private fun grantSelf(wanted: List<String>): List<String> {

        val ctx = requireContext()
        val pm = ctx.packageManager

        for (p in wanted) {
            if (p !in Protocol.SELF_GRANTABLE) throw SecurityException("$p cannot be granted this way.")
        }

        val granted = ArrayList<String>()
        for (p in wanted.distinct()) {
            permissionCall(pm, "grantRuntimePermission", Protocol.SELF_PACKAGE, p, userHandle(0))
            // Read it back; do not trust the call.
            if (pm.checkPermission(p, Protocol.SELF_PACKAGE) == PackageManager.PERMISSION_GRANTED) granted += p
        }
        return granted
    }

    private companion object {
        const val MODE_ALLOWED = 0
    }
}
