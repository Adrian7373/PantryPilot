package com.pantrypilot.app.pantry

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.app.add.AddItemScreen
import com.pantrypilot.app.formatAmount
import com.pantrypilot.app.label
import com.pantrypilot.app.receipt.ReceiptScreen
import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.domain.decay.DecayEngine
import com.pantrypilot.domain.decay.StorageZone
import java.util.concurrent.TimeUnit

private enum class PantryMode { LIST, ADD_ITEM, SCAN_RECEIPT }

/** [onCookTonight] switches to the recipes, which are already ranked by what expires first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(onCookTonight: () -> Unit, viewModel: PantryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(PantryMode.LIST) }

    when (mode) {
        PantryMode.LIST -> Unit
        PantryMode.ADD_ITEM -> {
            AddItemScreen(onClose = { mode = PantryMode.LIST })
            return
        }
        PantryMode.SCAN_RECEIPT -> {
            ReceiptScreen(onClose = { mode = PantryMode.LIST })
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pantry") },
                actions = {
                    TextButton(onClick = { mode = PantryMode.SCAN_RECEIPT }) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                        Text("Scan receipt", Modifier.padding(start = 8.dp))
                    }
                },
            )
        },
        floatingActionButton = {
            // The empty state has its own "Add an item" button.
            if (items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { mode = PantryMode.ADD_ITEM },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add item") },
                )
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyPantry(
                onScanReceipt = { mode = PantryMode.SCAN_RECEIPT },
                onAddItem = { mode = PantryMode.ADD_ITEM },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The same 48-hour window the daily digest uses, so the list and the notification agree.
                val now = System.currentTimeMillis()
                val (soon, later) = items.partition {
                    it.expiresAt - now <= DecayEngine.DEFAULT_USE_SOON_WINDOW.toMillis()
                }
                if (soon.isNotEmpty()) {
                    item(key = "header:soon") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Use within 2 days",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCookTonight) {
                                Icon(Icons.Filled.RestaurantMenu, contentDescription = null)
                                Text("Cook tonight", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    items(soon, key = { it.id }) { item -> PantryItemCard(item, viewModel) }
                    if (later.isNotEmpty()) {
                        item(key = "header:later") {
                            Text(
                                "Later",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                items(later, key = { it.id }) { item -> PantryItemCard(item, viewModel) }
            }
        }
    }
}

@Composable
private fun EmptyPantry(onScanReceipt: () -> Unit, onAddItem: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Kitchen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(
            "Your pantry is empty",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Scan a grocery receipt to add everything at once, or add items one at a time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onScanReceipt, modifier = Modifier.fillMaxWidth()) { Text("Scan a receipt") }
        OutlinedButton(onClick = onAddItem, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Add an item") }
    }
}

@Composable
private fun PantryItemCard(item: PantryItemEntity, viewModel: PantryViewModel) {
    val millisLeft = item.expiresAt - System.currentTimeMillis()
    val urgent = millisLeft <= DecayEngine.DEFAULT_USE_SOON_WINDOW.toMillis()
    val partlyUsed = item.amountLeft < item.packageAmount * item.quantity * 0.999
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(
                    "×${item.quantity}".takeIf { item.quantity > 1 && !partlyUsed },
                    item.zone.label() + if (item.opened) ", opened" else "",
                    timeLeftLabel(millisLeft),
                    "${formatAmount(item.amountLeft, item.stockUnit)} left".takeIf { partlyUsed },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!item.opened) Action("Opened") { viewModel.markOpened(item.id) }
                if (item.zone == StorageZone.FREEZER) {
                    Action("Thaw in fridge") { viewModel.moveTo(item.id, StorageZone.FRIDGE) }
                } else {
                    Action("Freeze") { viewModel.moveTo(item.id, StorageZone.FREEZER) }
                }
                Action("Used") { viewModel.markUsed(item.id) }
                Action("Tossed") { viewModel.markTossed(item.id) }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

private fun timeLeftLabel(millisLeft: Long): String {
    if (millisLeft <= 0) return "Expired"
    // Round up so a 2-day item reads "48 h" and a 14-day item "14 days" until a full unit has passed.
    val hours = (millisLeft + TimeUnit.HOURS.toMillis(1) - 1) / TimeUnit.HOURS.toMillis(1)
    val days = (millisLeft + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)
    return when {
        hours <= 1 -> "Expires within the hour"
        hours <= 48 -> "Expires in $hours h"
        else -> "Expires in $days days"
    }
}
