package com.pantrypilot.app.recipes

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pantrypilot.app.expiryShort
import com.pantrypilot.data.RankedRecipe
import com.pantrypilot.domain.recipe.Coverage
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(viewModel: RecipesViewModel = hiltViewModel()) {
    val ranked by viewModel.ranked.collectAsStateWithLifecycle()
    val names by viewModel.foodNames.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val open = openId?.let { id -> ranked?.firstOrNull { it.entry.id == id } }
    if (open != null) {
        BackHandler { openId = null }
        RecipeDetail(
            recipe = open,
            names = names,
            snackbar = snackbar,
            onBack = { openId = null },
            onCook = {
                viewModel.cook(open.entry.id)
                openId = null
            },
            onAddMissing = { viewModel.addMissing(open.entry.id) },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Recipes") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = ranked
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val hasFood = list.any { recipe -> recipe.match.coverage.any { it.uses.isNotEmpty() } }
                Text(
                    text = if (hasFood) {
                        "Ranked by how much food about to expire each one uses up."
                    } else {
                        "Add food to your pantry to see what you can cook with it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.entry.id }) { recipe ->
                RecipeCard(recipe, names, onClick = { openId = recipe.entry.id })
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RankedRecipe, names: Map<String, String>, onClick: () -> Unit) {
    val match = recipe.match
    val rescued = match.coverage
        .filter { it.uses.isNotEmpty() && it.soonestExpiry != null }
        .sortedBy { it.soonestExpiry }
        .take(3)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(recipe.entry.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${recipe.entry.minutes} min · serves ${recipe.entry.servings}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rescued.isNotEmpty()) {
                Text(
                    text = "Uses " + rescued.joinToString { "${names[it.ingredientId] ?: it.ingredientId} (${expiry(it)})" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rescued.any(::isUrgent)) MaterialTheme.colorScheme.error else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val lacking = match.coverage.filter { it.shortfall > 0.0 }
            Text(
                text = if (lacking.isEmpty()) {
                    "You have everything"
                } else {
                    "Need " + lacking.joinToString { names[it.ingredientId] ?: it.ingredientId }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (lacking.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetail(
    recipe: RankedRecipe,
    names: Map<String, String>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCook: () -> Unit,
    onAddMissing: () -> Unit,
) {
    val entry = recipe.entry
    val match = recipe.match
    val lacking = match.coverage.filter { it.shortfall > 0.0 }
    var confirming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lacking.isNotEmpty()) {
                    OutlinedButton(onClick = onAddMissing, modifier = Modifier.fillMaxWidth()) {
                        Text("Add what you need to the shopping list")
                    }
                }
                Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) { Text("Cook this") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${entry.minutes} min · serves ${entry.servings}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text("Ingredients", style = MaterialTheme.typography.titleMedium) }
            items(entry.ingredients) { line ->
                val coverage = match.coverage.firstOrNull { it.ingredientId == line.id }
                IngredientRow(line.text, coverage)
            }
            if (entry.extras.isNotEmpty()) {
                item {
                    Text(
                        "From the cupboard: ${entry.extras.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Text("Method", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            itemsIndexed(entry.steps) { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Cook ${entry.title}?") },
            text = {
                Text(
                    buildString {
                        append("The ingredients come out of your pantry, soonest-expiring first.")
                        if (lacking.isNotEmpty()) {
                            append(" You don't have enough ")
                            append(lacking.joinToString { names[it.ingredientId] ?: it.ingredientId })
                            append(", so only what you have is taken.")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onCook()
                }) { Text("Cook") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IngredientRow(text: String, coverage: Coverage?) {
    val (icon, tint, status) = when {
        coverage == null || coverage.missing ->
            Triple(Icons.Filled.RadioButtonUnchecked, MaterialTheme.colorScheme.outline, "Not in your pantry")
        coverage.short ->
            Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.tertiary, "Not quite enough")
        else -> Triple(
            Icons.Filled.CheckCircle,
            if (isUrgent(coverage)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            coverage.soonestExpiry?.let { "In your pantry · ${expiryPhrase(coverage)}" } ?: "In your pantry",
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun expiry(coverage: Coverage): String = coverage.soonestExpiry?.let { expiryShort(it.toEpochMilli()) }.orEmpty()

/** "expires tomorrow", "expires in 5 days". */
private fun expiryPhrase(coverage: Coverage): String {
    val short = expiry(coverage)
    return if (short.endsWith("days")) "expires in $short" else "expires $short"
}

/** Expiring within two days, the same window the pantry list marks in red. */
private fun isUrgent(coverage: Coverage): Boolean {
    val expiresAt = coverage.soonestExpiry?.toEpochMilli() ?: return false
    return expiresAt - System.currentTimeMillis() <= TimeUnit.DAYS.toMillis(2)
}
