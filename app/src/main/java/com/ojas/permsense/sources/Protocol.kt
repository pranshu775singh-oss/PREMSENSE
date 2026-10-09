package com.ojas.permsense.sources

import android.os.IBinder

/**
 * The wire protocol between the app and the Shizuku process that runs as shell.
 *
 * Every reply starts with the Binder exception slot, then an int status: 0 for success followed by
 * the payload, 1 for an error followed by a message. Large results never travel in one reply; the
 * service parks them under an id and the app reads them in chunks (see BlobTransfer in :core).
 */
internal object Protocol {

    const val DESCRIPTOR = "com.ojas.permsense.AppOpsService"

    /**
     * Bumped whenever anything about the protocol changes. It is also the Shizuku service version,
     * so a service process left over from an older build is replaced instead of being reused.
     */
    const val VERSION = 4

    /** PermSense's own application id. The privileged process acts on this package and no other for self-grants. */
    const val SELF_PACKAGE = "com.ojas.permsense"

    private const val FIRST = IBinder.FIRST_CALL_TRANSACTION

    const val PING = FIRST + 0
    const val SNAPSHOT_BEGIN = FIRST + 1
    const val HISTORY_BEGIN = FIRST + 2
    const val BLOB_READ = FIRST + 3
    const val BLOB_RELEASE = FIRST + 4

    const val T2_START = FIRST + 10
    const val T2_BATCH = FIRST + 11
    const val T2_ACK = FIRST + 12
    const val T2_STOP = FIRST + 13
    const val T2_STATUS = FIRST + 14

    const val REMEDIATION_STATE = FIRST + 20
    const val REMEDIATION_APPLY = FIRST + 21

    const val GRANT_SELF = FIRST + 30

    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    /** Events per live-collector batch: about 25 KB on the wire, far below the Binder limit. */
    const val T2_BATCH_SIZE = 300

    /** The most event ids one acknowledgement may carry. */
    const val T2_MAX_ACK = 1000

    /** Where the service keeps events it has not been told were safely stored. */
    const val T2_MAX_BUFFER = 20_000

    /** Most permissions a self-grant request may name. */
    const val MAX_SELF_GRANTS = 4

    /** The only permissions PermSense may grant itself. Each is the grant behind one tier. */
    val SELF_GRANTABLE: Set<String> = setOf(
        "android.permission.GET_APP_OPS_STATS",
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
    )

    /** How a change request travels. One definition, used by the app and by the service's tests. */
    fun writeRequest(p: android.os.Parcel, request: com.ojas.permsense.remediation.RemediationRequest) {
        p.writeInt(request.kind.ordinal)
        p.writeString(request.packageName)
        p.writeString(request.target)
        p.writeInt(request.mode ?: -1)
        p.writeInt(request.userId)
    }
}
