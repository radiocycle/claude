package dev.radiocycle.llmhub.tools

import dev.radiocycle.llmhub.data.repo.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lets the model compute with real JavaScript instead of guessing arithmetic or string work. */
class ExecJsTool(
    private val sandbox: JsSandbox,
    private val settings: SettingsRepository,
) : AgentTool {

    override val spec = ToolSpec(
        name = "exec_js",
        description = "Execute JavaScript in a sandboxed engine and return the completion value " +
            "plus anything logged with console.log. Use it for calculations, data transformation, " +
            "parsing and string manipulation. No network, no filesystem, no DOM. The last " +
            "expression is the return value; promises are awaited.",
        parameters = objectSchema(required = listOf("code")) {
            stringProp("code", "JavaScript source to evaluate. The final expression is returned.")
            intProp("timeout_ms", "Execution budget in milliseconds (default 5000, max 30000).")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val code = args["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code.isBlank()) return ToolOutcome("`code` is required", isError = true)

        val timeout = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: settings.current.tools.jsTimeoutMs).coerceIn(100L, 30_000L)

        val run = sandbox.evaluate(code, timeout)
        val text = buildString {
            if (run.logs.isNotEmpty()) {
                appendLine("console output:")
                run.logs.forEach { appendLine(it) }
                appendLine()
            }
            when {
                run.error != null -> append("Error: ${run.error}")
                else -> append("Result: ${run.result ?: "undefined"}")
            }
        }.trim()

        return ToolOutcome(text.ifBlank { "(no output)" }, isError = run.error != null)
    }
}
