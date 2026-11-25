package com.friday.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.ui.FridayService
import com.friday.assistant.ui.theme.FridayTheme
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.viewmodel.FridayViewModel

sealed interface FridayDestination {
    data object Home : FridayDestination
    data object Assistant : FridayDestination
    data class Vault(val initialSubTab: Int = 0) : FridayDestination
    data object Settings : FridayDestination
    data object ThemeStudio : FridayDestination
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: FridayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FridayLogger.i(TAG, "MainActivity onCreate")
        handleTriggerIntent(intent)

        setContent {
            FridayTheme {
                val colors = LocalFridayColors.current
                Surface(color = colors.bg) {
                    FridayAppShell(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTriggerIntent(intent)
    }

    private fun handleTriggerIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val autoStart = intent.getBooleanExtra("EXTRA_AUTO_START_MIC", false)
        if (action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "com.friday.assistant.ACTION_TRIGGER_ASSISTANT" ||
            autoStart
        ) {
            FridayLogger.i(TAG, "MainActivity received trigger intent: $action (autoStart=$autoStart)")
            FridayService.triggerGestureActivation()
            try {
                moveTaskToBack(true)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun FridayAppShell(viewModel: FridayViewModel) {
    val colors = LocalFridayColors.current
    val backStack = remember { mutableStateListOf<FridayDestination>(FridayDestination.Home) }
    var vaultInitialTab by remember { mutableIntStateOf(0) }
    var pendingChatPrompt by remember { mutableStateOf<String?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemStatus()
    }

    val current = backStack.lastOrNull() ?: FridayDestination.Home
    val isTopLevel = current is FridayDestination.Home ||
        current is FridayDestination.Assistant ||
        current is FridayDestination.Vault

    BackHandler(enabled = backStack.size > 1) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun selectTab(dest: FridayDestination) {
        backStack.clear()
        backStack.add(dest)
    }

    fun pushDestination(dest: FridayDestination) {
        if (backStack.lastOrNull() != dest) {
            backStack.add(dest)
        }
    }

    Scaffold(
        containerColor = colors.bg,
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(
                    containerColor = colors.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .border(
                            width = 1.dp,
                            color = colors.cardBorder,
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        )
                ) {
                    val homeSelected = current is FridayDestination.Home
                    val assistantSelected = current is FridayDestination.Assistant
                    val vaultSelected = current is FridayDestination.Vault

                    NavigationBarItem(
                        selected = homeSelected,
                        onClick = { selectTab(FridayDestination.Home) },
                        icon = {
                            Icon(
                                imageVector = if (homeSelected) Icons.Rounded.Home else Icons.Default.Home,
                                contentDescription = "Home"
                            )
                        },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary,
                            indicatorColor = colors.primaryMuted
                        )
                    )
                    NavigationBarItem(
                        selected = assistantSelected,
                        onClick = { selectTab(FridayDestination.Assistant) },
                        icon = {
                            Icon(
                                imageVector = if (assistantSelected) Icons.Rounded.ChatBubble else Icons.Default.ChatBubbleOutline,
                                contentDescription = "Assistant"
                            )
                        },
                        label = { Text("Assistant") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary,
                            indicatorColor = colors.primaryMuted
                        )
                    )
                    NavigationBarItem(
                        selected = vaultSelected,
                        onClick = { selectTab(FridayDestination.Vault(vaultInitialTab)) },
                        icon = {
                            Icon(
                                imageVector = if (vaultSelected) Icons.Rounded.Folder else Icons.Default.FolderOpen,
                                contentDescription = "Vault"
                            )
                        },
                        label = { Text("Vault") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary,
                            indicatorColor = colors.primaryMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            Crossfade(targetState = current, label = "RouteTransition") { route ->
                when (route) {
                    is FridayDestination.Home -> HomeDashboardScreen(
                        viewModel = viewModel,
                        onOpenSettings = { pushDestination(FridayDestination.Settings) },
                        onOpenThemeStudio = { pushDestination(FridayDestination.ThemeStudio) },
                        onOpenVaultTab = { tab ->
                            vaultInitialTab = tab
                            selectTab(FridayDestination.Vault(tab))
                        },
                        onAskPrompt = { prompt ->
                            pendingChatPrompt = prompt
                            selectTab(FridayDestination.Assistant)
                        },
                        onRequestCalendarPermission = {
                            calendarPermissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            )
                        }
                    )
                    is FridayDestination.Assistant -> AssistantChatScreen(
                        viewModel = viewModel,
                        initialPrompt = pendingChatPrompt,
                        onConsumedInitialPrompt = { pendingChatPrompt = null }
                    )
                    is FridayDestination.Vault -> VaultScreen(
                        viewModel = viewModel,
                        initialSubTab = route.initialSubTab,
                        onRunRoutine = { phrase ->
                            pendingChatPrompt = phrase
                            selectTab(FridayDestination.Assistant)
                        }
                    )
                    is FridayDestination.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                        onOpenThemeStudio = { pushDestination(FridayDestination.ThemeStudio) }
                    )
                    is FridayDestination.ThemeStudio -> ThemeStudioScreen(
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                    )
                }
            }
        }
    }
}
