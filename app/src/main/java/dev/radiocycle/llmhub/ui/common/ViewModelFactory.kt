package dev.radiocycle.llmhub.ui.common

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.radiocycle.llmhub.AppContainer
import dev.radiocycle.llmhub.ui.chat.ChatViewModel
import dev.radiocycle.llmhub.ui.providers.ProvidersViewModel

fun appViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { ChatViewModel(container) }
    initializer { ProvidersViewModel(container) }
}
