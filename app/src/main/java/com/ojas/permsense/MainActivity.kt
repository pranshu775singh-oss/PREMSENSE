package com.ojas.permsense

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ojas.permsense.capability.CapabilityProbe
import com.ojas.permsense.engine.ExportFiles
import com.ojas.permsense.ui.Effect
import com.ojas.permsense.ui.Localized
import com.ojas.permsense.ui.MainViewModel
import com.ojas.permsense.ui.PermSenseRoot
import com.ojas.permsense.ui.theme.PermSenseTheme
import kotlinx.coroutines.launch

/**
 * A FragmentActivity (not just a ComponentActivity) because the phone's lock prompt needs one.
 *
 * It does only what the app itself cannot: start other screens, show the lock prompt, hand files to
 * the share sheet. Every decision is made in the view model and in :core.
 */
class MainActivity : FragmentActivity() {

    private val vm: MainViewModel by viewModels()

    private var promptShowing = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onResumed() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localized.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                vm.effects.collect { handle(it) }
            }
        }

        setContent {
            PermSenseTheme {
                PermSenseRoot(vm, onUnlock = ::promptUnlock, onToggleLock = ::toggleLock)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (vm.state.value.locked) promptUnlock()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()

        // With the lock on, the screen is hidden from screenshots and from the recent apps list.
        if (vm.state.value.settings.appLock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it again; turning the phone sideways does not.
        if (!isChangingConfigurations) vm.lock()
    }

    // ------------------------------------------------------------------ lock

    private fun authenticate(onSuccess: () -> Unit) {

        if (promptShowing) return

        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            // The phone no longer has a screen lock to check against. Do not lock the person out of
            // their own data; turn the app lock off and say so.
            vm.setAppLock(false)
            vm.unlocked()
            vm.showMessage(R.string.msg_lock_unavailable)
            return
        }

        promptShowing = true

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or failed: it stays locked and the screen offers the button again.
                    promptShowing = false
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }

    private fun promptUnlock() = authenticate { vm.unlocked() }

    private fun toggleLock(enabled: Boolean) {
        if (!enabled) {
            vm.setAppLock(false)
            return
        }
        // Prove the prompt works before locking anyone behind it.
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.showMessage(R.string.msg_lock_unavailable)
            return
        }
        authenticate { vm.setAppLock(true) }
    }

    // ------------------------------------------------------------------ things only an activity can do

    private fun handle(effect: Effect) {
        when (effect) {
            is Effect.OpenAppSettings ->
                start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${effect.packageName}")))

            Effect.OpenUsageAccess ->
                // Straight to this app's own entry where the phone allows it, otherwise to the list.
                if (!start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")), quiet = true)) {
                    start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }

            Effect.OpenShizuku ->
                packageManager.getLaunchIntentForPackage(CapabilityProbe.SHIZUKU_PACKAGE)?.let { start(it) }

            Effect.OpenDeveloperOptions -> start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

            is Effect.OpenUrl -> start(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))

            Effect.RequestNotifications ->
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

            Effect.Recreate -> recreate()

            Effect.QuitApp -> finishAndRemoveTask()

            is Effect.Share -> share(effect.files)

            is Effect.CopyText ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("PermSense", effect.text))
        }
    }

    /** Starts a screen; false if the phone has nothing to open it with. */
    private fun start(intent: Intent, quiet: Boolean = false): Boolean =
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (!quiet) vm.showMessage(R.string.msg_cannot_open)
            false
        } catch (_: SecurityException) {
            if (!quiet) vm.showMessage(R.string.msg_cannot_open)
            false
        }

    private fun share(files: ExportFiles) {
        val uris = arrayListOf(files.evidenceUri, files.reportUri)
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("PermSense", uris[0]).also { it.addItem(ClipData.Item(uris[1])) }
        }
        start(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
