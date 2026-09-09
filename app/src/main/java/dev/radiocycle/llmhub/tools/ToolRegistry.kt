package dev.radiocycle.llmhub.tools

import dev.radiocycle.llmhub.core.AppJson
import dev.radiocycle.llmhub.data.model.ToolCall
import dev.radiocycle.llmhub.data.model.ToolResult
import dev.radiocycle.llmhub.data.repo.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Owns the tool instances and decides which of them the model is offered on a given turn. */
class ToolRegistry(
    private val settings: SettingsRepository,
    webSearch: WebSearchTool,
    webFetch: WebFetchTool,
    execJs: ExecJsTool,
) {
    private val tools: Map<String, AgentTool> = listOf(webSearch, webFetch, execJs)
        .associateBy { it.spec.name }

    /** Specs for the tools currently switched on in settings. */
    fun activeSpecs(): List<ToolSpec> {
        val toolSettings = settings.current.tools
        return buildList {
            if (toolSettings.webSearchEnabled) tools["web_search"]?.let { add(it.spec) }
            if (toolSettings.webFetchEnabled) tools["web_fetch"]?.let { add(it.spec) }
            if (toolSettings.execJsEnabled) tools["exec_js"]?.let { add(it.spec) }
        }
    }

    suspend fun run(call: ToolCall): ToolResult {
        val started = System.currentTimeMillis()
        val tool = tools[call.name]
            ?: return ToolResult(call.id, call.name, "Unknown tool: ${call.name}", isError = true)

        val args = runCatching { AppJson.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

        val outcome = runCatching { tool.execute(args) }
            .getOrElse { ToolOutcome("Tool crashed: ${it.message}", isError = true) }

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = outcome.content,
            isError = outcome.isError,
            durationMs = System.currentTimeMillis() - started,
        )
    }
}
