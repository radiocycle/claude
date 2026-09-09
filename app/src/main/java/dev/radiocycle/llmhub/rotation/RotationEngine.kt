package dev.radiocycle.llmhub.rotation

import android.util.Log
import dev.radiocycle.llmhub.data.model.EndpointHealth
import dev.radiocycle.llmhub.data.model.Provider
import dev.radiocycle.llmhub.data.model.RotationSettings
import dev.radiocycle.llmhub.data.model.RotationStrategy
import dev.radiocycle.llmhub.data.model.TokenUsage
import dev.radiocycle.llmhub.data.model.ToolCall
import dev.radiocycle.llmhub.data.repo.ProviderRepository
import dev.radiocycle.llmhub.data.repo.SettingsRepository
import dev.radiocycle.llmhub.net.ChatRequest
import dev.radiocycle.llmhub.net.ClientFactory
import dev.radiocycle.llmhub.net.LlmException
import dev.radiocycle.llmhub.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

sealed interface RotationEvent {
    data class Started(val provider: Provider, val model: String, val attempt: Int) : RotationEvent
    data class Text(val text: String) : RotationEvent
    data class Reasoning(val text: String) : RotationEvent
    data class Tools(val calls: List<ToolCall>) : RotationEvent
    /** A provider dropped out; [resumed] is true when partial text is being continued, not restarted. */
    data class Switched(
        val from: Provider,
        val to: Provider,
        val reason: String,
        val resumed: Boolean,
    ) : RotationEvent
    data class Finished(val provider: Provider, val model: String, val usage: TokenUsage?) : RotationEvent
    data class Failed(val message: String, val attempts: Int) : RotationEvent
}

/**
 * Picks an endpoint, streams from it, and — when it dies — moves the same turn onto the next
 * healthy endpoint. If tokens were already delivered, the partial answer is handed over as a
 * prefill so the user sees one continuous reply rather than a restart.
 */
