package dev.radiocycle.llmhub.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.radiocycle.llmhub.data.model.Provider
import dev.radiocycle.llmhub.data.model.Role
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenProviders: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showModelPicker by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all chats?") },
            text = { Text("This will delete all conversations and start a fresh chat.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllConversations()
                        showClearAllConfirm = false
                        scope.launch { drawerState.close() }
                    },
                ) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swipe only closes an open drawer. Edge-swipe-to-open would fight every horizontal drag
        // on the screen — scrolling a wide table or a long formula, most of all.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Conversations",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (conversations.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear all chats",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    selected = false,
                    onClick = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(conversations, key = { it.id }) { conversation ->
                        NavigationDrawerItem(
                            label = { Text(conversation.title, maxLines = 1) },
                            selected = conversation.id == state.conversationId,
                            onClick = {
                                viewModel.open(conversation.id)
                                scope.launch { drawerState.close() }
                            },
                            badge = {
                                IconButton(onClick = { viewModel.deleteConversation(conversation.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete chat")
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                if (conversations.size > 1) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = {
                            Text(
                                "Clear all chats",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        icon = {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        selected = false,
                        onClick = { showClearAllConfirm = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            ActiveRouteLabel(state, providers)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Conversations")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showModelPicker = true }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Choose model")
                        }
                        IconButton(onClick = viewModel::retryLast, enabled = !state.isStreaming) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Retry last turn")
                        }
                        IconButton(onClick = viewModel::newChat) {
                            Icon(Icons.Rounded.Add, contentDescription = "New chat")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            },
            bottomBar = {
                Composer(
                    isStreaming = state.isStreaming,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                key(state.conversationId) {
                    if (state.messages.isEmpty()) {
                        EmptyState(
                            hasProviders = providers.any { it.enabled },
                            onOpenProviders = onOpenProviders,
                        )
                    } else {
                        MessageList(state, richRendering = settings.richRendering)
                    }
                }

                state.notice?.let { notice ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(notice, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(12.dp))
                            TextButton(onClick = viewModel::dismissNotice) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            selectedProviderId = state.pinnedProviderId,
            selectedModel = state.pinnedModel,
            onPick = { providerId, model ->
                viewModel.pin(providerId, model)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
            onManageProviders = {
                showModelPicker = false
                onOpenProviders()
            },
        )
    }
}

@Composable
private fun ActiveRouteLabel(state: ChatUiState, providers: List<Provider>) {
    val provider = providers.firstOrNull { it.id == state.pinnedProviderId }
    val label = when {
        provider != null -> "${provider.name} · ${state.pinnedModel ?: provider.defaultModel}"
        providers.count { it.enabled } > 0 -> "Auto-rotation · ${providers.count { it.enabled }} providers"
        else -> "No providers configured"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun MessageList(state: ChatUiState, richRendering: Boolean) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    val canScrollForward by remember {
        derivedStateOf { listState.canScrollForward }
    }

    // Pause autoScroll immediately if the user touches and drags away from the bottom
    LaunchedEffect(listState) {
        snapshotFlow { isDragged to listState.canScrollForward }
            .collect { (dragged, canForward) ->
                if (dragged && canForward) {
                    autoScroll = false
                }
            }
    }

    // Resume autoScroll when user scrolls back to the very bottom and stops
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (inProgress, canForward) ->
                if (!inProgress && !canForward) {
                    autoScroll = true
                }
            }
    }

    // On new message (e.g. user sends message), re-enable auto-scroll and scroll down
    var lastMessageCount by remember { mutableStateOf(state.messages.size) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastMessageCount) {
            autoScroll = true
            val target = state.messages.size + if (state.isStreaming) 1 else 0
            listState.animateScrollToItem(target)
        }
        lastMessageCount = state.messages.size
    }

    // When opening a chat with messages, instantly place viewport at the bottom
    LaunchedEffect(Unit) {
        if (state.messages.isNotEmpty()) {
            val target = state.messages.size + if (state.isStreaming) 1 else 0
            listState.scrollToItem(target)
        }
    }

    // Follow streaming output IF auto-scroll is active and user is not touching/scrolling
    LaunchedEffect(state.messages.lastOrNull()?.content?.length, state.isStreaming) {
        if (autoScroll && !isDragged && !listState.isScrollInProgress && state.messages.isNotEmpty()) {
            val target = state.messages.size + if (state.isStreaming) 1 else 0
            listState.scrollToItem(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(state.messages, key = { _, it -> it.id }) { index, message ->
                val showProviderBadge = remember(state.messages, index) {
                    if (message.role != Role.ASSISTANT || message.providerName == null) {
                        false
                    } else {
                        val lastUser = state.messages.subList(0, index).indexOfLast { it.role == Role.USER }
                        val lastAssistantIndex = state.messages.subList(0, index).indexOfLast { it.role == Role.ASSISTANT }
                        val lastAssistant = if (lastAssistantIndex >= 0) state.messages[lastAssistantIndex] else null
                        when {
                            lastUser > lastAssistantIndex -> true
                            lastAssistant == null -> true
                            lastAssistant.providerName != message.providerName || lastAssistant.model != message.model -> true
                            else -> false
                        }
                    }
                }

                val hasSubsequentToolResult = remember(state.messages, index) {
                    state.messages.getOrNull(index + 1)?.role == Role.TOOL
                }

                MessageItem(
                    message = message,
                    isLast = message.id == state.messages.lastOrNull()?.id,
                    isStreaming = state.isStreaming,
                    richRendering = richRendering,
                    showProviderBadge = showProviderBadge,
                    hasSubsequentToolResult = hasSubsequentToolResult,
                )
            }
            if (state.isStreaming) {
                item(key = "streaming_indicator") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Working…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(24.dp)) }
        }

        AnimatedVisibility(
            visible = canScrollForward,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    autoScroll = true
                    scope.launch {
                        val target = state.messages.size + if (state.isStreaming) 1 else 0
                        listState.animateScrollToItem(target)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(20.dp),
                    )
                    if (state.isStreaming) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(6.dp)
                                .align(Alignment.TopEnd),
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasProviders: Boolean, onOpenProviders: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(32.dp),
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(22.dp).size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (hasProviders) "Ask anything" else "Set up a provider first",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasProviders) {
                "Requests rotate across your enabled providers. If one fails mid-answer, the next one " +
                    "picks the reply up where it stopped."
            } else {
                "Add an API key for OpenAI, Anthropic, Gemini or any OpenAI-compatible endpoint."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasProviders) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onOpenProviders) { Text("Open providers") }
        }
    }
}

@Composable
private fun Composer(isStreaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            AnimatedVisibility(visible = true) {
                FilledIconButton(
                    onClick = {
                        if (isStreaming) {
                            onStop()
                        } else if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(if (isStreaming) 16.dp else 26.dp),
                    colors = if (isStreaming) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(
                        if (isStreaming) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = if (isStreaming) "Stop" else "Send",
                    )
                }
            }
        }
    }
}
