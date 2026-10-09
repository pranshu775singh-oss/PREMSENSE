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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.i18n.DigestText
import com.ojas.permsense.i18n.FindingText
import com.ojas.permsense.remediation.RemediationKind
import com.ojas.permsense.remediation.UndoEntry

/**
 * The weekly summary, on demand: a plain sentence about how things stand, the few findings worth a
 * look (within the alert budget), and every change made through PermSense with a way to take it back.
 */
@Composable
fun SummaryScreen(state: UiState, vm: MainViewModel, contentPadding: PaddingValues) {

    val report = state.report
    val digest = remember(report) { vm.digest() }
    val byPackage = remember(report) { report?.inventory.orEmpty().associateBy { it.packageName } }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        item {
            Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                DigestText.headline(digest, state.lang),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        items(digest.critical + digest.others, key = { it.dedupKey }) { finding ->
            FindingCard(
                finding = finding,
                lang = state.lang,
                label = state.labels[finding.packageName] ?: finding.packageName,
                versionCode = byPackage[finding.packageName]?.versionCode,
                onFix = { vm.requestFix(finding) },
                onNotUseful = { vm.feedback(finding, useful = false) },
                onOpenApp = { vm.openApp(finding.packageName) },
            )
        }

        item {
            Text(stringResource(R.string.changes_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.changes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val undoable = report?.undoable.orEmpty()

        if (undoable.isEmpty()) {
            item { Text(stringResource(R.string.changes_none), style = MaterialTheme.typography.bodyMedium) }
        }

        items(undoable, key = { it.resultRowHash }) { entry ->
            ChangeRow(entry, state, onUndo = { vm.undo(entry) })
        }
    }
}

@Composable
private fun ChangeRow(entry: UndoEntry, state: UiState, onUndo: () -> Unit) {

    val app = state.labels[entry.packageName] ?: entry.packageName
    val what = when (entry.kind) {
        RemediationKind.SET_OP_MODE -> FindingText.op(entry.target, state.lang)
        else -> com.ojas.permsense.judgement.PermissionOps.opsFor(entry.target).firstOrNull()
            ?.let { FindingText.op(it, state.lang) } ?: entry.target.substringAfterLast('.')
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.change_line, what, app), style = MaterialTheme.typography.bodyMedium)
                Text(
                    FindingText.time(entry.timestampMs, state.lang),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onUndo, enabled = !state.busy) {
                Text(stringResource(R.string.action_undo))
            }
        }
    }
}
