package dev.radiocycle.llmhub.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.radiocycle.llmhub.data.model.RotationStrategy
import dev.radiocycle.llmhub.data.model.SearchBackend
import dev.radiocycle.llmhub.data.model.ThemeMode
import dev.radiocycle.llmhub.data.repo.SettingsRepository
import dev.radiocycle.llmhub.tools.RootAccess
import dev.radiocycle.llmhub.tools.WorkspaceManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repository: SettingsRepository, workspace: WorkspaceManager) {
    val settings by repository.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Generation")

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { prompt -> repository.update { it.copy(systemPrompt = prompt) } },
                label = { Text("System prompt") },
                placeholder = { Text("Applies to every provider") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            SliderRow(
                label = "Temperature",
                value = settings.temperature,
                valueLabel = String.format("%.2f", settings.temperature),
                range = 0f..2f,
                steps = 19,
                onChange = { value -> repository.update { it.copy(temperature = value) } },
            )

            OutlinedTextField(
                value = settings.maxTokens.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.coerceIn(64, 200_000)?.let { tokens ->
                        repository.update { it.copy(maxTokens = tokens) }
                    }
                },
                label = { Text("Max output tokens") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Section("Rotation")

            Text(
                settings.rotation.strategy.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotationStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = settings.rotation.strategy == strategy,
                        onClick = {
                            repository.update { it.copy(rotation = it.rotation.copy(strategy = strategy)) }
                        },
                        label = { Text(strategy.label) },
                    )
                }
            }

            Text(
                "Attempts below cap transport retries only; credential failures always walk the " +
                    "whole pool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderRow(
                label = "Max attempts per turn",
                value = settings.rotation.maxAttempts.toFloat(),
                valueLabel = settings.rotation.maxAttempts.toString(),
                range = 1f..10f,
                steps = 8,
                onChange = { value ->
                    repository.update {
                        it.copy(rotation = it.rotation.copy(maxAttempts = value.roundToInt()))
                    }
                },
            )

            SliderRow(
                label = "Cooldown after failure",
                value = settings.rotation.cooldownSeconds.toFloat(),
                valueLabel = "${settings.rotation.cooldownSeconds}s",
                range = 0f..300f,
                steps = 0,
                onChange = { value ->
                    repository.update {
                        it.copy(rotation = it.rotation.copy(cooldownSeconds = value.roundToInt()))
                    }
                },
            )

            ToggleRow(
                title = "Seamless mid-stream handoff",
                subtitle = "If a provider dies after tokens arrived, hand the partial answer to the " +
                    "next one and continue instead of restarting",
                checked = settings.rotation.midStreamHandoff,
                onChange = { enabled ->
                    repository.update { it.copy(rotation = it.rotation.copy(midStreamHandoff = enabled)) }
                },
            )
            ToggleRow(
                title = "Rotate keys on 401 / 402 / 403 / 429",
                subtitle = "Walk the whole key pool silently — nothing is reported until the last " +
                    "key of the last provider has been tried",
                checked = settings.rotation.rotateOnKeyError,
                onChange = { enabled ->
                    repository.update { it.copy(rotation = it.rotation.copy(rotateOnKeyError = enabled)) }
                },
            )
            ToggleRow(
                title = "Rotate on server and network errors",
                subtitle = "HTTP 5xx, timeouts, broken connections",
                checked = settings.rotation.rotateOnServerError,
                onChange = { enabled ->
                    repository.update { it.copy(rotation = it.rotation.copy(rotateOnServerError = enabled)) }
                },
            )
            ToggleRow(
                title = "Rotate on missing model",
                subtitle = "Endpoint does not serve the requested model",
                checked = settings.rotation.rotateOnModelMissing,
                onChange = { enabled ->
                    repository.update { it.copy(rotation = it.rotation.copy(rotateOnModelMissing = enabled)) }
                },
            )

            Section("Tools")

            ToggleRow(
                title = "web_search",
                subtitle = "Search the web and return ranked results",
                checked = settings.tools.webSearchEnabled,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(webSearchEnabled = enabled)) }
                },
            )
            ToggleRow(
                title = "web_fetch",
                subtitle = "Fetch a URL and extract readable text",
                checked = settings.tools.webFetchEnabled,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(webFetchEnabled = enabled)) }
                },
            )
            ToggleRow(
                title = "exec_js",
                subtitle = "Run JavaScript in a sandboxed engine",
                checked = settings.tools.execJsEnabled,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(execJsEnabled = enabled)) }
                },
            )
            ToggleRow(
                title = "File tools",
                subtitle = "read_file, write_file, edit_file, delete_file, list_files",
                checked = settings.tools.fileToolsEnabled,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(fileToolsEnabled = enabled)) }
                },
            )
            ToggleRow(
                title = "shell",
                subtitle = "Run real shell commands on the device — grant carefully",
                checked = settings.tools.shellEnabled,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(shellEnabled = enabled)) }
                },
            )

            Text(
                "Search backend",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = settings.tools.searchBackend == backend,
                        onClick = {
                            repository.update { it.copy(tools = it.tools.copy(searchBackend = backend)) }
                        },
                        label = { Text(backend.label) },
                    )
                }
            }

            if (settings.tools.searchBackend.needsKey) {
                OutlinedTextField(
                    value = settings.tools.searchApiKey,
                    onValueChange = { key ->
                        repository.update { it.copy(tools = it.tools.copy(searchApiKey = key)) }
                    },
                    label = { Text("${settings.tools.searchBackend.label} API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (settings.tools.searchBackend == SearchBackend.SEARXNG) {
                OutlinedTextField(
                    value = settings.tools.searxngUrl,
                    onValueChange = { url ->
                        repository.update { it.copy(tools = it.tools.copy(searxngUrl = url)) }
                    },
                    label = { Text("SearXNG instance URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SliderRow(
                label = "Tool rounds per turn",
                value = settings.tools.maxToolIterations.toFloat(),
                valueLabel = settings.tools.maxToolIterations.toString(),
                range = 1f..20f,
                steps = 18,
                onChange = { value ->
                    repository.update { it.copy(tools = it.tools.copy(maxToolIterations = value.roundToInt())) }
                },
            )
            SliderRow(
                label = "JavaScript timeout",
                value = settings.tools.jsTimeoutMs.toFloat(),
                valueLabel = "${settings.tools.jsTimeoutMs} ms",
                range = 500f..30_000f,
                steps = 0,
                onChange = { value ->
                    repository.update { it.copy(tools = it.tools.copy(jsTimeoutMs = value.toLong())) }
                },
            )

            Section("Workspace & shell")

            val defaultWorkspace = remember { workspace.defaultRoot().absolutePath }
            OutlinedTextField(
                value = settings.tools.workspacePath,
                onValueChange = { path ->
                    repository.update { it.copy(tools = it.tools.copy(workspacePath = path.trim())) }
                },
                label = { Text("Workspace directory") },
                placeholder = { Text(defaultWorkspace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (settings.tools.workspacePath.isBlank()) {
                    "Blank → app-private default: $defaultWorkspace"
                } else {
                    "File tools resolve relative paths here; the shell starts here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    repository.update { it.copy(tools = it.tools.copy(workspacePath = "")) }
                }) { Text("App files (default)") }
                OutlinedButton(onClick = {
                    repository.update {
                        it.copy(tools = it.tools.copy(workspacePath = workspace.sharedStorageRoot().absolutePath))
                    }
                }) { Text("Shared storage") }
            }

            ToggleRow(
                title = "Restrict to workspace",
                subtitle = "Refuse file paths that climb outside the workspace. Turn off for a " +
                    "full-device agent.",
                checked = settings.tools.restrictToWorkspace,
                onChange = { enabled ->
                    repository.update { it.copy(tools = it.tools.copy(restrictToWorkspace = enabled)) }
                },
            )

            // Root status is probed once, off the main thread — the probe itself may prompt.
            var rootState by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(settings.tools.shellUseRoot) {
                if (settings.tools.shellUseRoot) {
                    rootState = withContext(Dispatchers.IO) { RootAccess.isGranted() }
                } else {
                    rootState = null
                }
            }
            ToggleRow(
                title = "Run shell as root",
                subtitle = when {
                    !RootAccess.binaryPresent() -> "No su binary found — device does not appear rooted"
                    rootState == true -> "Root granted — shell runs with ${RootAccess.modeLabel() ?: "su"}"
                    rootState == false -> "su present but access was denied"
                    else -> "Use su (mount master) so writes land in the global namespace"
                },
                checked = settings.tools.shellUseRoot,
                onChange = { enabled ->
                    RootAccess.invalidate()
                    repository.update { it.copy(tools = it.tools.copy(shellUseRoot = enabled)) }
                },
            )
            SliderRow(
                label = "Shell timeout",
                value = settings.tools.shellTimeoutMs.toFloat(),
                valueLabel = "${settings.tools.shellTimeoutMs / 1000}s",
                range = 1_000f..600_000f,
                steps = 0,
                onChange = { value ->
                    repository.update { it.copy(tools = it.tools.copy(shellTimeoutMs = value.toLong())) }
                },
            )

            Section("Appearance")

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { repository.update { it.copy(themeMode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            ToggleRow(
                title = "Markdown + LaTeX rendering",
                subtitle = "Tables, fenced code and TeX formulas typeset with KaTeX. Turn off for " +
                    "the lightweight renderer.",
                checked = settings.richRendering,
                onChange = { enabled -> repository.update { it.copy(richRendering = enabled) } },
            )
            ToggleRow(
                title = "Dynamic color",
                subtitle = "Derive the palette from the wallpaper (Android 12+)",
                checked = settings.dynamicColor,
                onChange = { enabled -> repository.update { it.copy(dynamicColor = enabled) } },
            )

            Section("About")
            Text(
                "LLM Hub — one interface over OpenAI, Anthropic, Google and any compatible endpoint, " +
                    "with automatic failover and built-in tools. Keys are stored in this app's private " +
                    "storage and are only ever sent to the provider they belong to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