class RotationEngine(
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
) {
    private val _health = MutableStateFlow<Map<String, EndpointHealth>>(emptyMap())
    val health: StateFlow<Map<String, EndpointHealth>> = _health.asStateFlow()

    private val roundRobinCursor = AtomicInteger(0)

    fun healthOf(providerId: String): EndpointHealth =
        _health.value[providerId] ?: EndpointHealth(providerId)

    fun clearHealth() {
        _health.value = emptyMap()
    }

    fun stream(request: ChatRequest, pinnedProviderId: String? = null): Flow<RotationEvent> = flow {
        val config = settings.current.rotation
        val pool = providers.rotationPool()
        if (pool.isEmpty()) {
            emit(RotationEvent.Failed("No providers configured. Add one in the Providers tab.", 0))
            return@flow
        }

        val tried = mutableSetOf<String>()
        val partial = StringBuilder()
        var attempt = 0
        var lastProvider: Provider? = null
        var lastError: String? = null

        while (attempt < config.maxAttempts) {
            val provider = pick(pool, tried, pinnedProviderId, attempt, config)
            if (provider == null) {
                lastError = lastError ?: "Every provider is unavailable or cooling down."
                break
            }
            tried += provider.id
            attempt++

            val model = provider.modelOrDefault(
                if (provider.id == pinnedProviderId) request.model else null
            ).ifBlank { request.model }

            if (model.isBlank()) {
                lastError = "${provider.name} has no model configured."
                markFailure(provider, lastError, config)
                continue
            }

            lastProvider?.let { previous ->
                emit(
                    RotationEvent.Switched(
                        from = previous,
                        to = provider,
                        reason = lastError.orEmpty(),
                        resumed = partial.isNotEmpty(),
                    )
                )
            }
            emit(RotationEvent.Started(provider, model, attempt))

            val attemptRequest = request.copy(
                model = model,
                tools = if (provider.supportsTools) request.tools else emptyList(),
                prefill = partial.toString().takeIf { it.isNotEmpty() && config.midStreamHandoff },
            )

            val startedAt = System.currentTimeMillis()
            try {
                var usage: TokenUsage? = null
                ClientFactory.forMode(provider.apiMode).stream(provider, attemptRequest).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            partial.append(event.text)
                            emit(RotationEvent.Text(event.text))
                        }
                        is StreamEvent.ReasoningDelta -> emit(RotationEvent.Reasoning(event.text))
                        is StreamEvent.ToolCalls -> emit(RotationEvent.Tools(event.calls))
                        is StreamEvent.Completed -> usage = event.usage
                    }
                }
                markSuccess(provider, System.currentTimeMillis() - startedAt)
                emit(RotationEvent.Finished(provider, model, usage))
                return@flow
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val error = t as? LlmException ?: LlmException(
                    t.message ?: "unknown error",
                    kind = LlmException.Kind.NETWORK,
                    cause = t,
                )
                Log.w(TAG, "${provider.name} failed: ${error.message}")
                markFailure(provider, error.message ?: error.shortLabel, config)
                lastError = "${provider.name}: ${error.shortLabel}"
                lastProvider = provider

                if (!shouldRotate(error, config)) {
                    emit(RotationEvent.Failed("${provider.name} — ${error.message}", attempt))
                    return@flow
                }
                if (partial.isNotEmpty() && !config.midStreamHandoff) {
                    emit(RotationEvent.Failed("${provider.name} — ${error.message} (mid-stream handoff off)", attempt))
                    return@flow
                }
            }
        }

        emit(
            RotationEvent.Failed(
                lastError?.let { "All $attempt attempt(s) failed. Last: $it" }
                    ?: "No provider could serve this request.",
                attempt,
            )
        )
    }

    // --- Selection ------------------------------------------------------------------------

    private fun pick(
        pool: List<Provider>,
        tried: Set<String>,
        pinnedProviderId: String?,
        attempt: Int,
        config: RotationSettings,
    ): Provider? {
        if (attempt == 0 && pinnedProviderId != null) {
            pool.firstOrNull { it.id == pinnedProviderId }?.let { return it }
        }

        val remaining = pool.filterNot { it.id in tried }
        if (remaining.isEmpty()) return null

        val now = System.currentTimeMillis()
        val healthy = remaining.filterNot { healthOf(it.id).isCoolingDown(now) }
        // Everything is cooling down: take the one that recovers soonest rather than giving up.
        val candidates = healthy.ifEmpty {
            remaining.sortedBy { healthOf(it.id).cooldownUntil }.take(1)
        }

        return when (config.strategy) {
            RotationStrategy.FAILOVER -> candidates.first()

            RotationStrategy.ROUND_ROBIN ->
                candidates[(roundRobinCursor.getAndIncrement().mod(candidates.size))]

            RotationStrategy.LEAST_USED -> candidates.minByOrNull { healthOf(it.id).lastUsedAt }

            RotationStrategy.WEIGHTED -> {
                val total = candidates.sumOf { it.weight.coerceAtLeast(1) }
                var roll = Random.nextInt(total)
                candidates.firstOrNull { provider ->
                    roll -= provider.weight.coerceAtLeast(1)
                    roll < 0
                } ?: candidates.first()
            }
        }
    }

    private fun shouldRotate(error: LlmException, config: RotationSettings): Boolean = when (error.kind) {
        LlmException.Kind.RATE_LIMIT -> config.rotateOnRateLimit
        LlmException.Kind.SERVER, LlmException.Kind.NETWORK, LlmException.Kind.PARSE -> config.rotateOnServerError
        LlmException.Kind.AUTH -> config.rotateOnAuthError
        LlmException.Kind.MODEL_MISSING -> config.rotateOnModelMissing
        // A malformed request will fail identically everywhere; surface it instead of burning keys.
        LlmException.Kind.BAD_REQUEST -> false
        LlmException.Kind.CANCELLED -> false
    }

    // --- Health ---------------------------------------------------------------------------

    private fun markSuccess(provider: Provider, latencyMs: Long) = mutate(provider.id) { health ->
        health.copy(
            successes = health.successes + 1,
            consecutiveFailures = 0,
            cooldownUntil = 0L,
            lastUsedAt = System.currentTimeMillis(),
            lastLatencyMs = latencyMs,
            lastError = null,
        )
    }

    private fun markFailure(provider: Provider, reason: String, config: RotationSettings) =
        mutate(provider.id) { health ->
            val consecutive = health.consecutiveFailures + 1
            val backoff = min(
                config.cooldownSeconds.toLong() shl (consecutive - 1).coerceAtMost(5),
                config.maxCooldownSeconds.toLong(),
            )
            health.copy(
                failures = health.failures + 1,
                consecutiveFailures = consecutive,
                cooldownUntil = System.currentTimeMillis() + backoff * 1000,
                lastUsedAt = System.currentTimeMillis(),
                lastError = reason,
            )
        }

    private fun mutate(providerId: String, transform: (EndpointHealth) -> EndpointHealth) {
        _health.value = _health.value.toMutableMap().apply {
            put(providerId, transform(this[providerId] ?: EndpointHealth(providerId)))
        }
    }

    private companion object {
        const val TAG = "RotationEngine"
    }
}
