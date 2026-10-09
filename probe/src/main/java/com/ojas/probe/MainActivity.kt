package com.ojas.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * QuickCash: a test app. It says so on screen, because it must never be mistaken for a real lender.
 *
 * Everything it does is also reachable from adb (see tools/probe/probe.sh), so a test run needs no
 * taps:  adb shell am start -n com.ojas.quickcash/com.ojas.probe.MainActivity --es autorun activity
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val log = IntendedLog(this)

        // v2 arranges its own recurring background job as soon as it runs. v1 never does.
        if (BuildConfig.PROBE_GENERATION >= 2) BackgroundJobService.schedulePeriodic(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "QuickCash"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "TEST APP built by the PermSense team. Not a real lender. It performs scripted accesses so PermSense can be measured."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(176, 32, 32))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12); bottomMargin = dp(12)
            }
        })

        status = TextView(this).apply { textSize = 14f }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }

        button("1. Allow what the app asks for") { requestPermissions(runtimePermissions(), REQUEST) }
        button("2. Run on-screen steps (ACTIVITY)") { runOnScreen() }
        button("3. Run service steps (SERVICE)") { startService(Intent(this, ProbeForegroundService::class.java)) }
        button("4. Run background steps now (JOB)") {
            BackgroundJobService.schedulePeriodic(this)
            BackgroundJobService.scheduleOneShot(this, ProbeOp.CONTACTS.name, 1, 0L)
        }
        button("Start a fresh intended log") { log.reset(); refresh() }

        setContentView(ScrollView(this).apply { addView(root) })

        refresh()

        // Hands-free runs from adb.
        when (intent.getStringExtra("autorun")) {
            "permissions" -> requestPermissions(runtimePermissions(), REQUEST)
            "activity" -> runOnScreen()
            "service" -> {
                startForegroundService(Intent(this, ProbeForegroundService::class.java))
                if (intent.getBooleanExtra("leave", true)) moveTaskToBack(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun runOnScreen() {
        Thread {
            val count = Runner(applicationContext).runScript(Trigger.ACTIVITY)
            runOnUiThread { refresh("Ran $count on-screen access(es).") }
        }.start()
    }

    private fun refresh(note: String = "") {
        val lines = IntendedLog(this).lineCount()
        val granted = runtimePermissions().count { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        status.text = buildString {
            appendLine("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, generation ${BuildConfig.PROBE_GENERATION})")
            appendLine("Permissions allowed: $granted of ${runtimePermissions().size}")
            appendLine("Intended-access log: ${maxOf(lines - 1, 0)} entries")
            if (note.isNotEmpty()) appendLine(note)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun runtimePermissions(): Array<String> =
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST = 7
    }
}
