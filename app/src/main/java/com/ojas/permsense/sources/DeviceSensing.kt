package com.ojas.permsense.sources

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import android.provider.Settings
import com.ojas.permsense.capability.CapabilityManager
import com.ojas.permsense.capability.SourceResult
import com.ojas.permsense.history.AppOpsHistoryParser
import com.ojas.permsense.history.HistoryParseResult
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.processing.UsageContext
import com.ojas.permsense.processing.UsageEventLite
import com.ojas.permsense.processing.UsageKind
import com.ojas.permsense.processing.buildUsageContext
import com.ojas.permsense.remediation.AppState
import com.ojas.permsense.remediation.PrivilegedChannel
import com.ojas.permsense.remediation.RemediationException
import com.ojas.permsense.remediation.RemediationRequest
import com.ojas.permsense.wire.BlobAssembler
import com.ojas.permsense.wire.BlobInfo
import com.ojas.permsense.wire.BlobLimits
import com.ojas.permsense.wire.BlobTransferException
import com.ojas.permsense.wire.ObservationWire
import com.ojas.permsense.wire.SnapshotPayload
import com.ojas.permsense.wire.SnapshotWire
import com.ojas.permsense.wire.WireFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.ZoneId

private const val DEFAULT_LOOKBACK_MS = 24L * 60L * 60L * 1000L
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4101

/**
 * Everything the app can sense, in tiers.
 *
 *  T0  installed apps and their permissions, and what was on screen when (public APIs only)
 *  T1  per app and op, when it was last used or refused (AppOps)
 *  T1+ the OS's own per-access history for camera, microphone and location (dumpsys)
 *  T2  a live stream of accesses, from a collector running as shell
 *
 * T1 and T1+ are read through Shizuku when it is running, or directly by the app once a one-time
 * grant has been made (so they keep working after a reboot, with Shizuku off). T2 needs Shizuku.
 */
