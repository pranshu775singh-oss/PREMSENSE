package com.ojas.permsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.findings.SuggestedAction

/** Everything on screen. The activity supplies the few things only it can do (the lock prompt). */
@Composable
fun PermSenseRoot(
    vm: MainViewModel,
    onUnlock: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !state.loaded -> Loading()
            state.locked -> LockScreen(onUnlock = onUnlock, onQuit = vm::quit)
            state.onboarding -> OnboardingScreen(state, vm)
            else -> MainScaffold(state, vm, onToggleLock)
        }
    }

    state.pendingFix?.let { FixDialog(state, vm) }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.findings_loading), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit, onQuit: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.lock_unlock)) }
        TextButton(onClick = onQuit) { Text(stringResource(R.string.action_quick_exit)) }
    }
}

@Composable
private fun MainScaffold(state: UiState, vm: MainViewModel, onToggleLock: (Boolean) -> Unit) {

    val snackbar = remember { SnackbarHostState() }

    // Resolved here, where the language is known, then shown once.
    val text = state.message?.let { messageText(it) }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopRow(vm) },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Triple(Tab.FINDINGS, R.string.nav_findings, Icons.Filled.Warning),
                    Triple(Tab.APPS, R.string.nav_apps, Icons.AutoMirrored.Filled.List),
                    Triple(Tab.SUMMARY, R.string.nav_summary, Icons.Filled.Notifications),
                    Triple(Tab.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
                )
                for ((tab, label, icon) in items) {
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { vm.select(tab) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (state.tab) {
            Tab.FINDINGS -> FindingsScreen(state, vm, padding)
            Tab.APPS -> AppsScreen(state, vm, padding)
            Tab.SUMMARY -> SummaryScreen(state, vm, padding)
            Tab.SETTINGS -> SettingsScreen(state, vm, padding, onToggleLock)
        }
    }
}

/** The one control on every screen: leave at once, and leave nothing behind in the recent apps list. */
@Composable
private fun TopRow(vm: MainViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = vm::quit) { Text(stringResource(R.string.action_quick_exit)) }
    }
}

@Composable
private fun messageText(message: UiMessage): String =
    when (message) {
        is UiMessage.Plain -> message.text
        is UiMessage.Res -> stringResource(message.id, *message.args.toTypedArray())
    }

/** Asks before anything is changed on the person's behalf, and says exactly what will happen. */
@Composable
private fun FixDialog(state: UiState, vm: MainViewModel) {

    val finding = state.pendingFix ?: return
    val app = state.labels[finding.packageName] ?: finding.packageName

    val what = when (val action = finding.suggestedAction) {
        is SuggestedAction.RevokePermission ->
            com.ojas.permsense.judgement.PermissionOps.opsFor(action.permission).firstOrNull()
                ?.let { com.ojas.permsense.i18n.FindingText.op(it, state.lang) }
                ?: action.permission.substringAfterLast('.')
        is SuggestedAction.SetOpMode -> com.ojas.permsense.i18n.FindingText.op(action.op, state.lang)
        else -> ""
    }

    val body = when (finding.suggestedAction) {
        is SuggestedAction.SetOpMode -> stringResource(R.string.fix_confirm_body_foreground, what, app)
        else -> stringResource(R.string.fix_confirm_body, what, app)
    }

    AlertDialog(
        onDismissRequest = vm::cancelFix,
        title = { Text(stringResource(R.string.fix_confirm_title)) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = vm::confirmFix) { Text(stringResource(R.string.action_confirm_fix)) } },
        dismissButton = { TextButton(onClick = vm::cancelFix) { Text(stringResource(R.string.action_cancel)) } },
    )
}
