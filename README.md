# LLM Hub

An Android client that puts OpenAI, Anthropic, Google Gemini and any compatible endpoint behind a
single chat interface — with automatic failover between them and a set of built-in tools the model
can call.

Kotlin · Jetpack Compose · Material 3 Expressive · minSdk 26 · no backend of its own.

## What it does

**One interface, three wire protocols.** `LlmClient` has three implementations — OpenAI Chat
Completions, Anthropic Messages, Google `generateContent` — each streaming into the same
`StreamEvent` shape. Everything above that layer is protocol-agnostic, so a conversation can move
between vendors mid-turn.

**Seamless rotation.** Requests run through a `RotationEngine` that picks an endpoint, streams from
it, and moves to the next one when it fails. The part that matters: if a provider dies *after*
tokens have already been rendered, the partial answer is handed to the next provider as a prefill
(native assistant prefill on Anthropic, a trailing assistant turn plus a continuation instruction
elsewhere), so the reply continues from the cut-off point instead of restarting. Switches are shown
inline as a small chip, not hidden.

Selection strategies: **failover** (priority order), **round-robin**, **weighted**, **least-used**.
Failing endpoints get an exponentially growing cooldown (capped), and each one carries live health —
successes, failures, last latency, last error — visible on the Providers screen.

Rotation triggers are individually switchable: rate limits (429), server/network errors, auth
errors (401/403 — a dead key skips instead of blocking), and missing models. Malformed requests
(400) are surfaced rather than retried, since they would fail identically everywhere.

**Custom providers.** Every provider — preset or hand-made — is the same record: display name, API
mode (`openai` / `anthropic` / `google`), base URL, API key, arbitrary custom headers, model list,
weight, timeout, and a tool-support flag. Presets for Anthropic, OpenAI, Gemini, OpenRouter, Groq,
DeepSeek, Mistral, xAI, Together, Cerebras, Fireworks and local Ollama/LM Studio only pre-fill the
form; nothing is locked afterwards. "Fetch from endpoint" pulls the model catalogue and doubles as a
credentials check.

**Tools.** Offered to any provider that supports tool calling, executed locally, results fed back in
a loop until the model stops asking (budget configurable):

| Tool | What it does |
| --- | --- |
| `web_search` | Ranked results via DuckDuckGo (keyless default), Tavily, Brave or a SearXNG instance |
| `web_fetch` | Fetches a URL and extracts readable text from the HTML |
| `exec_js` | Evaluates JavaScript in an off-screen WebView — no network, no filesystem, console captured, promises awaited, hard timeout |

## Architecture

```
data/model      Provider, ApiMode, ChatMessage, Conversation, AppSettings, presets
data/store      JsonFileStore — atomic JSON files in app-private storage, mirrored to StateFlow
data/repo       Provider / Conversation / Settings repositories
net             LlmClient + OpenAiClient, AnthropicClient, GoogleClient, SSE reader, error taxonomy
rotation        RotationEngine (endpoint choice, health, handoff), ChatEngine (tool loop)
tools           AgentTool, ToolRegistry, WebSearchTool, WebFetchTool, ExecJsTool, JsSandbox
ui              Compose screens: chat, providers, provider editor, settings
```

Dependencies are wired by hand in `AppContainer`; state is `StateFlow` throughout. Persistence is
plain JSON files written through a temp file, so a crash mid-write cannot truncate the previous
state. There is no analytics, no telemetry, and no server between the app and the providers — API
keys live in the app's private storage and are only ever sent to the provider they belong to.

## Building

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # minified, signed with the debug key
```

Requires JDK 17 and the Android SDK (compileSdk 36). CI builds both variants on every push and
uploads them as workflow artifacts — see [`.github/workflows/android.yml`](.github/workflows/android.yml).

## Usage notes

- Providers are tried top to bottom; reorder them with the arrows on the Providers screen.
- The route selector in the chat top bar pins one provider+model, or leaves it on automatic
  rotation. Pinning still allows failover to the rest of the pool.
- For a local Ollama or LM Studio server, `10.0.2.2` is the host machine as seen from an emulator.
- `exec_js` runs in a WebView, so it is real JavaScript with no DOM and no network access.
