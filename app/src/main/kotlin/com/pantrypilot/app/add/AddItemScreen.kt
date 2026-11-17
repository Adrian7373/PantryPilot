package com.pantrypilot.app.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.app.label
import com.pantrypilot.domain.decay.StorageZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemScreen(onClose: () -> Unit, viewModel: AddItemViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }

    if (scanning) {
        ScannerScreen(
            onScanned = { barcode ->
                scanning = false
                viewModel.onScanned(barcode)
            },
            onClose = { scanning = false },
        )
        return
    }

    val close = {
        viewModel.reset()
        onClose()
    }
    BackHandler(onBack = close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add item") },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { viewModel.save(onSaved = onClose) },
                enabled = state.ruleId != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Add to pantry") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text("Scan barcode", Modifier.padding(start = 8.dp))
                }
            }
            item {
                OutlinedTextField(
                    value = state.barcode,
                    onValueChange = viewModel::onBarcodeChange,
                    label = { Text("Barcode (optional)") },
                    supportingText = lookupMessage(state.lookup)?.let { message -> { Text(message) } },
                    isError = state.lookup == LookupStatus.FAILED,
                    trailingIcon = {
                        IconButton(onClick = viewModel::lookUp, enabled = state.canLookUp) {
                            Icon(Icons.Filled.Search, contentDescription = "Look up barcode")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.lookUp() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StorageZone.entries.forEach { zone ->
                        FilterChip(
                            selected = state.zone == zone,
                            onClick = { viewModel.pickZone(zone) },
                            label = { Text(zone.label()) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.size,
                    onValueChange = viewModel::onSizeChange,
                    label = { Text("Package size (optional)") },
                    placeholder = { Text("500 g, 1 l or 12") },
                    supportingText = {
                        Text(
                            if (state.sizeError) {
                                "Enter a number and a unit, like 500 g or 1 l"
                            } else {
                                "Leave empty for a typical package"
                            },
                        )
                    },
                    isError = state.sizeError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.price,
                    onValueChange = viewModel::onPriceChange,
                    label = { Text("Price (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    // The chosen rule may be scrolled out of view in the list below, so name it here.
                    val chosen = rules.firstOrNull { it.id == state.ruleId }?.name
                    Text(
                        text = if (chosen != null) "Keeps like: $chosen" else "Keeps like",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Sets how long it lasts in each zone. Pick the closest food.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.pickRule(rule) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = rule.id == state.ruleId, onClick = { viewModel.pickRule(rule) })
                    Column {
                        Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            rule.category,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun lookupMessage(status: LookupStatus): String? = when (status) {
    LookupStatus.IDLE -> null
    LookupStatus.LOADING -> "Looking up product…"
    LookupStatus.FOUND -> "Product found. Check the name and what it keeps like."
    LookupStatus.NOT_FOUND -> "No product found for this barcode. Enter the name yourself."
    LookupStatus.FAILED -> "Lookup failed. Check your connection, or enter the name yourself."
}
