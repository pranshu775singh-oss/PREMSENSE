package com.ojas.permsense.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ojas.permsense.R
import com.ojas.permsense.capability.CapabilityMatrix
import com.ojas.permsense.capability.CapabilityProbe
import com.ojas.permsense.capability.SourceResult
import com.ojas.permsense.engine.AuditEngine
import com.ojas.permsense.engine.AuditReport
import com.ojas.permsense.engine.ExportFiles
import com.ojas.permsense.engine.RunMode
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.OpMode
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.i18n.Digest
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.remediation.AppOpModes
import com.ojas.permsense.remediation.RemediationKind
import com.ojas.permsense.remediation.RemediationOutcome
import com.ojas.permsense.remediation.RemediationRequest
import com.ojas.permsense.remediation.UndoEntry
import com.ojas.permsense.timeline.AppTimeline
import com.ojas.permsense.timeline.Timeline
import com.ojas.permsense.work.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.TimeZone

enum class Tab { FINDINGS, APPS, SUMMARY, SETTINGS }

/** Something to tell the person once. Kept as a resource id so it is worded in the chosen language. */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Plain(val text: String) : UiMessage
}

/** Things only the activity can do, asked for by the view model. */
sealed interface Effect {
    data class OpenAppSettings(val packageName: String) : Effect
    data class Share(val files: ExportFiles) : Effect
    data object OpenUsageAccess : Effect
    data object OpenShizuku : Effect
    data object OpenDeveloperOptions : Effect
    data class OpenUrl(val url: String) : Effect
    data object RequestNotifications : Effect
    data object Recreate : Effect
    data object QuitApp : Effect
    data class CopyText(val text: String) : Effect
}

data class SettingsState(
    val appLock: Boolean,
    val digest: Boolean,
    val t1Only: Boolean,
    val liveCollector: Boolean,
    val languageTag: String?,
)

/** The cheap, instant facts the setup screens show; the full probe exercises each tier for real. */
data class DeviceStatus(
    val usageAccess: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuAllowed: Boolean = false,
    val directActivity: Boolean = false,
    val directHistory: Boolean = false,
    val notifications: Boolean = false,
)

