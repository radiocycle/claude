package dev.radiocycle.llmhub.data.repo

import android.content.Context
import dev.radiocycle.llmhub.data.model.AppSettings
import dev.radiocycle.llmhub.data.store.JsonFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val store = JsonFileStore(
        context = context,
        fileName = "settings.json",
        serializer = AppSettings.serializer(),
        default = { AppSettings() },
        scope = scope,
    )

    val settings: StateFlow<AppSettings> = store.state
    val current: AppSettings get() = store.value

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)
}
