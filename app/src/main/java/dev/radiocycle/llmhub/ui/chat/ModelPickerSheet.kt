package dev.radiocycle.llmhub.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.radiocycle.llmhub.data.model.Provider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<Provider>,
    selectedProviderId: String?,
    selectedModel: String?,
    onPick: (providerId: String?, model: String?) -> Unit,
    onDismiss: () -> Unit,
    onManageProviders: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Route", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onManageProviders) { Text("Manage") }
                }
            }

            item {
                RouteRow(
                    title = "Automatic rotation",
                    subtitle = "Use the whole pool in the configured order",
                    selected = selectedProviderId == null,
                    leading = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
                    onClick = { onPick(null, null) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            providers.forEach { provider ->
                item {
                    Text(
                        text = "${provider.name}  ·  ${provider.apiMode.label}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                val models = provider.models.ifEmpty { listOfNotNull(provider.defaultModel.takeIf { it.isNotBlank() }) }
                if (models.isEmpty()) {
                    item {
                        Text(
                            "No models — open the provider and fetch them",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                        )
                    }
                }
                for (model in models) {
                    item(key = "${provider.id}:$model") {
                        RouteRow(
                            title = model,
                            subtitle = if (provider.enabled) null else "provider disabled",
                            selected = provider.id == selectedProviderId && model == selectedModel,
                            leading = null,
                            onClick = { onPick(provider.id, model) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun RouteRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    leading: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(20.dp))
        }
    }
}
