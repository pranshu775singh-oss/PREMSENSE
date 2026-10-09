package com.ojas.permsense.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.setup.Oem

private const val LAST_STEP = 4

/**
 * Guided setup. Only the first two steps are needed: PermSense is useful on its own from the start
 * (it lists what every app can access and warns about the risky combinations). Everything after
 * that is optional and says plainly what it adds and what it costs.
 */
@Composable
fun OnboardingScreen(state: UiState, vm: MainViewModel) {

    var step by rememberSaveable { mutableIntStateOf(0) }
    val needsNotificationStep = Build.VERSION.SDK_INT >= 33

    fun next() {
        step = (step + 1).coerceAtMost(LAST_STEP)
        if (step == 2 && !needsNotificationStep) step = 3
    }

    fun back() {
        step = (step - 1).coerceAtLeast(0)
        if (step == 2 && !needsNotificationStep) step = 1
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            LinearProgressIndicator(
                progress = { (step + 1) / (LAST_STEP + 1f) },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> UsageStep(state, vm)
                    2 -> NotificationStep(state, vm)
                    3 -> DeeperStep(state, vm)
                    else -> DoneStep(state, vm)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = ::back) { Text(stringResource(R.string.action_back)) }
                } else {
                    TextButton(onClick = vm::quit) { Text(stringResource(R.string.action_quick_exit)) }
                }

                if (step < LAST_STEP) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step >= 2) {
                            TextButton(onClick = { step = LAST_STEP }) { Text(stringResource(R.string.onb_skip)) }
                        }
                        Button(onClick = ::next) { Text(stringResource(R.string.onb_continue)) }
                    }
                } else {
                    Button(onClick = vm::finishOnboarding) { Text(stringResource(R.string.onb_start)) }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep() {
    StepTitle(stringResource(R.string.onb_welcome_title))
    Body(stringResource(R.string.onb_welcome_body))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.onb_promise_1), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.onb_promise_2), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.onb_promise_3), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UsageStep(state: UiState, vm: MainViewModel) {
    StepTitle(stringResource(R.string.onb_usage_title))
    Body(stringResource(R.string.onb_usage_body))
    StateLine(state.status.usageAccess)
    if (!state.status.usageAccess) {
        Button(onClick = vm::openUsageAccess) { Text(stringResource(R.string.action_open_settings)) }
        Note(stringResource(R.string.onb_usage_note))
    }
}

@Composable
private fun NotificationStep(state: UiState, vm: MainViewModel) {
    StepTitle(stringResource(R.string.onb_notif_title))
    Body(stringResource(R.string.onb_notif_body))
    StateLine(state.status.notifications)
    if (!state.status.notifications) {
        Button(onClick = vm::requestNotifications) { Text(stringResource(R.string.onb_notif_button)) }
    }
}

@Composable
private fun StateLine(on: Boolean) {
    Text(
        stringResource(if (on) R.string.onb_state_on else R.string.onb_state_off),
        style = MaterialTheme.typography.titleMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeeperStep(state: UiState, vm: MainViewModel) {

    val status = state.status
    val oem = remember { Oem.fromManufacturer(Build.MANUFACTURER, Build.BRAND) }

    StepTitle(stringResource(R.string.onb_deeper_title))
    Body(stringResource(R.string.onb_deeper_body))

    // ---- Shizuku
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onb_shizuku_title), style = MaterialTheme.typography.titleMedium)

            when {
                !status.shizukuInstalled -> {
                    Text(stringResource(R.string.onb_shizuku_missing), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::openShizukuPage) { Text(stringResource(R.string.onb_shizuku_page)) }
                }
                !status.shizukuRunning -> {
                    Text(stringResource(R.string.onb_shizuku_stopped), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::openShizuku) { Text(stringResource(R.string.action_open_shizuku)) }
                        OutlinedButton(onClick = vm::openDeveloperOptions) { Text(stringResource(R.string.onb_dev_options)) }
                    }
                    Note(stringResource(R.string.onb_shizuku_restart))
                }
                !status.shizukuAllowed -> {
                    Text(stringResource(R.string.onb_shizuku_allow), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::requestShizukuPermission) { Text(stringResource(R.string.action_allow)) }
                }
                else -> {
                    Text(stringResource(R.string.onb_shizuku_ready), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // ---- phone-maker specific steps
    oemHint(oem)?.let { hint ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.onb_oem_title), style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // ---- survive restarts
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onb_grant_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.onb_grant_body), style = MaterialTheme.typography.bodyMedium)

            val held = status.directActivity && (state.settings.t1Only || status.directHistory)
            if (held) {
                Text(stringResource(R.string.onb_grant_done), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = vm::grantSelf, enabled = status.shizukuAllowed && !state.busy) {
                    Text(stringResource(R.string.onb_grant_button))
                }
                if (!status.shizukuAllowed) Note(stringResource(R.string.onb_grant_needs_shizuku))
            }
        }
    }

    // ---- T1-only
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_t1_only), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.onb_t1_only_body), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.settings.t1Only, onCheckedChange = vm::setT1Only, modifier = Modifier.padding(start = 12.dp))
        }
    }

    Note(stringResource(R.string.onb_dev_off))
}

@Composable
private fun DoneStep(state: UiState, vm: MainViewModel) {
    StepTitle(stringResource(R.string.onb_done_title))
    Body(stringResource(R.string.onb_done_body))

    val matrix = state.capability
    if (matrix != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { CapabilityList(matrix) }
        }
    }
    OutlinedButton(onClick = vm::probeCapabilities, enabled = !state.probing) {
        Text(stringResource(if (state.probing) R.string.checking else R.string.onb_check))
    }
    if (state.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** The extra step the phone's maker needs before wireless debugging or Shizuku will work, if any. */
@Composable
private fun oemHint(oem: Oem): String? =
    when (oem) {
        Oem.XIAOMI -> stringResource(R.string.oem_xiaomi)
        Oem.OPPO -> stringResource(R.string.oem_oppo)
        Oem.VIVO -> stringResource(R.string.oem_vivo)
        Oem.SAMSUNG -> stringResource(R.string.oem_samsung)
        Oem.HUAWEI -> stringResource(R.string.oem_huawei)
        Oem.OTHER -> null
    }
