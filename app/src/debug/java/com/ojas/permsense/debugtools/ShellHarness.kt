package com.ojas.permsense.debugtools

import android.content.Context
import android.os.Looper
import android.os.Parcel
import com.ojas.permsense.history.AppOpsHistoryParser
import com.ojas.permsense.history.HistoryParseResult
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.remediation.AppState
import com.ojas.permsense.remediation.PrivilegedChannel
import com.ojas.permsense.remediation.RemediationException
import com.ojas.permsense.remediation.RemediationExecutor
import com.ojas.permsense.remediation.RemediationKind
import com.ojas.permsense.remediation.RemediationRequest
import com.ojas.permsense.sources.AppOpsHistoryDump
import com.ojas.permsense.sources.AppOpsSnapshotReader
import com.ojas.permsense.sources.AppOpsUserService
import com.ojas.permsense.sources.Protocol
import com.ojas.permsense.wire.ObservationWire
import java.io.File
import java.time.ZoneId

/**
 * Debug builds only. Runs the privileged half of PermSense as the shell user, WITHOUT Shizuku, so
 * the part that cannot be unit tested (the real AppOps watchers, the real snapshot and history
 * reads, the real permission changes) can be exercised on an emulator or a phone from a computer:
 *
 *   adb shell "CLASSPATH=$(pm path com.ojas.permsense | cut -d: -f2) app_process /system/bin \
 *       com.ojas.permsense.debugtools.ShellHarness <command> ..."
 *
 * It builds the same service object Shizuku would start and talks to it through the same Binder
 * transactions, locally. What it cannot cover is Shizuku's own transport (binding, the permission
 * prompt), which is thin and belongs to Shizuku.
 *
 * Commands
 *   t2 <seconds> <out.jsonl>      watch live accesses for a while, then write every event captured
 *   t1 <out.jsonl>                one T1 snapshot, written as events
 *   history <out.txt>             the OS history dump, saved raw, and how many records the parser reads
 *   remediate <revoke|grant> <package> <permission>
 *                                 change one permission through the executor and print what happened
 *   whoami                        the user id this runs as
 */
object ShellHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args.toList())
        } catch (e: Throwable) {
            System.err.println("FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
        System.exit(0)
    }

    private fun run(args: List<String>) {

        val command = args.firstOrNull() ?: run {
            System.err.println("usage: ShellHarness <t2|t1|history|remediate|whoami> ...")
            System.exit(64)
            return
        }

        if (command == "whoami") {
            println("uid=${android.os.Process.myUid()}")
            return
        }

        val context = systemContext()

        when (command) {
            "introspect" -> introspect()
            "t2" -> t2(context, args[1].toInt(), File(args[2]))
            "t1" -> t1(context, File(args[1]))
            "history" -> history(File(args[1]))
            "remediate" -> remediate(context, args[1], args[2], args[3])
            else -> {
                System.err.println("unknown command $command")
                System.exit(64)
            }
        }
    }

    // ------------------------------------------------------------------ a context without an app

    private fun systemContext(): Context {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }

    // ------------------------------------------------------------------ what this Android offers

    /** Prints the watcher methods and listener interfaces this release of Android actually has. */
    private fun introspect() {
        val manager = Class.forName("android.app.AppOpsManager")

        println("== AppOpsManager methods with 'Watching' or 'PackagesForOps' in the name")
        manager.declaredMethods
            .filter { it.name.contains("Watching") || it.name.contains("PackagesForOps") || it.name == "strOpToOp" || it.name == "opToPublicName" }
            .sortedBy { it.name }
            .forEach { println("  " + it.toGenericString()) }

        for (name in listOf("OnOpNotedListener", "OnOpStartedListener", "OnOpActiveChangedListener", "OnOpChangedListener", "OnOpNotedCallback")) {
            println("== $name")
            try {
                val type = Class.forName("android.app.AppOpsManager\$$name")
                println("  interface=${type.isInterface}")
                type.declaredMethods.forEach { println("  " + it.toGenericString()) }
            } catch (e: ClassNotFoundException) {
                println("  (not present)")
            }
        }
    }

    // ------------------------------------------------------------------ one transaction

    /** Makes one call to the service object, exactly as the app's side does over Binder. */
    private fun call(service: AppOpsUserService, code: Int, write: (Parcel) -> Unit = {}): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        data.writeInterfaceToken(Protocol.DESCRIPTOR)
        write(data)
        check(service.transact(code, data, reply, 0)) { "the service did not handle request $code" }
        reply.readException()
        val status = reply.readInt()
        check(status == Protocol.STATUS_OK) { "service error: ${reply.readString()}" }
        data.recycle()
        return reply
    }

    // ------------------------------------------------------------------ commands

    private fun t2(context: Context, seconds: Int, out: File) {

        val service = AppOpsUserService(context)

        val started = call(service, Protocol.T2_START)
        val running = started.readInt() != 0
        val buffered = started.readInt()
        val dropped = started.readLong()
        val startedAt = started.readLong()
        println("T2 started: running=$running buffered=$buffered dropped=$dropped startedAtMs=$startedAt")
        check(running) { "the collector did not start" }

        Thread.sleep(seconds * 1000L)

        val events = ArrayList<RawObservation>()
        var skip = 0
        while (true) {
            val reply = call(service, Protocol.T2_BATCH) { it.writeInt(Protocol.T2_BATCH_SIZE); it.writeInt(skip) }
            val batch = ObservationWire.decode(reply.createByteArray() ?: ByteArray(0))
            events += batch
            skip += batch.size
            if (batch.size < Protocol.T2_BATCH_SIZE) break
        }

        val status = call(service, Protocol.T2_STATUS)
        println("T2 captured ${events.size} event(s); service reports running=${status.readInt() != 0} buffered=${status.readInt()} dropped=${status.readLong()}")

        write(out, events)
        call(service, Protocol.T2_STOP)
        println("wrote ${out.path}")
    }

    private fun t1(context: Context, out: File) {
        val payload = AppOpsSnapshotReader(context).scan()
        println(
            "T1: ${payload.packageCount} packages, ${payload.operationCount} operations, " +
                    "${payload.accessedOperationCount} accessed, ${payload.rejectedOperationCount} rejected, " +
                    "${payload.skipped.size} skipped"
        )
        payload.skipped.forEach { println("  skipped: $it") }
        write(out, payload.observations)
        println("wrote ${out.path}")
    }

    private fun history(out: File) {
        val text = AppOpsHistoryDump.capture()
        out.writeText(text)
        println("history: ${text.length} characters saved to ${out.path}")
        when (val parsed = AppOpsHistoryParser.parse(text, ZoneId.systemDefault())) {
            is HistoryParseResult.Parsed -> println("history: parser read ${parsed.observations.size} record(s)")
            is HistoryParseResult.Unrecognised -> println("history: parser REFUSED the format: ${parsed.reason}")
        }
    }

    private fun remediate(context: Context, kind: String, packageName: String, permission: String) {

        val service = AppOpsUserService(context)

        val channel = object : PrivilegedChannel {
            override fun readState(request: RemediationRequest): AppState {
                try {
                    val reply = call(service, Protocol.REMEDIATION_STATE) { Protocol.writeRequest(it, request) }
                    val granted = reply.readInt()
                    val mode = reply.readInt()
                    return AppState(
                        permissionGranted = when (granted) { 1 -> true; 0 -> false; else -> null },
                        opMode = mode.takeIf { it >= 0 },
                    )
                } catch (e: IllegalStateException) {
                    throw RemediationException(e.message ?: "read failed", e)
                }
            }

            override fun execute(request: RemediationRequest) {
                try {
                    call(service, Protocol.REMEDIATION_APPLY) { Protocol.writeRequest(it, request) }
                } catch (e: IllegalStateException) {
                    throw RemediationException(e.message ?: "change failed", e)
                }
            }
        }

        val ledger = LedgerWriter()
        val executor = RemediationExecutor(channel, ledger, "com.ojas.permsense", { true })

        val request = RemediationRequest(
            if (kind == "revoke") RemediationKind.REVOKE_PERMISSION else RemediationKind.GRANT_PERMISSION,
            packageName, permission,
        )

        val outcome = executor.execute(request, uid = 0)
        println("outcome: $outcome")
        ledger.all().forEach { println("ledger: ${it.recordKind} ${it.accessKind} ${it.attributionTag} ${it.accessResult}") }
    }

    // ------------------------------------------------------------------ output

    private fun write(out: File, events: List<RawObservation>) {
        out.bufferedWriter().use { w ->
            for (e in events) {
                w.write(
                    "{\"id\":${q(e.sourceEventId)},\"tier\":${q(e.tier.name)},\"uid\":${e.uid},\"pkg\":${q(e.packageName)}," +
                            "\"op\":${q(e.op)},\"kind\":${q(e.accessKind)},\"ts\":${e.timestampMs},\"dur\":${e.durationMs ?: "null"}," +
                            "\"flags\":${e.flags},\"mode\":${e.opMode ?: "null"},\"uidState\":${e.uidState ?: "null"}," +
                            "\"result\":${q(e.accessResult ?: "")}}"
                )
                w.newLine()
            }
        }
    }

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
