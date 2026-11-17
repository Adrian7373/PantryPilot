package com.pantrypilot.app.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.app.digest.DigestPrefs
import com.pantrypilot.domain.insights.MonthSummary
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Series colours: the first two slots of the validated reference palette, stepped per theme. */
private object SeriesColors {
    val usedLight = Color(0xFF2A78D6)
    val usedDark = Color(0xFF3987E5)
    val wastedLight = Color(0xFFEB6834)
    val wastedDark = Color(0xFFD95926)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val digest by viewModel.digest.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reminder settings")
                    }
                },
            )
        },
    ) { padding ->
        val current = state ?: return@Scaffold
        if (!current.hasAnyData) {
            EmptyInsights(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MonthSwitcher(current, viewModel) }
            item { Headline(current.summary) }
            item { StatTiles(current.summary) }
            item { TrendCard(current, onSelect = { viewModel.show(it.month) }) }
            if (current.summary.topWasted.isNotEmpty()) {
                item { Text("Thrown away most", style = MaterialTheme.typography.titleMedium) }
                items(current.summary.topWasted) { food ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(food.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(money(food.cents), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (current.summary.estimated) {
                item {
                    Text(
                        "Where a receipt had no price, a typical price is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        DigestSettingsDialog(digest, onSave = viewModel::updateDigest, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun EmptyInsights(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Insights, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "Your savings show up here",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Cook, use up or toss food from your pantry, and PantryPilot keeps track of what it was worth.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MonthSwitcher(state: InsightsState, viewModel: InsightsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::previousMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            monthName(state.summary.month, full = true),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = viewModel::nextMonth, enabled = !state.isCurrentMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

/** The one number that matters: food that would have been thrown away. */
@Composable
private fun Headline(summary: MonthSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (summary.rescuedCents > 0.0) {
                Text(
                    money(summary.rescuedCents),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "of food rescued: eaten or frozen in its last two days",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    "Nothing rescued yet this month",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Cook or freeze food in its last two days and it counts here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatTiles(summary: MonthSummary) {
    val share = summary.wasteShare
    // Equal heights, so the tiles line up whether or not one has a detail line.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Eaten", money(summary.usedCents), null, Modifier.weight(1f).fillMaxHeight())
        StatTile(
            "Thrown away",
            money(summary.wastedCents),
            share?.let { "${(it * 100).roundToInt()}% of all food used or tossed" },
            Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String?, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Eaten versus thrown away for the last six months, as grouped bars on one axis. Tapping a
 * month shows its numbers above, which doubles as the chart's readout.
 */
@Composable
private fun TrendCard(state: InsightsState, onSelect: (MonthSummary) -> Unit) {
    val dark = isSystemInDarkTheme()
    val used = if (dark) SeriesColors.usedDark else SeriesColors.usedLight
    val wasted = if (dark) SeriesColors.wastedDark else SeriesColors.wastedLight
    val grid = MaterialTheme.colorScheme.outlineVariant
    val history = state.history
    val maxCents = history.maxOf { maxOf(it.usedCents, it.wastedCents) }.coerceAtLeast(1.0)

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last 6 months", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LegendKey(used, "Eaten")
                LegendKey(wasted, "Thrown away", Modifier.padding(start = 12.dp))
            }
            Text(
                "Up to ${money(maxCents)} a month",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().height(140.dp)) {
                history.forEach { month ->
                    val selected = month.month == state.summary.month
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(month) }
                            .semantics {
                                contentDescription = "${monthName(month.month, full = true)}: " +
                                    "eaten ${money(month.usedCents)}, thrown away ${money(month.wastedCents)}"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(Modifier.weight(1f).fillMaxWidth()) {
                            val barWidth = 10.dp.toPx()
                            val gap = 2.dp.toPx()
                            val radius = 4.dp.toPx()
                            // Recessive baseline.
                            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                            val left = size.width / 2 - barWidth - gap / 2
                            listOf(month.usedCents to used, month.wastedCents to wasted).forEachIndexed { i, (cents, color) ->
                                val height = (cents / maxCents * size.height).toFloat()
                                if (height > 0f) {
                                    val x = left + i * (barWidth + gap)
                                    // Rounded at the data end only; square where it meets the baseline.
                                    val path = Path().apply {
                                        addRoundRect(
                                            RoundRect(
                                                left = x,
                                                top = size.height - height,
                                                right = x + barWidth,
                                                bottom = size.height,
                                                topLeftCornerRadius = CornerRadius(radius),
                                                topRightCornerRadius = CornerRadius(radius),
                                            ),
                                        )
                                    }
                                    drawPath(path, color)
                                }
                            }
                        }
                        Text(
                            monthName(month.month, full = false),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigestSettingsDialog(current: DigestPrefs, onSave: (DigestPrefs) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(current.enabled) }
    val time = rememberTimePickerState(current.time.hour, current.time.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily summary") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "One notification a day listing food to use in the next two days",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.padding(start = 8.dp))
                }
                if (enabled) {
                    Text("Send it at", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    TimePicker(state = time)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(DigestPrefs(enabled, LocalTime.of(time.hour, time.minute)))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun money(cents: Double): String = NumberFormat.getCurrencyInstance().format(cents / 100.0)

private fun monthName(month: java.time.YearMonth, full: Boolean): String =
    if (full) {
        "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}"
    } else {
        month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