data class UiState(
    val loaded: Boolean = false,
    val onboarding: Boolean = false,
    val locked: Boolean = false,
    val busy: Boolean = false,
    val tab: Tab = Tab.FINDINGS,
    val report: AuditReport? = null,
    /** Display names for the apps in [report], loaded once so scrolling a list never asks Android. */
    val labels: Map<String, String> = emptyMap(),
    val lastRunMs: Long = 0L,
    val lang: Lang = Lang.EN,
    val settings: SettingsState = SettingsState(false, true, false, true, null),
    val status: DeviceStatus = DeviceStatus(),
    val capability: CapabilityMatrix? = null,
    val probing: Boolean = false,
    val lending: Set<String> = emptySet(),
    val selectedApp: String? = null,
    val timeline: AppTimeline? = null,
    val pendingFix: Finding? = null,
    val message: UiMessage? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = AuditEngine.of(application)
    private val sensing = engine.sensing()
    private val probe = CapabilityProbe(application, sensing)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuChanged() }
    private val onBinder = Shizuku.OnBinderReceivedListener { shizukuChanged() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { shizukuChanged() }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(onPermission)
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onBinderDead)
        } catch (_: Throwable) {
            // Shizuku's client library is only a convenience; the app works without it.
        }
        refresh()
    }

    override fun onCleared() {
        try {
            Shizuku.removeRequestPermissionResultListener(onPermission)
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onBinderDead)
        } catch (_: Throwable) {
        }
    }

    private fun initialState(): UiState {
        val lang = Localized.langOf(getApplication())
        return UiState(
            onboarding = !engine.onboardingDone(),
            locked = engine.appLockEnabled(),
            lang = lang,
            settings = readSettings(),
            status = readStatus(),
            lending = engine.userTaggedLending(),
            lastRunMs = engine.lastRunMs(),
        )
    }

    private fun readSettings() = SettingsState(
        appLock = engine.appLockEnabled(),
        digest = engine.digestEnabled(),
        t1Only = engine.t1OnlyMode(),
        liveCollector = engine.liveCollectorEnabled(),
        languageTag = engine.languageTag(),
    )

    private fun readStatus() = DeviceStatus(
        usageAccess = sensing.hasUsageAccess(),
        shizukuInstalled = CapabilityProbe.isShizukuInstalled(getApplication()),
        shizukuRunning = sensing.isShizukuRunning(),
        shizukuAllowed = sensing.isShizukuPermissionGranted(),
        directActivity = sensing.canReadAppOpsDirectly(),
        directHistory = sensing.canReadHistoryDirectly(),
        notifications = com.ojas.permsense.work.Notifier.canPost(getApplication()),
    )

    // ------------------------------------------------------------------ checks

    /** Runs a full check and shows the result. Ignored while one is already running. */
    fun refresh() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }

            val report = engine.run(RunMode.FULL)

            // What is on screen now has been seen: no notification for it afterwards.
            engine.takeUnannouncedCritical(report.findings)

            val labels = withContext(Dispatchers.Default) {
                report.inventory.associate { it.packageName to engine.labelOf(it.packageName) }
            }

            _state.update {
                it.copy(
                    loaded = true,
                    busy = false,
                    report = report,
                    labels = labels,
                    lastRunMs = engine.lastRunMs(),
                    status = readStatus(),
                    lending = engine.userTaggedLending(),
                    settings = readSettings(),
                )
            }

            _state.value.selectedApp?.let { loadTimeline(it) }
        }
    }

    /** The screens call this when the activity comes back to the front, so Settings changes show at once. */
    fun onResumed() {
        _state.update { it.copy(status = readStatus(), settings = readSettings()) }
    }

    private fun shizukuChanged() {
        viewModelScope.launch {
            _state.update { it.copy(status = readStatus()) }
            refresh()
        }
    }

    fun probeCapabilities() {
        if (_state.value.probing) return
        viewModelScope.launch {
            _state.update { it.copy(probing = true) }
            val matrix = probe.run(engine.t1OnlyMode(), engine.liveCollectorEnabled())
            _state.update { it.copy(probing = false, capability = matrix, status = readStatus()) }
        }
    }

    // ------------------------------------------------------------------ navigation

    fun select(tab: Tab) = _state.update { it.copy(tab = tab, selectedApp = if (tab == Tab.APPS) it.selectedApp else null, timeline = if (tab == Tab.APPS) it.timeline else null) }

    fun openApp(packageName: String) {
        _state.update { it.copy(tab = Tab.APPS, selectedApp = packageName, timeline = null) }
        viewModelScope.launch { loadTimeline(packageName) }
    }

    fun closeApp() = _state.update { it.copy(selectedApp = null, timeline = null) }

    private suspend fun loadTimeline(packageName: String) {
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now).toLong()
        val timeline = withContext(Dispatchers.Default) {
            Timeline.build(engine.timelineFor(packageName, limit = 5_000), packageName, now, offset)
        }
        _state.update { if (it.selectedApp == packageName) it.copy(timeline = timeline) else it }
    }

    // ------------------------------------------------------------------ fixing

    /** Asks for confirmation when a fix can be made here, otherwise sends the person to Settings. */
    fun requestFix(finding: Finding) {
        when (val action = finding.suggestedAction) {
            is SuggestedAction.OpenAppSettings -> open(action.packageName)
            is SuggestedAction.RevokePermission, is SuggestedAction.SetOpMode ->
                if (sensing.isShizukuPermissionGranted()) {
                    _state.update { it.copy(pendingFix = finding) }
                } else {
                    message(R.string.msg_need_shizuku)
                    open(finding.packageName)
                }
            SuggestedAction.None -> Unit
        }
    }

    private fun open(packageName: String) {
        _effects.tryEmit(Effect.OpenAppSettings(packageName))
    }

    fun openAppSettings(packageName: String) = open(packageName)

    fun cancelFix() = _state.update { it.copy(pendingFix = null) }

    fun confirmFix() {
        val finding = _state.value.pendingFix ?: return
        _state.update { it.copy(pendingFix = null, busy = true) }

        val request = when (val action = finding.suggestedAction) {
            is SuggestedAction.RevokePermission ->
                RemediationRequest(RemediationKind.REVOKE_PERMISSION, action.packageName, action.permission)
            is SuggestedAction.SetOpMode ->
                RemediationRequest(
                    RemediationKind.SET_OP_MODE, action.packageName, action.op,
                    mode = if (action.mode == OpMode.FOREGROUND_ONLY) AppOpModes.FOREGROUND else AppOpModes.IGNORED,
                )
            else -> null
        }

        if (request == null) {
            _state.update { it.copy(busy = false) }
            return
        }

        viewModelScope.launch {
            val uid = engine.uidOf(request.packageName)
            val outcome = if (uid == null) {
                RemediationOutcome.Failed("The app is no longer installed.", null)
            } else {
                engine.remediate(request, uid)
            }
            _state.update { it.copy(busy = false) }
            message(outcomeMessage(outcome))
            refresh()
        }
    }

    fun undo(entry: UndoEntry) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val outcome = engine.undo(entry)
            _state.update { it.copy(busy = false) }
            message(if (outcome is RemediationOutcome.Done) UiMessage.Res(R.string.msg_undo_done) else outcomeMessage(outcome))
            refresh()
        }
    }

    private fun outcomeMessage(outcome: RemediationOutcome): UiMessage =
        when (outcome) {
            is RemediationOutcome.Done -> UiMessage.Res(R.string.msg_fix_done)
            is RemediationOutcome.AlreadyInPlace -> UiMessage.Res(R.string.msg_fix_already)
            is RemediationOutcome.NotApplied -> UiMessage.Res(R.string.msg_fix_not_applied)
            is RemediationOutcome.Rejected -> UiMessage.Res(R.string.msg_fix_rejected, listOf(outcome.reason))
            is RemediationOutcome.Failed -> UiMessage.Res(R.string.msg_fix_failed, listOf(outcome.reason))
        }

    /** Teaches the ranker which kinds of finding are useful. A critical finding is never hidden by it. */
    fun feedback(finding: Finding, useful: Boolean) {
        engine.feedback(finding, useful)
        refresh()
    }

    fun setLendingTag(packageName: String, lending: Boolean) {
        engine.setLendingTag(packageName, lending)
        _state.update { it.copy(lending = engine.userTaggedLending()) }
        refresh()
    }

    // ------------------------------------------------------------------ evidence

    fun export() {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val files = engine.exportEvidence(_state.value.lang, report.findings, report.inventory)
            _state.update { it.copy(busy = false) }
            if (files == null) message(R.string.msg_export_failed) else _effects.emit(Effect.Share(files))
        }
    }

    // ------------------------------------------------------------------ settings

    fun setLanguage(tag: String?) {
        engine.setLanguageTag(tag)
        _effects.tryEmit(Effect.Recreate)
    }

    fun setAppLock(enabled: Boolean) {
        engine.setAppLock(enabled)
        _state.update { it.copy(settings = readSettings(), locked = false) }
    }

    fun setDigest(enabled: Boolean) {
        engine.setDigestEnabled(enabled)
        Schedule.setDigest(getApplication(), enabled)
        _state.update { it.copy(settings = readSettings()) }
    }

    fun setT1Only(enabled: Boolean) {
        engine.setT1OnlyMode(enabled)
        _state.update { it.copy(settings = readSettings()) }
        probeCapabilities()
    }

    fun setLiveCollector(enabled: Boolean) {
        viewModelScope.launch {
            engine.setLiveCollectorEnabled(enabled)
            _state.update { it.copy(settings = readSettings()) }
            probeCapabilities()
        }
    }

    fun wipe() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            engine.wipeEverything()
            _state.update {
                initialState().copy(loaded = true, onboarding = true, locked = false, message = UiMessage.Res(R.string.msg_wiped))
            }
            refresh()
        }
    }

    // ------------------------------------------------------------------ lock

    fun lock() {
        if (engine.appLockEnabled()) _state.update { it.copy(locked = true) }
    }

    fun unlocked() = _state.update { it.copy(locked = false) }

    // ------------------------------------------------------------------ setup

    fun requestShizukuPermission() {
        sensing.requestShizukuPermission()
    }

    fun openUsageAccess() {
        _effects.tryEmit(Effect.OpenUsageAccess)
    }

    fun openShizuku() {
        _effects.tryEmit(Effect.OpenShizuku)
    }

    fun openShizukuPage() {
        _effects.tryEmit(Effect.OpenUrl("https://github.com/RikkaApps/Shizuku/releases"))
    }

    fun openDeveloperOptions() {
        _effects.tryEmit(Effect.OpenDeveloperOptions)
    }

    fun requestNotifications() {
        _effects.tryEmit(Effect.RequestNotifications)
    }

    fun grantSelf() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val r = sensing.grantSelfPermissions(includeHistory = !engine.t1OnlyMode())) {
                is SourceResult.Ok -> message(UiMessage.Res(R.string.msg_selfgrant_ok, listOf(r.data.size)))
                is SourceResult.NeedsSetup -> message(UiMessage.Res(R.string.msg_selfgrant_failed, listOf(r.missing)))
                is SourceResult.Broken -> message(UiMessage.Res(R.string.msg_selfgrant_failed, listOf(r.reason)))
            }
            _state.update { it.copy(busy = false, status = readStatus()) }
            probeCapabilities()
        }
    }

    fun finishOnboarding() {
        engine.setOnboardingDone(true)
        Schedule.ensureScheduled(getApplication(), engine.digestEnabled())
        _state.update { it.copy(onboarding = false) }
        refresh()
    }

    fun reopenOnboarding() = _state.update { it.copy(onboarding = true) }

    fun copySupportReport() {
        val text = _state.value.capability?.report() ?: return
        _effects.tryEmit(Effect.CopyText(text))
        message(R.string.msg_copied)
    }

    fun quit() {
        _effects.tryEmit(Effect.QuitApp)
    }

    // ------------------------------------------------------------------ messages

    fun showMessage(@StringRes id: Int) = message(UiMessage.Res(id))

    private fun message(@StringRes id: Int) = message(UiMessage.Res(id))

    private fun message(message: UiMessage) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** The weekly digest of what is on screen, within the alert budget. */
    fun digest(): Digest = engine.digestOf(_state.value.report?.findings.orEmpty())

    fun labelOf(packageName: String): String = engine.labelOf(packageName)

    fun lendingTagged(packageName: String): Boolean = engine.isLendingTagged(packageName)
}
