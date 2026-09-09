package dev.radiocycle.llmhub.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.radiocycle.llmhub.AppContainer
import dev.radiocycle.llmhub.ui.chat.ChatScreen
import dev.radiocycle.llmhub.ui.chat.ChatViewModel
import dev.radiocycle.llmhub.ui.common.appViewModelFactory
import dev.radiocycle.llmhub.ui.providers.ProviderEditScreen
import dev.radiocycle.llmhub.ui.providers.ProvidersScreen
import dev.radiocycle.llmhub.ui.providers.ProvidersViewModel
import dev.radiocycle.llmhub.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Rounded.Chat),
    PROVIDERS("Providers", Icons.Rounded.Hub),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun AppShell(container: AppContainer) {
    val factory = remember(container) { appViewModelFactory(container) }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val providersViewModel: ProvidersViewModel = viewModel(factory = factory)

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var editingProvider by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = editingProvider) {
        providersViewModel.discardDraft()
        editingProvider = false
    }
    BackHandler(enabled = !editingProvider && tab != Tab.CHAT) { tab = Tab.CHAT }

    Scaffold(
        bottomBar = {
            if (!editingProvider) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            AnimatedContent(
                targetState = if (editingProvider) null else tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { target ->
                when (target) {
                    null -> ProviderEditScreen(
                        viewModel = providersViewModel,
                        onClose = { editingProvider = false },
                    )

                    Tab.CHAT -> ChatScreen(
                        viewModel = chatViewModel,
                        onOpenProviders = { tab = Tab.PROVIDERS },
                    )

                    Tab.PROVIDERS -> ProvidersScreen(
                        viewModel = providersViewModel,
                        onEdit = { editingProvider = true },
                    )

                    Tab.SETTINGS -> SettingsScreen(repository = container.settings)
                }
            }
        }
    }
}
