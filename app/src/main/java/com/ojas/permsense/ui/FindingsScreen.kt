package com.ojas.permsense.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.engine.AuditReport
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.i18n.EvidenceCards
import com.ojas.permsense.i18n.FindingText
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.ledger.ChainVerification
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Tier
import com.ojas.permsense.ui.theme.LocalSeverityColors

/** One finding as a card that always answers the same questions, in the same order. */
@Composable
fun FindingCard(
    finding: Finding,
    lang: Lang,
    label: String,
    versionCode: Long?,
    onFix: () -> Unit,
    onNotUseful: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = remember(finding, lang, label, versionCode) {
        EvidenceCards.build(finding, lang, label, versionName = null, versionCode = versionCode)
    }
    val captions = remember(lang) { EvidenceCards.captions(lang) }
    val colors = LocalSeverityColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colors.container(card.severity),
            contentColor = colors.content(card.severity),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // A screen reader reads this block as one passage, in the order a person would ask.
            Column(
                modifier = Modifier.clearAndSetSemantics { contentDescription = card.spoken(lang) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    EvidenceCards.severityWord(card.severity, lang),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    card.what,
                    style = MaterialTheme.typography.titleMedium,
                )
                LabelledValue(captions.who, card.who)
                LabelledValue(captions.whenCaption, card.whenText)
                LabelledValue(captions.situation, card.situation)
                LabelledValue(captions.why, card.why)
            }

            card.doWhat?.let { action ->
                Button(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
                    Text(action)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenApp, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_app_details))
                }
                // A serious finding is never hidden by a tap, so it does not offer one.
                if (card.severity < 3) {
                    TextButton(onClick = onNotUseful, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_not_useful))
                    }
                }
            }
        }
    }
}

@Composable
fun FindingsScreen(state: UiState, vm: MainViewModel, contentPadding: PaddingValues) {

    val report = state.report
    val byPackage = remember(report) { report?.inventory.orEmpty().associateBy { it.packageName } }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        item {
            Text(stringResource(R.string.findings_title), style = MaterialTheme.typography.headlineSmall)
        }

        item { CheckRow(state, vm) }

        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        item { SourceChips(state) }

        item { Banners(state, vm) }

        if (report == null) {
            item { Text(stringResource(R.string.findings_loading), style = MaterialTheme.typography.bodyLarge) }
        } else if (report.findings.isEmpty()) {
            item { EmptyState(report, state) }
        } else {
            items(report.findings, key = { it.dedupKey }) { finding ->
                val app = byPackage[finding.packageName]
                FindingCard(
                    finding = finding,
                    lang = state.lang,
                    label = state.labels[finding.packageName] ?: finding.packageName,
                    versionCode = app?.versionCode,
                    onFix = { vm.requestFix(finding) },
                    onNotUseful = { vm.feedback(finding, useful = false) },
                    onOpenApp = { vm.openApp(finding.packageName) },
                )
            }
        }
    }
}

@Composable
private fun CheckRow(state: UiState, vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val last = if (state.lastRunMs > 0) FindingText.time(state.lastRunMs, state.lang) else stringResource(R.string.never)
        Text(
            stringResource(R.string.last_checked, last),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = vm::refresh, enabled = !state.busy) {
            Text(stringResource(R.string.check_now))
        }
    }
}

/** Which sources are delivering right now, so the person knows how much PermSense can actually see. */
@Composable
private fun SourceChips(state: UiState) {
    val tiers = state.report?.tiers.orEmpty()
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(stringResource(R.string.tier_t0), Tier.T0 in tiers, if (Tier.T0 in tiers) on else off)
        StatusChip(stringResource(R.string.tier_t1), Tier.T1 in tiers, if (Tier.T1 in tiers) on else off)
        StatusChip(stringResource(R.string.tier_t1plus), Tier.T1_PLUS in tiers, if (Tier.T1_PLUS in tiers) on else off)
        StatusChip(stringResource(R.string.tier_t2), Tier.T2 in tiers, if (Tier.T2 in tiers) on else off)
    }
}

@Composable
private fun Banners(state: UiState, vm: MainViewModel) {

    val report = state.report
    val status = state.status

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        if (!status.usageAccess) {
            Banner(
                stringResource(R.string.banner_usage_access), severity = 2,
                actionLabel = stringResource(R.string.action_open_settings), onAction = vm::openUsageAccess,
            )
        }

        if (report?.ledgerError != null) {
            Banner(stringResource(R.string.banner_ledger_error, report.ledgerError), severity = 3)
        }

        if (report?.chain is ChainVerification.Broken) {
            Banner(stringResource(R.string.banner_chain_broken), severity = 3)
        }

        if (state.settings.liveCollector && report != null && !report.liveCollectorRunning) {
            when {
                status.shizukuRunning && !status.shizukuAllowed -> Banner(
                    stringResource(R.string.banner_shizuku_not_allowed), severity = 1,
                    actionLabel = stringResource(R.string.action_allow), onAction = vm::requestShizukuPermission,
                )
                status.shizukuInstalled -> Banner(
                    stringResource(R.string.banner_live_paused), severity = 1,
                    actionLabel = stringResource(R.string.action_open_shizuku), onAction = vm::openShizuku,
                )
                else -> Banner(
                    stringResource(R.string.banner_live_off), severity = 1,
                    actionLabel = stringResource(R.string.action_set_up), onAction = vm::reopenOnboarding,
                )
            }
        }

        if (report != null && (report.baseline?.learningCells ?: 0) > 0) {
            Banner(stringResource(R.string.banner_learning), severity = 1)
        }

        if (report != null && report.rulesAwaitingReview.isNotEmpty()) {
            Banner(stringResource(R.string.banner_review, report.rulesAwaitingReview.joinToString(", ")), severity = 1)
        }
    }
}

@Composable
private fun EmptyState(report: AuditReport, state: UiState) {
    val limited = report.tiers.none { it == Tier.T1 || it == Tier.T1_PLUS || it == Tier.T2 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.findings_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (limited) R.string.findings_empty_limited else R.string.findings_empty_detail,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.lastRunMs > 0) {
                Text(
                    stringResource(R.string.last_checked, FindingText.time(state.lastRunMs, state.lang)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Inventory entry for a finding, if the app is still installed. */
internal fun AppInventory?.versionOrNull(): Long? = this?.versionCode
