package dev.radiocycle.llmhub.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.radiocycle.llmhub.data.model.ApiMode
import dev.radiocycle.llmhub.data.model.HeaderEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(viewModel: ProvidersViewModel, onClose: () -> Unit) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val provider = draft ?: return
    var keyVisible by remember { mutableStateOf(false) }
    var modelsText by remember(provider.id) { mutableStateOf(provider.models.joinToString("\n")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (provider.name.isBlank()) "New provider" else provider.name) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.discardDraft()
                        onClose()
                    }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { name -> viewModel.editDraft { it.copy(name = name) } },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("API mode")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ApiMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = provider.apiMode == mode,
                        onClick = { viewModel.editDraft { it.copy(apiMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ApiMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
            Text(
                text = when (provider.apiMode) {
                    ApiMode.OPENAI -> "POST {base}/chat/completions · Authorization: Bearer <key>"
                    ApiMode.ANTHROPIC -> "POST {base}/v1/messages · x-api-key + anthropic-version"
                    ApiMode.GOOGLE -> "POST {base}/v1beta/models/{model}:streamGenerateContent · x-goog-api-key"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { url -> viewModel.editDraft { it.copy(baseUrl = url) } },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = provider.apiKey,
                onValueChange = { key -> viewModel.editDraft { it.copy(apiKey = key) } },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Custom headers")
            provider.headers.forEachIndexed { index, entry ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = entry.name,
                        onValueChange = { name ->
                            viewModel.editDraft { it.copy(headers = it.headers.replaceAt(index) { h -> h.copy(name = name) }) }
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = entry.value,
                        onValueChange = { value ->
                            viewModel.editDraft { it.copy(headers = it.headers.replaceAt(index) { h -> h.copy(value = value) }) }
                        },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f),
                    )
                    IconButton(onClick = {
                        viewModel.editDraft { it.copy(headers = it.headers.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Rounded.Close, contentDescription = "Remove header", Modifier.size(18.dp)) }
                }
            }
            OutlinedButton(
                onClick = { viewModel.editDraft { it.copy(headers = it.headers + HeaderEntry()) } },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add header")
            }

            SectionLabel("Models")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::fetchModels, enabled = !probe.running) {
                    if (probe.running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("Fetch from endpoint")
                }
                probe.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (probe.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            OutlinedTextField(
                value = modelsText,
                onValueChange = { text ->
                    modelsText = text
                    viewModel.editDraft { it.copy(models = text.lines().map(String::trim).filter(String::isNotBlank)) }
                },
                label = { Text("Model IDs (one per line)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (provider.models.isNotEmpty()) {
                SectionLabel("Default model")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    provider.models.take(40).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { model ->
                                FilterChip(
                                    selected = provider.defaultModel == model,
                                    onClick = { viewModel.editDraft { it.copy(defaultModel = model) } },
                                    label = { Text(model, maxLines = 1) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            SectionLabel("Behaviour")
            ToggleRow(
                title = "Enabled",
                subtitle = "Include this endpoint in rotation",
                checked = provider.enabled,
                onChange = { enabled -> viewModel.editDraft { it.copy(enabled = enabled) } },
            )
            ToggleRow(
                title = "Supports tools",
                subtitle = "Send tool definitions to this endpoint",
                checked = provider.supportsTools,
                onChange = { supports -> viewModel.editDraft { it.copy(supportsTools = supports) } },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = provider.weight.toString(),
                    onValueChange = { value ->
                        viewModel.editDraft { it.copy(weight = value.toIntOrNull()?.coerceIn(1, 100) ?: it.weight) }
                    },
                    label = { Text("Weight") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = provider.timeoutSeconds.toString(),
                    onValueChange = { value ->
                        viewModel.editDraft {
                            it.copy(timeoutSeconds = value.toIntOrNull()?.coerceIn(5, 900) ?: it.timeoutSeconds)
                        }
                    },
                    label = { Text("Timeout (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = provider.notes,
                onValueChange = { notes -> viewModel.editDraft { it.copy(notes = notes) } },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { if (viewModel.saveDraft()) onClose() },
                enabled = provider.name.isNotBlank() && provider.baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) { Text("Save provider") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
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

private fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) transform(item) else item }