class DeviceSensing(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val capabilities = CapabilityManager(appContext)
    private val link = ShizukuLink(appContext)

    private val usageStatsManager: UsageStatsManager? =
        appContext.getSystemService(UsageStatsManager::class.java)

    private val devicePolicyManager: DevicePolicyManager? =
        appContext.getSystemService(DevicePolicyManager::class.java)

    // A raw AppOps dump is every app's access history in plaintext: debuggable builds only.
    private val isDebuggableBuild: Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // ----------------------------------------------------------------------------- T0 gates

    fun hasUsageAccess(): Boolean = capabilities.isUsageAccessGranted()

    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    // ----------------------------------------------------------------------------- T0 inventory

    /** Pass the package names of apps tagged as lending apps so the RBI rules can fire. */
    fun scanInventory(lendingPackages: Set<String> = emptySet()): SourceResult<List<AppInventory>> {

        if (!capabilities.isQueryAllPackagesDeclared()) {
            return SourceResult.Broken(
                "QUERY_ALL_PACKAGES is not available. PermSense cannot guarantee complete app inventory."
            )
        }

        val packages = when (val r = loadInstalledPackages()) {
            is SourceResult.Ok -> r.data
            is SourceResult.NeedsSetup -> return r
            is SourceResult.Broken -> return r
        }

        val accessibilityPackages = enabledPackages(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listenerPackages = enabledPackages("enabled_notification_listeners")
        val adminPackages = activeDeviceAdminPackages()

        val inventory = packages.map { p ->
            val requested = p.requestedPermissions?.toList().orEmpty()
            val flags = p.requestedPermissionsFlags ?: IntArray(0)
            val granted = requested.filterIndexed { index, _ ->
                ((flags.getOrNull(index) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }
            AppInventory(
                packageName = p.packageName,
                versionCode = p.longVersionCodeSafe(),
                requestedPermissions = requested,
                grantedPermissions = granted,
                policyTags = if (p.packageName in lendingPackages) setOf("lending") else emptySet(),
                isAccessibilityEnabled = p.packageName in accessibilityPackages,
                isDeviceAdmin = p.packageName in adminPackages,
                isNotificationListener = p.packageName in listenerPackages,
                isSystemApp = ((p.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0,
                category = p.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED,
                lastUpdateTimeMs = p.lastUpdateTime,
            )
        }.sortedBy { it.packageName.lowercase() }

        return SourceResult.Ok(inventory)
    }

    private fun loadInstalledPackages(): SourceResult<List<PackageInfo>> =
        try {
            SourceResult.Ok(installedPackages(PackageManager.GET_PERMISSIONS))
        } catch (bulk: RuntimeException) {
            // The bulk call can overflow the Binder buffer on phones with many apps.
            try {
                val recovered = packageManager.getInstalledApplications(0).mapNotNull { app ->
                    try {
                        packageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    } catch (_: RuntimeException) {
                        null
                    }
                }
                if (recovered.isEmpty()) SourceResult.Broken("Unable to enumerate installed packages.", bulk)
                else SourceResult.Ok(recovered)
            } catch (fallback: RuntimeException) {
                SourceResult.Broken("Package inventory failed on both paths.", fallback)
            }
        }

    @Suppress("DEPRECATION")
    private fun installedPackages(flags: Int): List<PackageInfo> =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getInstalledPackages(flags)
        }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String, flags: Int): PackageInfo =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }

    private fun PackageInfo.longVersionCodeSafe(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()

    private fun enabledPackages(setting: String): Set<String> {
        val raw = Settings.Secure.getString(appContext.contentResolver, setting) ?: return emptySet()
        return raw.split(':')
            .mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .toSet()
    }

    private fun activeDeviceAdminPackages(): Set<String> =
        try {
            devicePolicyManager?.activeAdmins.orEmpty().map { it.packageName }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: RuntimeException) {
            emptySet()
        }

    // ----------------------------------------------------------------------------- T0 usage context

    fun readUsageEvents(
        fromMs: Long,
        toMs: Long = System.currentTimeMillis(),
    ): SourceResult<List<UsageEventLite>> {

        if (!hasUsageAccess()) return SourceResult.NeedsSetup("Usage Access")
        if (toMs < fromMs) return SourceResult.Broken("The usage events range is invalid.")

        val manager = usageStatsManager ?: return SourceResult.Broken("UsageStatsManager is not available.")

        return try {
            val events = manager.queryEvents(fromMs, toMs)
                ?: return SourceResult.Broken("Android returned no usage events object.")

            val result = ArrayList<UsageEventLite>()
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.PAUSED
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> UsageKind.FOREGROUND_SERVICE_START
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> UsageKind.FOREGROUND_SERVICE_STOP
                    UsageEvents.Event.SCREEN_INTERACTIVE -> UsageKind.SCREEN_ON
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.SCREEN_OFF
                    UsageEvents.Event.KEYGUARD_SHOWN -> UsageKind.KEYGUARD_SHOWN
                    UsageEvents.Event.KEYGUARD_HIDDEN -> UsageKind.KEYGUARD_HIDDEN
                    UsageEvents.Event.DEVICE_STARTUP -> UsageKind.DEVICE_STARTUP
                    UsageEvents.Event.DEVICE_SHUTDOWN -> UsageKind.DEVICE_SHUTDOWN
                    else -> null
                }
                if (kind != null) result += UsageEventLite(kind, event.packageName, event.timeStamp)
            }
            SourceResult.Ok(result)
        } catch (_: SecurityException) {
            SourceResult.NeedsSetup("Usage Access")
        } catch (e: RuntimeException) {
            SourceResult.Broken("The usage events query failed: ${e.brief()}", e)
        }
    }

    fun scanUsageContext(fromMs: Long, toMs: Long = System.currentTimeMillis()): SourceResult<UsageContext> =
        when (val r = readUsageEvents(fromMs, toMs)) {
            is SourceResult.Ok -> SourceResult.Ok(buildUsageContext(r.data, fromMs, toMs))
            is SourceResult.NeedsSetup -> r
            is SourceResult.Broken -> r
        }

    data class SensingSnapshot(
        val inventory: SourceResult<List<AppInventory>>,
        val usage: SourceResult<UsageContext>,
        val collectedAtMs: Long,
    )

    suspend fun scan(
        lookbackMs: Long = DEFAULT_LOOKBACK_MS,
        lendingPackages: Set<String> = emptySet(),
    ): SensingSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        SensingSnapshot(
            inventory = scanInventory(lendingPackages),
            usage = scanUsageContext(now - lookbackMs, now),
            collectedAtMs = now,
        )
    }

    /** The Linux uid an installed app runs as; the ledger and the privileged service identify apps by it. */
    fun uidOf(packageName: String): Int? =
        try {
            packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** UI helper only. Never call this for the ledger (loading labels is slow). */
    fun labelOf(packageName: String): String =
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    // ----------------------------------------------------------------------------- Shizuku gates

    fun isShizukuRunning(): Boolean = link.isRunning()

    fun isShizukuPermissionGranted(): Boolean = link.hasPermission()

    fun requestShizukuPermission() {
        if (!link.isRunning()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    fun releaseShizukuService() = link.release()

    // ----------------------------------------------------------------------------- direct (no Shizuku)

    /** The one-time grant has been made, so the app can read AppOps state itself. */
    fun canReadAppOpsDirectly(): Boolean =
        appContext.checkSelfPermission("android.permission.GET_APP_OPS_STATS") == PackageManager.PERMISSION_GRANTED

    fun canReadHistoryDirectly(): Boolean =
        appContext.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /**
     * Uses Shizuku once to give PermSense the permissions behind T1 and T1+, so they keep working
     * after a reboot with Shizuku stopped. Returns the permissions now held.
     *
     * With [includeHistory] false (T1-only mode) the broad DUMP permission is never requested: it
     * exposes the dumps of many other system services, not just AppOps, and only the OS history
     * backfill needs it.
     */
    suspend fun grantSelfPermissions(includeHistory: Boolean = true): SourceResult<List<String>> = withContext(Dispatchers.IO) {
        val wanted = Protocol.SELF_GRANTABLE
            .filter { includeHistory || it != "android.permission.DUMP" }
            .sorted()
        when (val h = link.handshake()) {
            is SourceResult.NeedsSetup -> return@withContext h
            is SourceResult.Broken -> return@withContext h
            is SourceResult.Ok -> Unit
        }
        link.call(
            Protocol.GRANT_SELF,
            write = { p -> p.writeInt(wanted.size); wanted.forEach { p.writeString(it) } },
            read = { it.createStringArrayList().orEmpty() },
        )
    }

    // ----------------------------------------------------------------------------- T1

    data class T1Snapshot(
        val observations: List<RawObservation>,
        val capturedAtMs: Long,
        val packageCount: Int,
        val operationCount: Int,
        val accessedOperationCount: Int,
        val rejectedOperationCount: Int,
        /** Operations Android would not answer for, each with the reason. Empty on a complete scan. */
        val skipped: List<String> = emptyList(),
    )

    suspend fun scanT1(): SourceResult<T1Snapshot> = withContext(Dispatchers.IO) {

        val payload: SourceResult<SnapshotPayload> =
            when {
                link.hasPermission() -> {
                    val viaService = snapshotViaService()
                    if (viaService is SourceResult.Ok || !canReadAppOpsDirectly()) viaService else snapshotDirect()
                }
                canReadAppOpsDirectly() -> snapshotDirect()
                else -> SourceResult.NeedsSetup("Shizuku, or the one-time GET_APP_OPS_STATS grant")
            }

        when (payload) {
            is SourceResult.Ok -> {
                val s = payload.data
                // Zero packages or operations is never a real phone: a failure, not "no activity".
                if (s.packageCount == 0 || s.operationCount == 0) {
                    SourceResult.Broken(
                        "T1 returned no AppOps data (${s.packageCount} packages, ${s.operationCount} operations). " +
                                "Not treating this as 'no activity'."
                    )
                } else {
                    SourceResult.Ok(
                        T1Snapshot(
                            observations = s.observations,
                            capturedAtMs = s.capturedAtMs,
                            packageCount = s.packageCount,
                            operationCount = s.operationCount,
                            accessedOperationCount = s.accessedOperationCount,
                            rejectedOperationCount = s.rejectedOperationCount,
                            skipped = s.skipped,
                        )
                    )
                }
            }
            is SourceResult.NeedsSetup -> payload
            is SourceResult.Broken -> payload
        }
    }

    private fun snapshotViaService(): SourceResult<SnapshotPayload> {

        when (val h = link.handshake()) {
            is SourceResult.NeedsSetup -> return h
            is SourceResult.Broken -> return h
            is SourceResult.Ok -> Unit
        }

        val info = when (val begun = link.call(Protocol.SNAPSHOT_BEGIN, read = ::readBlobInfo)) {
            is SourceResult.Ok -> begun.data
            is SourceResult.NeedsSetup -> return begun
            is SourceResult.Broken -> return begun
        }

        val bytes = when (val r = readBlob(info)) {
            is SourceResult.Ok -> r.data
            is SourceResult.NeedsSetup -> return r
            is SourceResult.Broken -> return r
        }

        return try {
            SourceResult.Ok(SnapshotWire.decode(bytes))
        } catch (e: WireFormatException) {
            SourceResult.Broken("The T1 snapshot arrived damaged: ${e.brief()}", e)
        }
    }

    private fun snapshotDirect(): SourceResult<SnapshotPayload> {
        if (!HiddenApi.enableForAppOps()) {
            return SourceResult.Broken("Android blocked this app's access to AppOps. Use Shizuku for T1 on this phone.")
        }
        return try {
            SourceResult.Ok(AppOpsSnapshotReader(appContext).scan())
        } catch (e: AppOpsReadException) {
            SourceResult.Broken(e.message ?: "The AppOps read failed.", e)
        } catch (e: SecurityException) {
            SourceResult.NeedsSetup("GET_APP_OPS_STATS")
        } catch (e: Throwable) {
            SourceResult.Broken("The direct AppOps read failed: ${e.brief()}", e)
        }
    }

    // ----------------------------------------------------------------------------- T1+

    data class T1PlusSnapshot(
        val observations: List<RawObservation>,
        val capturedAtMs: Long,
        val rawBytes: Int,
        /** How far back the OS keeps this history on this Android version. */
        val coverageMs: Long,
    )

    suspend fun scanT1Plus(): SourceResult<T1PlusSnapshot> = withContext(Dispatchers.IO) {

        val dump: SourceResult<Pair<String, Long>> =
            when {
                link.hasPermission() -> {
                    val viaService = dumpViaService()
                    if (viaService is SourceResult.Ok || !canReadHistoryDirectly()) viaService else dumpDirect()
                }
                canReadHistoryDirectly() -> dumpDirect()
                else -> SourceResult.NeedsSetup("Shizuku, or the one-time DUMP grant")
            }

        val (text, capturedAt) = when (dump) {
            is SourceResult.Ok -> dump.data
            is SourceResult.NeedsSetup -> return@withContext dump
            is SourceResult.Broken -> return@withContext dump
        }

        // Without DUMP, Android answers with a refusal written into the text, not an error.
        if (text.contains("Permission Denial") || text.contains("permission denied", ignoreCase = true)) {
            return@withContext SourceResult.NeedsSetup("DUMP permission")
        }

        // Keep a real dump in DEBUG builds only: it is needed to check the parser per OS version,
        // but it must never sit in plaintext on a user's phone.
        if (isDebuggableBuild) {
            runCatching {
                java.io.File(appContext.filesDir, "appops_dump_sdk${android.os.Build.VERSION.SDK_INT}.txt").writeText(text)
            }
        }

        when (val parsed = AppOpsHistoryParser.parse(text, ZoneId.systemDefault())) {
            is HistoryParseResult.Parsed -> SourceResult.Ok(
                T1PlusSnapshot(
                    observations = parsed.observations,
                    capturedAtMs = capturedAt,
                    rawBytes = text.length,
                    coverageMs = AppOpsHistoryParser.retentionMs(android.os.Build.VERSION.SDK_INT),
                )
            )
            // Unrecognised is never "no history": it fails closed, with the reason.
            is HistoryParseResult.Unrecognised -> SourceResult.Broken(parsed.reason)
        }
    }

    private fun dumpViaService(): SourceResult<Pair<String, Long>> {

        when (val h = link.handshake()) {
            is SourceResult.NeedsSetup -> return h
            is SourceResult.Broken -> return h
            is SourceResult.Ok -> Unit
        }

        // The reply is the blob id, its size and checksum, then when the dump was taken.
        val info = when (val b = link.call(Protocol.HISTORY_BEGIN, read = ::readBlobInfo)) {
            is SourceResult.Ok -> b.data
            is SourceResult.NeedsSetup -> return b
            is SourceResult.Broken -> return b
        }

        val bytes = when (val r = readBlob(info)) {
            is SourceResult.Ok -> r.data
            is SourceResult.NeedsSetup -> return r
            is SourceResult.Broken -> return r
        }

        return SourceResult.Ok(bytes.toString(Charsets.UTF_8) to System.currentTimeMillis())
    }

    private fun dumpDirect(): SourceResult<Pair<String, Long>> =
        try {
            SourceResult.Ok(AppOpsHistoryDump.capture() to System.currentTimeMillis())
        } catch (e: AppOpsReadException) {
            SourceResult.Broken(e.message ?: "The AppOps history dump failed.", e)
        } catch (e: SecurityException) {
            SourceResult.NeedsSetup("DUMP")
        }

    // ----------------------------------------------------------------------------- blobs

    private fun readBlobInfo(p: Parcel): BlobInfo = BlobInfo(p.readLong(), p.readInt(), p.readLong())

    /** Reads a parked result back in chunks, checks it, and always lets the service forget it. */
    private fun readBlob(info: BlobInfo): SourceResult<ByteArray> {

        val assembler = BlobAssembler(info)

        try {
            while (!assembler.isComplete) {
                val offset = assembler.nextOffset
                val chunk = link.call(
                    Protocol.BLOB_READ,
                    write = { p -> p.writeLong(info.id); p.writeInt(offset); p.writeInt(BlobLimits.MAX_CHUNK_BYTES) },
                    read = { it.createByteArray() ?: ByteArray(0) },
                )
                when (chunk) {
                    is SourceResult.Ok -> assembler.accept(offset, chunk.data)
                    is SourceResult.NeedsSetup -> return chunk
                    is SourceResult.Broken -> return chunk
                }
            }
            return SourceResult.Ok(assembler.result())

        } catch (e: BlobTransferException) {
            return SourceResult.Broken("The transfer from the Shizuku service failed: ${e.message}", e)
        } finally {
            link.call(Protocol.BLOB_RELEASE, write = { it.writeLong(info.id) }, read = { })
        }
    }

    // ----------------------------------------------------------------------------- T2

    data class T2Status(
        val running: Boolean,
        val bufferedEvents: Int,
        val droppedEvents: Long,
        val startedAtMs: Long,
    )

    private fun readT2Status(p: Parcel) = T2Status(p.readInt() != 0, p.readInt(), p.readLong(), p.readLong())

    suspend fun startT2(): SourceResult<T2Status> = withContext(Dispatchers.IO) {
        when (val h = link.handshake()) {
            is SourceResult.NeedsSetup -> return@withContext h
            is SourceResult.Broken -> return@withContext h
            is SourceResult.Ok -> Unit
        }
        link.call(Protocol.T2_START, read = ::readT2Status)
    }

    suspend fun t2Status(): SourceResult<T2Status> = withContext(Dispatchers.IO) {
        link.call(Protocol.T2_STATUS, read = ::readT2Status)
    }

    /**
     * Reads buffered live events without removing them. [skip] is how many the caller has already
     * read this round; nothing leaves the buffer until [acknowledgeT2].
     */
    suspend fun getT2Batch(
        maxEvents: Int = Protocol.T2_BATCH_SIZE,
        skip: Int = 0,
    ): SourceResult<List<RawObservation>> =
        withContext(Dispatchers.IO) {
            val bytes = when (val r = link.call(
                Protocol.T2_BATCH,
                write = {
                    it.writeInt(maxEvents.coerceIn(1, Protocol.T2_BATCH_SIZE))
                    it.writeInt(skip.coerceAtLeast(0))
                },
                read = { it.createByteArray() ?: ByteArray(0) },
            )) {
                is SourceResult.Ok -> r.data
                is SourceResult.NeedsSetup -> return@withContext r
                is SourceResult.Broken -> return@withContext r
            }
            try {
                SourceResult.Ok(ObservationWire.decode(bytes))
            } catch (e: WireFormatException) {
                SourceResult.Broken("A live-collector batch arrived damaged: ${e.brief()}", e)
            }
        }

    suspend fun acknowledgeT2(sourceEventIds: Collection<String>): SourceResult<Unit> =
        withContext(Dispatchers.IO) {
            val ids = sourceEventIds.filter { it.isNotBlank() }.distinct()
            // Chunked: one acknowledgement carries at most T2_MAX_ACK ids.
            for (chunk in ids.chunked(Protocol.T2_MAX_ACK)) {
                when (val r = link.call(
                    Protocol.T2_ACK,
                    write = { p -> p.writeInt(chunk.size); chunk.forEach { p.writeString(it) } },
                    read = { },
                )) {
                    is SourceResult.Ok -> Unit
                    is SourceResult.NeedsSetup -> return@withContext r
                    is SourceResult.Broken -> return@withContext r
                }
            }
            SourceResult.Ok(Unit)
        }

    suspend fun stopT2(): SourceResult<Unit> = withContext(Dispatchers.IO) {
        link.call(Protocol.T2_STOP, read = { })
    }

    // ----------------------------------------------------------------------------- remediation

    /** The channel remediation uses to change a permission or an op mode, via Shizuku. */
    fun remediationChannel(): PrivilegedChannel = ShizukuRemediationChannel()

    private inner class ShizukuRemediationChannel : PrivilegedChannel {

        private fun write(p: Parcel, request: RemediationRequest) = Protocol.writeRequest(p, request)

        override fun readState(request: RemediationRequest): AppState =
            when (val r = link.call(
                Protocol.REMEDIATION_STATE,
                write = { write(it, request) },
                read = { it.readInt() to it.readInt() },
            )) {
                is SourceResult.Ok -> AppState(
                    permissionGranted = when (r.data.first) { 1 -> true; 0 -> false; else -> null },
                    opMode = r.data.second.takeIf { it >= 0 },
                )
                is SourceResult.NeedsSetup -> throw RemediationException("${r.missing} is needed to change permissions from here.")
                is SourceResult.Broken -> throw RemediationException(r.reason, r.cause)
            }

        override fun execute(request: RemediationRequest) {
            when (val r = link.call(Protocol.REMEDIATION_APPLY, write = { write(it, request) }, read = { })) {
                is SourceResult.Ok -> Unit
                is SourceResult.NeedsSetup -> throw RemediationException("${r.missing} is needed to change permissions from here.")
                is SourceResult.Broken -> throw RemediationException(r.reason, r.cause)
            }
        }
    }
}
