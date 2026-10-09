package com.ojas.permsense.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ojas.permsense.setup.Oem
import com.ojas.permsense.sources.DeviceSensing

enum class TierState { WORKING, NEEDS_SETUP, NOT_USED, FAILED }

/** One row of the capability matrix: a source of information, and whether it works on this phone right now. */
data class TierCheck(
    /** T0 inventory, T0 usage, T1, T1+, T2, or Fix. */
    val id: String,
    val state: TierState,
    /** The real reason, as reported: what it read, what is missing, or why it failed. */
    val detail: String,
)

/**
 * What this phone can do for PermSense, found by trying it, not by guessing from the Android
 * version. Each tier is actually exercised, so "works" means a scan just succeeded.
 */
data class CapabilityMatrix(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val oem: Oem,
    val checks: List<TierCheck>,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuAllowed: Boolean,
) {
    fun check(id: String): TierCheck? = checks.firstOrNull { it.id == id }

    fun works(id: String): Boolean = check(id)?.state == TierState.WORKING

    /** True when at least the zero-setup tier gives a useful picture. */
    val usable: Boolean get() = works("T0 inventory")

    /**
     * One line for the device-by-tier matrix the build plan asks for, easy to paste into a table:
     * `Pixel 8 | Android 15 (SDK 35) | T0 inventory PASS | T0 usage PASS | T1 PASS | ...`
     */
    fun matrixRow(): String =
        (listOf("$manufacturer $model", "Android $androidRelease (SDK $sdk)") +
                checks.map { "${it.id} ${label(it.state)}" })
            .joinToString(" | ")

    /** The same, with each reason, for a bug report. Contains no app names and no personal data. */
    fun report(): String = buildString {
        appendLine("$manufacturer $model, Android $androidRelease (SDK $sdk)")
        appendLine("Shizuku: ${if (!shizukuInstalled) "not installed" else if (!shizukuRunning) "installed, not running" else if (!shizukuAllowed) "running, not allowed" else "running and allowed"}")
        checks.forEach { appendLine("${it.id}: ${label(it.state)}  ${it.detail}") }
    }

    private fun label(state: TierState): String =
        when (state) {
            TierState.WORKING -> "PASS"
            TierState.NEEDS_SETUP -> "SETUP"
            TierState.NOT_USED -> "OFF"
            TierState.FAILED -> "FAIL"
        }
}

class CapabilityProbe(
    context: Context,
    private val sensing: DeviceSensing,
) {

    private val appContext = context.applicationContext

    suspend fun run(t1Only: Boolean, liveCollectorEnabled: Boolean = true): CapabilityMatrix {

        val checks = ArrayList<TierCheck>()

        // ---- T0
        checks += when (val inventory = sensing.scanInventory()) {
            is SourceResult.Ok -> TierCheck("T0 inventory", TierState.WORKING, "${inventory.data.size} apps listed")
            is SourceResult.NeedsSetup -> TierCheck("T0 inventory", TierState.NEEDS_SETUP, inventory.missing)
            is SourceResult.Broken -> TierCheck("T0 inventory", TierState.FAILED, inventory.reason)
        }

        checks += if (sensing.hasUsageAccess()) {
            TierCheck("T0 usage", TierState.WORKING, "usage access granted")
        } else {
            TierCheck("T0 usage", TierState.NEEDS_SETUP, "Usage access is off in Settings")
        }

        // ---- T1
        checks += when (val t1 = sensing.scanT1()) {
            is SourceResult.Ok -> TierCheck(
                "T1", TierState.WORKING,
                "${t1.data.packageCount} apps, ${t1.data.operationCount} operations" +
                        (if (t1.data.skipped.isNotEmpty()) ", ${t1.data.skipped.size} not readable" else "") +
                        (if (sensing.isShizukuPermissionGranted()) ", via Shizuku" else ", read directly"),
            )
            is SourceResult.NeedsSetup -> TierCheck("T1", TierState.NEEDS_SETUP, t1.missing)
            is SourceResult.Broken -> TierCheck("T1", TierState.FAILED, t1.reason)
        }

        // ---- T1+
        checks += if (t1Only) {
            TierCheck("T1+", TierState.NOT_USED, "T1-only mode: the broad DUMP permission is never used")
        } else when (val h = sensing.scanT1Plus()) {
            is SourceResult.Ok -> TierCheck("T1+", TierState.WORKING, "${h.data.observations.size} records from the OS history")
            is SourceResult.NeedsSetup -> TierCheck("T1+", TierState.NEEDS_SETUP, h.missing)
            is SourceResult.Broken -> TierCheck("T1+", TierState.FAILED, h.reason)
        }

        // ---- T2 and the fix channel share one privileged process
        val installed = isShizukuInstalled(appContext)
        val running = sensing.isShizukuRunning()
        val allowed = sensing.isShizukuPermissionGranted()

        checks += when {
            !liveCollectorEnabled -> TierCheck("T2", TierState.NOT_USED, "Switched off in settings")
            !allowed -> TierCheck("T2", TierState.NEEDS_SETUP, if (!running) "Shizuku is not running" else "Shizuku has not been allowed")
            else -> when (val t2 = sensing.startT2()) {
                is SourceResult.Ok -> TierCheck(
                    "T2", if (t2.data.running) TierState.WORKING else TierState.FAILED,
                    if (t2.data.running) "watching; ${t2.data.bufferedEvents} events waiting" else "the collector did not start",
                )
                is SourceResult.NeedsSetup -> TierCheck("T2", TierState.NEEDS_SETUP, t2.missing)
                is SourceResult.Broken -> TierCheck("T2", TierState.FAILED, t2.reason)
            }
        }

        checks += if (allowed) {
            TierCheck("Fix", TierState.WORKING, "permissions can be removed and restored through Shizuku")
        } else {
            TierCheck("Fix", TierState.NEEDS_SETUP, "Needs Shizuku; otherwise PermSense opens the app's settings page")
        }

        return CapabilityMatrix(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            oem = Oem.fromManufacturer(Build.MANUFACTURER, Build.BRAND),
            checks = checks,
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuAllowed = allowed,
        )
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun isShizukuInstalled(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: RuntimeException) {
                false
            }
    }
}
