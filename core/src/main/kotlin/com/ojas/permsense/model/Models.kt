package com.ojas.permsense.model

enum class Tier {
    T0,
    T1,
    T1_PLUS,
    T2,
    T3
}

enum class Visibility {
    VISIBLE,
    FOREGROUND_SERVICE,
    BACKGROUND,
    SCREEN_OFF,
    UNKNOWN
}

data class RawObservation(
    val tier: Tier,
    val sourceEventId: String,
    val uid: Int,
    val packageName: String,
    val op: String,
    val accessKind: String,
    val timestampMs: Long,
    val durationMs: Long? = null,
    val attributionTag: String? = null,
    val flags: Int = 0,
    val opMode: Int? = null,
    val uidState: Int? = null,
    val accessResult: String? = null,

    val evidenceSourceIds: Set<String> = setOf(sourceEventId),
    val evidenceTiers: Set<Tier> = setOf(tier)
) {
    val userId: Int
        get() = uid / 100000
}

data class Episode(
    val uid: Int,
    val packageName: String,
    val op: String,

    val stratum: Visibility,

    val startMs: Long,
    val endMs: Long,

    val observationCount: Int,

    val tiers: Set<Tier>,
    val sourceEventIds: List<String>,

    val accessKinds: Set<String>,
    val durationMs: Long?,

    val attributionTags: Set<String>,
    val flags: Int,

    val accessResults: Set<String>,

    val observations: List<RawObservation>
)

data class AppVersionPoint(
    val versionCode: Long,
    val effectiveFromMs: Long
)

data class ContextualisedEpisode(
    val episode: Episode,
    val stratum: Visibility,
    val screenState: String,
    val appVersionAtAccess: Long
)

data class AppInventory(
    val packageName: String,
    val versionCode: Long,

    val requestedPermissions: List<String>,
    val grantedPermissions: List<String>,

    val policyTags: Set<String> = emptySet(),

    val isAccessibilityEnabled: Boolean = false,
    val isDeviceAdmin: Boolean = false,
    val isNotificationListener: Boolean = false,
    val isSystemApp: Boolean = false,

    val category: Int = -1,

    val lastUpdateTimeMs: Long = 0L
)

data class VisibilityInterval(
    val packageName: String,
    val state: Visibility,
    val startMs: Long,
    val endMs: Long
)

data class DeviceOffInterval(
    val startMs: Long,
    val endMs: Long
)

data class SnapshotRow(
    val uid: Int,
    val packageName: String,
    val op: String,
    val lastAccessMs: Long,
    val lastRejectMs: Long = 0L,
    val uidState: Int? = null
)

/**
 * The app's process state at the moment of an access, as AppOps reports it. The numbers are
 * AppOpsManager.UID_STATE_*. Lower means more visible to the user.
 */
object UidStates {

    const val PERSISTENT = 100
    const val TOP = 200
    const val FOREGROUND_SERVICE_LOCATION = 300
    const val FOREGROUND_SERVICE = 400
    const val FOREGROUND = 500
    const val BACKGROUND = 600
    const val CACHED = 700

    /** The short names `dumpsys appops` prints: pers, top, fgsvcl, fgsvc, fg, bg, cch. */
    fun fromToken(token: String): Int? =
        when (token) {
            "pers" -> PERSISTENT
            "top" -> TOP
            "fgsvcl" -> FOREGROUND_SERVICE_LOCATION
            "fgsvc" -> FOREGROUND_SERVICE
            "fg" -> FOREGROUND
            "bg" -> BACKGROUND
            "cch" -> CACHED
            else -> null
        }

    /** The user can see the app: an activity of it is on screen (full screen or not). */
    fun isOnScreen(state: Int): Boolean = state == TOP || state == FOREGROUND

    fun isForegroundService(state: Int): Boolean =
        state == FOREGROUND_SERVICE || state == FOREGROUND_SERVICE_LOCATION
}

/** AppOpsManager.OP_FLAG_*: how an access was made. */
object OpFlags {

    const val SELF = 1
    const val TRUSTED_PROXY = 2
    const val UNTRUSTED_PROXY = 4
    const val TRUSTED_PROXIED = 8
    const val UNTRUSTED_PROXIED = 16

    /** The short names `dumpsys appops` prints: s, tp, up, tpd, upd. */
    fun fromToken(token: String): Int? =
        when (token) {
            "s" -> SELF
            "tp" -> TRUSTED_PROXY
            "up" -> UNTRUSTED_PROXY
            "tpd" -> TRUSTED_PROXIED
            "upd" -> UNTRUSTED_PROXIED
            else -> null
        }
}

object Ops {

    const val CONTACTS =
        "android:read_contacts"

    const val CALL_LOG =
        "android:read_call_log"

    const val SMS =
        "android:read_sms"

    const val PHONE_STATE =
        "android:read_phone_state"

    const val CLIPBOARD =
        "android:read_clipboard"

    const val CAMERA =
        "android:camera"

    const val MIC =
        "android:record_audio"

    const val FINE_LOC =
        "android:fine_location"

    const val COARSE_LOC =
        "android:coarse_location"

    const val MEDIA_IMAGES =
        "android:read_media_images"

    const val MEDIA_VIDEO =
        "android:read_media_video"

    const val MEDIA_AUDIO =
        "android:read_media_audio"

    const val EXT_STORAGE =
        "android:read_external_storage"

    val MEDIA =
        setOf(
            MEDIA_IMAGES,
            MEDIA_VIDEO,
            MEDIA_AUDIO,
            EXT_STORAGE
        )

    val INDICATOR =
        setOf(
            CAMERA,
            MIC,
            FINE_LOC,
            COARSE_LOC
        )

    val SENSITIVE: Set<String> =
        setOf(
            CONTACTS,
            CALL_LOG,
            SMS,
            PHONE_STATE,
            CLIPBOARD
        ) + INDICATOR + MEDIA
}