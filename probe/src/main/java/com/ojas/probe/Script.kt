package com.ojas.probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Where a scripted access runs from. This decides what state the app is in when it happens. */
enum class Trigger {
    /** On screen, in front of the user. */
    ACTIVITY,

    /** From a foreground service the user can see a notification for. */
    SERVICE,

    /** From a scheduled job: no screen, no service. Background, or screen off if the phone is locked. */
    JOB,

    /** From a broadcast, for short ad-hoc steps. Same state as a job. */
    BROADCAST,
}

/** The things the probe can do, each one an access PermSense is meant to notice. */
enum class ProbeOp(val opName: String) {
    CONTACTS("android:read_contacts"),
    CALL_LOG("android:read_call_log"),
    CAMERA("android:camera"),
    MIC("android:record_audio"),
    FINE_LOC("android:fine_location"),
    CLIPBOARD("android:read_clipboard"),
    MEDIA_IMAGES("android:read_media_images"),
}

/** [times] accesses of [op], [spacingMs] apart, whenever the app is started by one of [runIn]. */
data class Step(
    val id: String,
    val op: ProbeOp,
    val runIn: Set<Trigger>,
    val times: Int,
    val spacingMs: Long,
)

/**
 * The deterministic schedule. It is plain data (assets/schedule.json, one per build), so what a
 * build will do is readable without running it, and two phones given the same build do the same.
 */
data class Script(val name: String, val steps: List<Step>) {

    fun stepsFor(trigger: Trigger): List<Step> = steps.filter { trigger in it.runIn }

    companion object {

        fun load(context: Context): Script =
            parse(context.assets.open("schedule.json").bufferedReader().use { it.readText() })

        fun parse(text: String): Script {
            val root = JSONObject(text)
            val steps = root.getJSONArray("steps").objects().map { s ->
                Step(
                    id = s.getString("id"),
                    op = ProbeOp.valueOf(s.getString("op")),
                    runIn = s.getJSONArray("runIn").strings().map { Trigger.valueOf(it) }.toSet(),
                    times = s.getInt("times"),
                    spacingMs = s.optLong("spacingMs", 0L),
                )
            }
            return Script(root.getString("name"), steps)
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}
