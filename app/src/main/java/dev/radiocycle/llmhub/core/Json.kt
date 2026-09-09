package dev.radiocycle.llmhub.core

import kotlinx.serialization.json.Json

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Pretty variant used for tool payloads shown in the UI. */
val PrettyJson: Json = Json(AppJson) { prettyPrint = true }
