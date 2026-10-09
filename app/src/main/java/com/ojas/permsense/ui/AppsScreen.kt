package com.ojas.permsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ojas.permsense.R
import com.ojas.permsense.i18n.FindingText
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.judgement.PermissionOps
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Ops
import com.ojas.permsense.timeline.DayBucket
import com.ojas.permsense.timeline.TimelineEvent

@Composable
fun AppsScreen(state: UiState, vm: MainViewModel, contentPadding: PaddingValues) {
    val selected = state.selectedApp
    if (selected != null) {
        AppDetail(selected, state, vm, contentPadding)
    } else {
        AppList(state, vm, contentPadding)
    }
}

/** The sensitive things an app is currently allowed to use, worded for a person. */
internal fun sensitiveAllowed(app: AppInventory, lang: Lang): List<String> =
    app.grantedPermissions
        .flatMap { PermissionOps.opsFor(it) }
        .filter { it in Ops.SENSITIVE }
        .distinct()
        .map { FindingText.op(it, lang) }
        .distinct()
        .sorted()

@Composable
private fun AppList(state: UiState, vm: MainViewModel, contentPadding: PaddingValues) {

    val apps = remember(state.report, state.lang) {
        state.report?.inventory.orEmpty()
            .filter { !it.isSystemApp }
            .sortedWith(
                compareByDescending<AppInventory> { sensitiveAllowed(it, state.lang).size }
                    .thenBy { (state.labels[it.packageName] ?: it.packageName).lowercase() }
            )
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.apps_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (apps.isEmpty()) {
            item { Text(stringResource(R.string.apps_empty), style = MaterialTheme.typography.bodyLarge) }
        }

        items(apps, key = { it.packageName }) { app ->
            val allowed = remember(app, state.lang) { sensitiveAllowed(app, state.lang) }
            val lending = remember(state.lending, app.packageName) { vm.lendingTagged(app.packageName) }
            val label = state.labels[app.packageName] ?: app.packageName

            Card(onClick = { vm.openApp(app.packageName) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (allowed.isEmpty()) stringResource(R.string.apps_none_sensitive)
                        else stringResource(R.string.apps_allowed, allowed.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (lending) {
                        Text(
                            stringResource(R.string.apps_lending_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetail(packageName: String, state: UiState, vm: MainViewModel, contentPadding: PaddingValues) {

    val app = remember(state.report, packageName) { state.report?.inventory.orEmpty().firstOrNull { it.packageName == packageName } }
    val label = state.labels[packageName] ?: packageName
    val lending = remember(state.lending, packageName) { vm.lendingTagged(packageName) }
    val allowed = remember(app, state.lang) { app?.let { sensitiveAllowed(it, state.lang) }.orEmpty() }
    val timeline = state.timeline

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        item {
            TextButton(onClick = vm::closeApp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_back))
            }
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Text(
                buildString {
                    append(packageName)
                    app?.let { append("  ·  ").append(stringResource(R.string.version_number, it.versionCode)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (app == null) {
            item { Text(stringResource(R.string.app_not_installed), style = MaterialTheme.typography.bodyLarge) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apps_lending_switch), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.apps_lending_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = lending, onCheckedChange = { vm.setLendingTag(packageName, it) })
                }
            }
        }

        item {
            Text(stringResource(R.string.apps_allowed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (allowed.isEmpty()) stringResource(R.string.apps_none_sensitive) else allowed.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleMedium)
            if (timeline == null) {
                Text(stringResource(R.string.findings_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                TimelineChart(timeline.days, state.lang)
                Legend(state.lang)
                if (timeline.hasOlderHistory) {
                    Text(
                        stringResource(R.string.timeline_older),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (timeline != null) {
            item { Text(stringResource(R.string.timeline_recent), style = MaterialTheme.typography.titleMedium) }

            if (timeline.events.isEmpty()) {
                item { Text(stringResource(R.string.timeline_none), style = MaterialTheme.typography.bodyMedium) }
            }

            items(timeline.events.take(60)) { event ->
                EventRow(event, state.lang)
                HorizontalDivider()
            }
        }

        item {
            Button(onClick = { vm.openAppSettings(packageName) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_app_settings))
            }
        }
    }
}

@Composable
private fun EventRow(event: TimelineEvent, lang: Lang) {
    val what = FindingText.op(event.op, lang)
    val line = when (event.kind) {
        TimelineEvent.Kind.ACCESS -> {
            val times = if (event.count > 1) " ×${event.count}" else ""
            "$what$times · ${strataLabel(event.stratum)}"
        }
        TimelineEvent.Kind.CHANGE -> stringResource(R.string.timeline_change, what, event.detail.orEmpty().substringAfter(':').lowercase())
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(FindingText.time(event.timestampMs, lang), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}

private val STRATUM_ORDER = listOf("VISIBLE", "FOREGROUND_SERVICE", "BACKGROUND", "SCREEN_OFF", "UNKNOWN")

private fun strataColor(stratum: String): Color =
    when (stratum) {
        "VISIBLE" -> Color(0xFF2E7D32)
        "FOREGROUND_SERVICE" -> Color(0xFF00838F)
        "BACKGROUND" -> Color(0xFFF9A825)
        "SCREEN_OFF" -> Color(0xFFC62828)
        else -> Color(0xFF9E9E9E)
    }

@Composable
private fun strataLabel(stratum: String): String =
    when (stratum) {
        "VISIBLE" -> stringResource(R.string.stratum_visible)
        "FOREGROUND_SERVICE" -> stringResource(R.string.stratum_service)
        "BACKGROUND" -> stringResource(R.string.stratum_background)
        "SCREEN_OFF" -> stringResource(R.string.stratum_screen_off)
        else -> stringResource(R.string.stratum_unknown)
    }

/**
 * Accesses per day for the last two weeks, stacked by what the phone was doing. A bar is only a
 * picture: the numbers are also in the description a screen reader reads.
 */
@Composable
fun TimelineChart(days: List<DayBucket>, lang: Lang, modifier: Modifier = Modifier) {

    val max = (days.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    val axis = MaterialTheme.colorScheme.outline

    val summary = days
        .filter { it.total > 0 }
        .joinToString("; ") { "${FindingText.time(it.dayStartMs, lang).substringBefore(',')}: ${it.total}" }
        .ifEmpty { stringResource(R.string.timeline_none) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = summary },
        ) {
            val slot = size.width / days.size
            val barWidth = slot * 0.7f

            days.forEachIndexed { index, day ->
                var y = size.height
                for (stratum in STRATUM_ORDER) {
                    val n = day.byStratum[stratum] ?: 0
                    if (n == 0) continue
                    val h = size.height * n / max
                    y -= h
                    drawRect(
                        color = strataColor(stratum),
                        topLeft = Offset(index * slot + (slot - barWidth) / 2f, y),
                        size = Size(barWidth, h),
                    )
                }
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.firstOrNull()?.let {
                Text(FindingText.time(it.dayStartMs, lang).substringBefore(','), style = MaterialTheme.typography.labelSmall)
            }
            Text(stringResource(R.string.timeline_max, max), style = MaterialTheme.typography.labelSmall)
            days.lastOrNull()?.let {
                Text(FindingText.time(it.dayStartMs, lang).substringBefore(','), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Legend(lang: Lang) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (stratum in STRATUM_ORDER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(strataColor(stratum))
                Spacer(Modifier.width(8.dp))
                Text(strataLabel(stratum), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
