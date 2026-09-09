package dev.radiocycle.llmhub.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.radiocycle.llmhub.AppContainer
import dev.radiocycle.llmhub.data.model.ChatMessage
import dev.radiocycle.llmhub.data.model.Conversation
import dev.radiocycle.llmhub.data.model.Provider
import dev.radiocycle.llmhub.data.model.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val pinnedProviderId: String? = null,
    val pinnedModel: String? = null,
    val notice: String? = null,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = container.conversations.conversations
    val providers: StateFlow<List<Provider>> = container.providers.providers

    private var streamJob: Job? = null

    init {
        val existing = container.conversations.conversations.value.firstOrNull()
        if (existing != null) open(existing.id) else newChat()
    }

    fun open(conversationId: String) {
        stop()
        val conversation = container.conversations.byId(conversationId) ?: return
        _state.value = ChatUiState(
            conversationId = conversation.id,
            title = conversation.title,
            messages = conversation.messages,
            pinnedProviderId = conversation.pinnedProviderId,
            pinnedModel = conversation.pinnedModel,
        )
    }

    fun newChat() {
        stop()
        val previous = _state.value
        val conversation = container.conversations.create()
        _state.value = ChatUiState(
            conversationId = conversation.id,
            title = conversation.title,
            // Carry the model choice over to the new chat — it is nearly always what you want.
            pinnedProviderId = previous.pinnedProviderId,
            pinnedModel = previous.pinnedModel,
        )
        persistPin()
    }

    fun deleteConversation(id: String) {
        container.conversations.delete(id)
        if (_state.value.conversationId == id) {
            val next = container.conversations.conversations.value.firstOrNull()
            if (next != null) open(next.id) else newChat()
        }
    }

    fun rename(title: String) {
        val id = _state.value.conversationId ?: return
        container.conversations.rename(id, title)
        _state.value = _state.value.copy(title = title)
    }

    fun pin(providerId: String?, model: String?) {
        _state.value = _state.value.copy(pinnedProviderId = providerId, pinnedModel = model)
        persistPin()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isStreaming) return

        if (container.providers.rotationPool().isEmpty()) {
            _state.value = _state.value.copy(
                notice = "Add and enable at least one provider before sending."
            )
            return
        }

        val conversationId = _state.value.conversationId ?: container.conversations.create().id
        val history = _state.value.messages + ChatMessage(role = Role.USER, content = prompt)
        _state.value = _state.value.copy(
            conversationId = conversationId,
            messages = history,
            isStreaming = true,
            notice = null,
        )
        persist(conversationId, history)

        streamJob = viewModelScope.launch {
            try {
                container.chatEngine
                    .run(history, _state.value.pinnedProviderId, _state.value.pinnedModel)
                    .collect { produced -> _state.value = _state.value.copy(messages = history + produced) }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages + ChatMessage(
                        role = Role.ASSISTANT,
                        error = t.message ?: "Request failed",
                    )
                )
            } finally {
                _state.value = _state.value.copy(isStreaming = false)
                persist(conversationId, _state.value.messages)
                refreshTitle(conversationId)
            }
        }
    }

    /** Keeps whatever has streamed so far — a stopped answer is still worth reading. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        if (_state.value.isStreaming) {
            _state.value = _state.value.copy(isStreaming = false)
            _state.value.conversationId?.let { persist(it, _state.value.messages) }
        }
    }

    fun retryLast() {
        val messages = _state.value.messages
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        if (lastUser < 0 || _state.value.isStreaming) return
        val prompt = messages[lastUser].content
        val trimmed = messages.take(lastUser)
        _state.value = _state.value.copy(messages = trimmed)
        _state.value.conversationId?.let { persist(it, trimmed) }
        send(prompt)
    }

    private fun persist(conversationId: String, messages: List<ChatMessage>) {
        container.conversations.setMessages(conversationId, messages)
    }

    private fun persistPin() {
        val state = _state.value
        state.conversationId?.let {
            container.conversations.setPinned(it, state.pinnedProviderId, state.pinnedModel)
        }
    }

    private fun refreshTitle(conversationId: String) {
        container.conversations.byId(conversationId)?.let { conversation ->
            _state.value = _state.value.copy(title = conversation.title)
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
