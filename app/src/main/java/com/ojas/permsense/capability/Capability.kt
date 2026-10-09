package com.ojas.permsense.capability

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

import rikka.shizuku.Shizuku

sealed interface SourceResult<out T> {

    data class Ok<T>(
        val data: T
    ) : SourceResult<T>

    data class NeedsSetup(
        val missing: String
    ) : SourceResult<Nothing>

    data class Broken(
        val reason: String,
        val cause: Throwable? = null
    ) : SourceResult<Nothing>
}

fun <T, R> SourceResult<T>.map(
    transform: (T) -> R
): SourceResult<R> {
    return when (this) {
        is SourceResult.Ok -> SourceResult.Ok(transform(data))
        is SourceResult.NeedsSetup -> this
        is SourceResult.Broken -> this
    }
}

enum class CapabilityTier {
    T0,
    T1,
    T1_PLUS,
    T2,
    T3
}

data class CapabilityStatus(
    val queryAllPackagesDeclared: Boolean,
    val usageAccessGranted: Boolean,
    val getAppOpsStatsGranted: Boolean,
    val shizukuRunning: Boolean,
    val shizukuPermissionGranted: Boolean,
    val t0InventoryReady: Boolean,
    val t0ContextReady: Boolean,
    val t1PermissionReady: Boolean,
    val highestAvailableTier: CapabilityTier
)

class CapabilityManager(
    context: Context
) {

    private val appContext =
        context.applicationContext

    fun isQueryAllPackagesDeclared(): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }

        return appContext.checkSelfPermission(
            Manifest.permission.QUERY_ALL_PACKAGES
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isUsageAccessGranted(): Boolean {

        val appOps =
            appContext.getSystemService(
                AppOpsManager::class.java
            ) ?: return false

        return try {

            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        appContext.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        appContext.packageName
                    )
                }

            // Granted from Settings, the mode is ALLOWED. Granted once with `pm grant` (or by
            // Shizuku), the mode stays at its default and the permission itself is what counts.
            mode == AppOpsManager.MODE_ALLOWED ||
                    (mode == AppOpsManager.MODE_DEFAULT &&
                            appContext.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                            PackageManager.PERMISSION_GRANTED)

        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuPermissionGranted(): Boolean {

        if (!isShizukuRunning()) {
            return false
        }

        return try {
            Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun isGetAppOpsStatsGranted(): Boolean {

        if (!isShizukuRunning()) {
            return false
        }

        return try {
            Shizuku.checkRemotePermission(
                "android.permission.GET_APP_OPS_STATS"
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun evaluate(): CapabilityStatus {

        val inventoryReady =
            isQueryAllPackagesDeclared()

        val usageReady =
            isUsageAccessGranted()

        val shizukuReady =
            isShizukuRunning()

        val shizukuPermission =
            isShizukuPermissionGranted()

        val appOpsStats =
            isGetAppOpsStatsGranted()

        val t1Ready =
            shizukuReady &&
                    shizukuPermission &&
                    appOpsStats

        val highestTier =
            when {
                t1Ready -> CapabilityTier.T1
                else -> CapabilityTier.T0
            }

        return CapabilityStatus(
            queryAllPackagesDeclared =
                inventoryReady,

            usageAccessGranted =
                usageReady,

            getAppOpsStatsGranted =
                appOpsStats,

            shizukuRunning =
                shizukuReady,

            shizukuPermissionGranted =
                shizukuPermission,

            t0InventoryReady =
                inventoryReady,

            t0ContextReady =
                usageReady,

            t1PermissionReady =
                t1Ready,

            highestAvailableTier =
                highestTier
        )
    }
}