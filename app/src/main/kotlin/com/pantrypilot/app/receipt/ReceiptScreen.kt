package com.pantrypilot.app.receipt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import java.util.Locale

@Composable
fun ReceiptScreen(onClose: () -> Unit, viewModel: ReceiptViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val close = {
        viewModel.reset()
        onClose()
    }
    BackHandler(onBack = close)

    if (state.stage == ReceiptStage.REVIEW) {
        ReceiptReview(state, rules, viewModel, onRetake = viewModel::reset, onClose = close, onSaved = onClose)
    } else {
        ReceiptCapture(
            reading = state.stage == ReceiptStage.READING,
            message = state.message,
            onReading = viewModel::onReading,
            onRecognized = viewModel::onRecognized,
            onFailed = viewModel::onReadFailed,
            onClose = close,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptReview(
    state: ReceiptState,
    rules: List<ShelfLifeRuleEntity>,
    viewModel: ReceiptViewModel,
    onRetake: () -> Unit,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    var pickingRuleFor by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review receipt") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = { TextButton(onClick = onRetake) { Text("Retake") } },
            )
        },
        bottomBar = {
            Button(
                onClick = { viewModel.save(onSaved) },
                enabled = state.includedCount > 0,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (state.includedCount == 1) "Add 1 item" else "Add ${state.includedCount} items")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.items) { index, item ->
                val draft = item.draft
                val rule = rules.firstOrNull { it.id == draft.ruleId }
                Row {
                    Checkbox(
                        checked = item.included,
                        onCheckedChange = { viewModel.toggle(index) },
                        enabled = rule != null,
                    )
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { viewModel.rename(index, it) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = listOfNotNull(
                                draft.rawName,
                                "×${draft.quantity}".takeIf { draft.quantity > 1 },
                                String.format(Locale.getDefault(), "%.2f", draft.priceCents / 100.0),
                                "remembered".takeIf { draft.learned },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(
                            onClick = { pickingRuleFor = index },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(if (rule != null) "Keeps like: ${rule.name}" else "Choose what it keeps like")
                        }
                    }
                }
            }
        }
    }

    pickingRuleFor?.let { index ->
        AlertDialog(
            onDismissRequest = { pickingRuleFor = null },
            title = { Text("Keeps like") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.pickRule(index, rule)
                                    pickingRuleFor = null
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                rule.category,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingRuleFor = null }) { Text("Cancel") } },
        )
    }
}
