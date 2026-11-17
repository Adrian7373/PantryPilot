package com.pantrypilot.app.restock

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.app.formatAmount
import com.pantrypilot.app.shortFoodName
import com.pantrypilot.data.CheckAnswer
import com.pantrypilot.data.RestockRepository
import com.pantrypilot.data.StapleCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestockScreen(viewModel: RestockViewModel = hiltViewModel()) {
    val shopping by viewModel.shopping.collectAsStateWithLifecycle()
    val staples by viewModel.staples.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var choosingStaple by remember { mutableStateOf(false) }

    fun addDraft() {
        if (draft.isNotBlank()) viewModel.add(draft)
        draft = ""
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Restock") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (checks.isNotEmpty()) {
                item { Text("Quick check", style = MaterialTheme.typography.titleMedium) }
                items(checks, key = { "check:${it.rule.id}" }) { check ->
                    QuickCheckCard(check, onAnswer = { viewModel.answer(check.rule.id, it) })
                }
                item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            }
            item { Text("Shopping list", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Add to the list") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = ::addDraft, enabled = draft.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDraft() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (shopping.isEmpty()) {
                item {
                    Hint("Nothing to buy. Staples go on the list by themselves when they run low.")
                }
            }
            // Both sections share one list, and a food can be in both, so keys carry the section.
            items(shopping, key = { "list:${it.id}" }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.bought(item.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = false, onCheckedChange = { viewModel.bought(item.id) })
                    Column {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item { Text("Kept in stock", style = MaterialTheme.typography.titleMedium) }
            item {
                Hint(
                    "When less than a quarter of a typical package is left, it goes on the shopping list. " +
                        "Once you've bought something twice, you'll get a quick check when it's probably running low.",
                )
            }
            items(staples, key = { "staple:${it.rule.id}" }) { staple ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(staple.rule.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (staple.tracked) {
                                "Have ${formatAmount(staple.onHand, staple.rule.stockUnit)} · " +
                                    "low below ${formatAmount(staple.parAmount, staple.rule.stockUnit)}"
                            } else {
                                "Not bought yet · low below ${formatAmount(staple.parAmount, staple.rule.stockUnit)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (staple.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.stopKeeping(staple.rule.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop keeping ${staple.rule.name} in stock")
                    }
                }
            }
            item {
                TextButton(onClick = { choosingStaple = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Keep another food in stock", Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (choosingStaple) {
        val kept = staples.map { it.rule.id }.toSet()
        AlertDialog(
            onDismissRequest = { choosingStaple = false },
            title = { Text("Keep in stock") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rules.filter { it.id !in kept }, key = { it.id }) { rule ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.keepStocked(rule)
                                    choosingStaple = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Low below ${formatAmount(rule.packageSize * RestockRepository.PAR_FRACTION, rule.stockUnit)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingStaple = false }) { Text("Cancel") } },
        )
    }
}

/** "Still have milk?" with three one-tap answers, instead of logging every splash. */
@Composable
private fun QuickCheckCard(check: StapleCheck, onAnswer: (CheckAnswer) -> Unit) {
    val name = check.rule.name.shortFoodName()
    val perWeek = formatAmount(check.dailyRate * 7, check.rule.stockUnit)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Still have ${name.lowercase()}?", style = MaterialTheme.typography.titleMedium)
            Text(
                "You usually get through about $perWeek a week, so it may be nearly gone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAnswer(CheckAnswer.PLENTY) }) { Text("Plenty left") }
                TextButton(onClick = { onAnswer(CheckAnswer.LOW) }) { Text("Running low") }
                TextButton(onClick = { onAnswer(CheckAnswer.OUT) }) { Text("All gone") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
