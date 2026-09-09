package dev.radiocycle.llmhub.net

import okhttp3.Response

/** One decoded server-sent event. */
data class SseEvent(val name: String?, val data: String)

/**
 * Reads an SSE body line by line. Blocking — always call from an IO dispatcher inside a flow.
 * Stops early when [onEvent] returns false.
 */
inline fun Response.readSse(onEvent: (SseEvent) -> Boolean) {
    val source = body?.source() ?: throw LlmException("Empty stream body", kind = LlmException.Kind.PARSE)
    var eventName: String? = null
    val data = StringBuilder()

    fun flush(): Boolean {
        if (data.isEmpty() && eventName == null) return true
        val payload = data.toString()
        data.setLength(0)
        val name = eventName
        eventName = null
        return onEvent(SseEvent(name, payload))
    }

    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> if (!flush()) return
            line.startsWith(":") -> Unit // comment / keep-alive
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
    flush()
}
