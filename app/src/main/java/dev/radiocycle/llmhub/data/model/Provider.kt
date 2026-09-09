package dev.radiocycle.llmhub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Wire protocol a provider speaks. Everything else (auth header, path, body) follows from it. */
@Serializable
enum class ApiMode {
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC,
    @SerialName("google") GOOGLE;

    val label: String
        get() = when (this) {
            OPENAI -> "OpenAI"
            ANTHROPIC -> "Anthropic"
            GOOGLE -> "Google"
        }
}

@Serializable
data class HeaderEntry(
    val name: String = "",
    val value: String = "",
)

/**
 * A single callable endpoint. Built-in presets and user-defined custom providers are the same
 * type — a preset merely pre-fills the fields.
 */
@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val apiMode: ApiMode = ApiMode.OPENAI,
    /** Base URL without the endpoint path, e.g. `https://api.openai.com/v1`. */
    val baseUrl: String = "",
    val apiKey: String = "",
    val headers: List<HeaderEntry> = emptyList(),
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val enabled: Boolean = true,
    /** Lower value wins in FAILOVER order; also the display order. */
    val priority: Int = 0,
    /** Relative pick chance for WEIGHTED rotation. */
    val weight: Int = 1,
    val timeoutSeconds: Int = 120,
    val supportsTools: Boolean = true,
    val presetId: String? = null,
    val notes: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || presetId == PRESET_LOCAL || apiMode == ApiMode.OPENAI && baseUrl.contains("localhost"))

    fun modelOrDefault(requested: String?): String =
        requested?.takeIf { it.isNotBlank() && (models.isEmpty() || it in models) }
            ?: defaultModel.takeIf { it.isNotBlank() }
            ?: models.firstOrNull()
            ?: ""

    companion object {
        const val PRESET_LOCAL = "local"
    }
}

/** How the engine picks the next endpoint when several are eligible. */
@Serializable
enum class RotationStrategy {
    @SerialName("failover") FAILOVER,
    @SerialName("round_robin") ROUND_ROBIN,
    @SerialName("weighted") WEIGHTED,
    @SerialName("least_used") LEAST_USED;

    val label: String
        get() = when (this) {
            FAILOVER -> "Failover"
            ROUND_ROBIN -> "Round-robin"
            WEIGHTED -> "Weighted"
            LEAST_USED -> "Least used"
        }

    val description: String
        get() = when (this) {
            FAILOVER -> "Always start from the highest-priority provider, fall through on error"
            ROUND_ROBIN -> "Spread requests evenly across every healthy provider"
            WEIGHTED -> "Pick randomly, proportional to each provider's weight"
            LEAST_USED -> "Prefer the provider that has been idle the longest"
        }
}

/** Runtime health of one endpoint. Not persisted — it is rebuilt every launch. */
data class EndpointHealth(
    val providerId: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val cooldownUntil: Long = 0L,
    val lastUsedAt: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastError: String? = null,
) {
    fun isCoolingDown(now: Long = System.currentTimeMillis()) = now < cooldownUntil
}
