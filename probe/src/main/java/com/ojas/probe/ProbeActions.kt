package com.ojas.probe

import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What one attempt at an access ended as. */
data class ActionResult(
    /** OK, DENIED (the permission is not granted) or UNAVAILABLE (the phone could not do it). */
    val outcome: String,
    val detail: String,
) {
    companion object {
        fun ok(detail: String = "") = ActionResult("OK", detail)
        fun denied(detail: String = "") = ActionResult("DENIED", detail)
        fun unavailable(detail: String) = ActionResult("UNAVAILABLE", detail)
    }
}

/**
 * The real accesses. Each one goes through the same framework path a real app would use, so the
 * operating system notes it in AppOps exactly as it would for anyone else.
 *
 * Always call from a worker thread: some of these wait for the hardware.
 */
object ProbeActions {

    fun perform(context: Context, op: ProbeOp): ActionResult =
        try {
            when (op) {
                ProbeOp.CONTACTS -> readContacts(context)
                ProbeOp.CALL_LOG -> readCallLog(context)
                ProbeOp.CAMERA -> useCamera(context)
                ProbeOp.MIC -> useMicrophone()
                ProbeOp.FINE_LOC -> readLocation(context)
                ProbeOp.CLIPBOARD -> readClipboard(context)
                ProbeOp.MEDIA_IMAGES -> readPhotos(context)
            }
        } catch (e: SecurityException) {
            ActionResult.denied(e.javaClass.simpleName)
        } catch (e: Exception) {
            ActionResult.unavailable(e.javaClass.simpleName)
        }

    private fun readContacts(context: Context): ActionResult {
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null,
        ) ?: return ActionResult.unavailable("no_cursor")
        return cursor.use { ActionResult.ok("rows=${it.count}") }
    }

    private fun readCallLog(context: Context): ActionResult {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null,
        ) ?: return ActionResult.unavailable("no_cursor")
        return cursor.use { ActionResult.ok("rows=${it.count}") }
    }

    private fun readPhotos(context: Context): ActionResult {
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, null,
        ) ?: return ActionResult.unavailable("no_cursor")
        return cursor.use { ActionResult.ok("rows=${it.count}") }
    }

    /** Opens the camera, holds it for a moment (a continuous op has a duration), and closes it. */
    private fun useCamera(context: Context): ActionResult {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: return ActionResult.unavailable("no_camera")

        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        var failure: Int? = null

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                opened.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                opened.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                failure = error
                camera.close()
                opened.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        opened.await(5, TimeUnit.SECONDS)
        val camera = device ?: return ActionResult.unavailable("camera_error_${failure ?: "timeout"}")
        Thread.sleep(HOLD_MS)
        camera.close()
        return ActionResult.ok("held_ms=$HOLD_MS")
    }

    private fun useMicrophone(): ActionResult {
        val rate = 16_000
        val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return ActionResult.unavailable("no_audio_input")

        @Suppress("MissingPermission")
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return ActionResult.denied("audio_record_not_initialised")
        }
        return try {
            recorder.startRecording()
            Thread.sleep(HOLD_MS)
            recorder.stop()
            ActionResult.ok("held_ms=$HOLD_MS")
        } finally {
            recorder.release()
        }
    }

    private fun readLocation(context: Context): ActionResult {
        val manager = context.getSystemService(LocationManager::class.java)
        @Suppress("MissingPermission")
        val fix = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return ActionResult.ok(if (fix != null) "fix=1" else "fix=0")
    }

    private fun readClipboard(context: Context): ActionResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip
        return ActionResult.ok(if (clip != null) "items=${clip.itemCount}" else "items=0")
    }

    private const val HOLD_MS = 1_200L
}
