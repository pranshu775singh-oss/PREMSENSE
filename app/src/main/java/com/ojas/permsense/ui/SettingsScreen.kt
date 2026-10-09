package com.ojas.permsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.capability.CapabilityMatrix
import com.ojas.permsense.capability.TierState
import com.ojas.permsense.ledger.ChainVerification

@Composable
fun SettingsScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onToggleLock: (Boolean) -> Unit,
) {
    var confirmWipe by remember { mutableStateOf(false) }
    val settings = state.settings

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }

        // ---- language
        item {
            SettingsCard(stringResource(R.string.settings_language)) {
                LanguageOption(stringResource(R.string.lang_system), settings.languageTag == null) { vm.setLanguage(null) }
                LanguageOption(stringResource(R.string.lang_en), settings.languageTag == "en") { vm.setLanguage("en") }
                LanguageOption(stringResource(R.string.lang_hi), settings.languageTag == "hi") { vm.setLanguage("hi") }
            }
        }

        // ---- privacy and safety
        item {
            SettingsCard(stringResource(R.string.settings_privacy)) {
                SwitchRow(
                    title = stringResource(R.string.settings_lock),
                    detail = stringResource(R.string.settings_lock_detail),
                    checked = settings.appLock,
                    onChange = onToggleLock,
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_digest),
                    detail = stringResource(R.string.settings_digest_detail),
                    checked = settings.digest,
                    onChange = vm::setDigest,
                )
                HorizontalDivider()
                Text(stringResource(R.string.settings_quick_exit_detail), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::quit) { Text(stringResource(R.string.action_quick_exit)) }
            }
        }

        // ---- sensing
        item {
            SettingsCard(stringResource(R.string.settings_sensing)) {
                SwitchRow(
                    title = stringResource(R.string.settings_live),
                    detail = stringResource(R.string.settings_live_detail),
                    checked = settings.liveCollector,
                    onChange = vm::setLiveCollector,
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_t1_only),
                    detail = stringResource(R.string.settings_t1_only_detail),
                    checked = settings.t1Only,
                    onChange = vm::setT1Only,
                )
                HorizontalDivider()
                OutlinedButton(onClick = vm::reopenOnboarding) { Text(stringResource(R.string.settings_setup)) }
            }
        }

        // ---- what works
        item {
            SettingsCard(stringResource(R.string.settings_capabilities)) {
                val matrix = state.capability
                if (matrix == null) {
                    Text(stringResource(R.string.settings_capabilities_hint), style = MaterialTheme.typography.bodySmall)
                } else {
                    CapabilityList(matrix)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::probeCapabilities, enabled = !state.probing) {
                        Text(stringResource(if (state.probing) R.string.checking else R.string.settings_check_again))
                    }
                    if (matrix != null) {
                        TextButton(onClick = vm::copySupportReport) { Text(stringResource(R.string.settings_copy_report)) }
                    }
                }
            }
        }

        // ---- evidence
        item {
            SettingsCard(stringResource(R.string.settings_evidence)) {
                Text(stringResource(R.string.export_limits), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = vm::export, enabled = !state.busy && state.report != null) {
                    Text(stringResource(R.string.action_export))
                }
            }
        }

        // ---- erase
        item {
            SettingsCard(stringResource(R.string.settings_erase)) {
                Text(stringResource(R.string.settings_erase_detail), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { confirmWipe = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.action_erase)) }
            }
        }

        // ---- about and limits
        item {
            SettingsCard(stringResource(R.string.about_title)) {
                Text(stringResource(R.string.about_no_network), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_integrity), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_legal), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_rules), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- technical details
        item {
            SettingsCard(stringResource(R.string.details_title)) {
                val report = state.report
                if (report == null) {
                    Text(stringResource(R.string.findings_loading), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.details_rows, report.ledgerRows), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            if (report.chain is ChainVerification.Broken) R.string.details_chain_broken else R.string.details_chain_ok,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    report.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.erase_confirm_title)) },
            text = { Text(stringResource(R.string.erase_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; vm.wipe() }) {
                    Text(stringResource(R.string.action_erase), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 10.dp))
    }
}

/** One line per source of information: whether it works on this phone right now, and why not if it does not. */
@Composable
fun CapabilityList(matrix: CapabilityMatrix) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${matrix.manufacturer} ${matrix.model} · Android ${matrix.androidRelease}",
            style = MaterialTheme.typography.labelLarge,
        )
        matrix.checks.forEach { check ->
            val word = when (check.state) {
                TierState.WORKING -> stringResource(R.string.cap_working)
                TierState.NEEDS_SETUP -> stringResource(R.string.cap_needs_setup)
                TierState.NOT_USED -> stringResource(R.string.cap_off)
                TierState.FAILED -> stringResource(R.string.cap_failed)
            }
            Column {
                Text("${tierName(check.id)}: $word", style = MaterialTheme.typography.bodyMedium)
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun tierName(id: String): String =
    when (id) {
        "T0 inventory" -> stringResource(R.string.cap_t0_inventory)
        "T0 usage" -> stringResource(R.string.cap_t0_usage)
        "T1" -> stringResource(R.string.tier_t1)
        "T1+" -> stringResource(R.string.tier_t1plus)
        "T2" -> stringResource(R.string.tier_t2)
        "Fix" -> stringResource(R.string.cap_fix)
        else -> id
    }
