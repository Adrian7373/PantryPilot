package com.pantrypilot.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.pantrypilot.app.insights.InsightsScreen
import com.pantrypilot.app.pantry.PantryScreen
import com.pantrypilot.app.recipes.RecipesScreen
import com.pantrypilot.app.restock.RestockScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A tab a notification asked to open, until the shell has switched to it. */
    private val requestedTab = mutableStateOf<TopLevelTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedTab.value = tabFrom(intent)
        setContent {
            PantryPilotTheme {
                AppShell(requestedTab = requestedTab.value, onTabShown = { requestedTab.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = tabFrom(intent)
    }

    private fun tabFrom(intent: Intent?): TopLevelTab? =
        intent?.getStringExtra(EXTRA_TAB)?.let { name -> TopLevelTab.entries.firstOrNull { it.name == name } }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

enum class TopLevelTab(val label: String, val icon: ImageVector) {
    PANTRY("Pantry", Icons.Filled.Kitchen),
    RECIPES("Recipes", Icons.Filled.RestaurantMenu),
    RESTOCK("Restock", Icons.Filled.ShoppingCart),
    INSIGHTS("Insights", Icons.Filled.Insights),
}

@Composable
fun AppShell(requestedTab: TopLevelTab?, onTabShown: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(TopLevelTab.PANTRY) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onTabShown()
        }
    }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var onboarded by rememberSaveable { mutableStateOf(prefs.getBoolean(ONBOARDED, false)) }
    if (!onboarded) {
        OnboardingScreen(onDone = {
            prefs.edit().putBoolean(ONBOARDED, true).apply()
            onboarded = true
        })
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelTab.entries.forEach { entry ->
                item(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    icon = { Icon(entry.icon, contentDescription = null) },
                    label = { Text(entry.label) },
                )
            }
        },
    ) {
        when (tab) {
            TopLevelTab.PANTRY -> PantryScreen(onCookTonight = { tab = TopLevelTab.RECIPES })
            TopLevelTab.RECIPES -> RecipesScreen()
            TopLevelTab.RESTOCK -> RestockScreen()
            TopLevelTab.INSIGHTS -> InsightsScreen()
        }
    }
}

private const val ONBOARDED = "onboarded"

@Composable
fun PantryPilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
