package com.ojas.permsense.sources

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import com.ojas.permsense.capability.SourceResult
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The connection to the privileged process, and the one place a transaction is made.
 *
 * Every call here ends in a [SourceResult]: it worked, something has to be set up first, or it
 * broke, with the real cause in the message. Nothing is swallowed into a generic "failed".
 */
internal class ShizukuLink(private val context: Context) {

    private val bindLock = Any()

    @Volatile
    private var binder: IBinder? = null

    private var connection: ServiceConnection? = null

    private val debuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun isRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    fun hasPermission(): Boolean =
        isRunning() && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    private fun serviceArgs() =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, AppOpsUserService::class.java.name))
            // The collector has to keep watching while the app's own process is gone; otherwise
            // every time Android reclaims the app's memory would be a blind spot. It accepts calls
            // from this app only, and is removed by release() when the live collector is switched off.
            .daemon(true)
            .debuggable(debuggable)
            .version(Protocol.VERSION)
            .tag("permsense-appops")
            .processNameSuffix("appops")

    /** Call when the user turns the Shizuku-based tiers off, or when the app is done with them. */
    fun release() = synchronized(bindLock) {
        connection?.let {
            try {
                Shizuku.unbindUserService(serviceArgs(), it, true)
            } catch (_: Throwable) {
                // already gone
            }
        }
        connection = null
        binder = null
    }

    private fun connect(): SourceResult<IBinder> = synchronized(bindLock) {

        binder?.takeIf { it.isBinderAlive }?.let { return SourceResult.Ok(it) }

        if (!isRunning()) return SourceResult.NeedsSetup("Shizuku is not running")
        if (!hasPermission()) return SourceResult.NeedsSetup("Shizuku permission")

        // Drop a stale connection before rebinding, so connections do not pile up.
        connection?.let {
            try {
                Shizuku.unbindUserService(serviceArgs(), it, false)
            } catch (_: Throwable) {
            }
        }
        binder = null

        val latch = CountDownLatch(1)
        val fresh = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service        // no lock: this runs on the main thread
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        connection = fresh

        try {
            Shizuku.bindUserService(serviceArgs(), fresh)
        } catch (e: Throwable) {
            connection = null
            return SourceResult.Broken("Failed to bind the Shizuku service: ${e.brief()}", e)
        }

        val connected = try {
            latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!connected) return SourceResult.Broken("The Shizuku service did not connect in time.")

        val live = binder
        if (live == null || !live.isBinderAlive) {
            return SourceResult.Broken("The Shizuku service connected but returned no live binder.")
        }
        SourceResult.Ok(live)
    }

    /**
     * Makes one transaction.
     *
     * @param write fills the request (after the interface token)
     * @param read reads the payload of a successful reply
     */
    fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): SourceResult<T> {

        val service = when (val c = connect()) {
            is SourceResult.Ok -> c.data
            is SourceResult.NeedsSetup -> return c
            is SourceResult.Broken -> return c
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        return try {
            data.writeInterfaceToken(Protocol.DESCRIPTOR)
            write(data)

            if (!service.transact(code, data, reply, 0)) {
                return SourceResult.Broken("The privileged service did not handle request $code.")
            }
            reply.readException()

            when (reply.readInt()) {
                Protocol.STATUS_OK -> SourceResult.Ok(read(reply))
                else -> SourceResult.Broken(reply.readString() ?: "The privileged service reported an error.")
            }

        } catch (e: SecurityException) {
            SourceResult.NeedsSetup("Shizuku permission (${e.message})")
        } catch (e: DeadObjectException) {
            drop()
            SourceResult.Broken("The Shizuku service stopped while it was being used: ${e.brief()}", e)
        } catch (e: Throwable) {
            SourceResult.Broken("Shizuku request $code failed: ${e.brief()}", e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Checks the running service speaks this build's protocol. A leftover older one is replaced. */
    fun handshake(): SourceResult<Unit> {
        val result = call(Protocol.PING) { it.readInt() }
        return when (result) {
            is SourceResult.Ok ->
                if (result.data == Protocol.VERSION) SourceResult.Ok(Unit)
                else {
                    release()
                    SourceResult.Broken("The Shizuku service is from an older version of PermSense (protocol ${result.data}, expected ${Protocol.VERSION}). Reconnect and try again.")
                }
            is SourceResult.NeedsSetup -> result
            is SourceResult.Broken -> result
        }
    }

    private fun drop() = synchronized(bindLock) {
        binder = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
