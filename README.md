# LLM Hub

An Android client that puts OpenAI, Anthropic, Google Gemini and any compatible endpoint behind a
single chat interface — with automatic failover between them and a set of built-in tools the model
can call.

Kotlin · Jetpack Compose · Material 3 Expressive · minSdk 26 · no backend of its own.

The UI is built on material3 1.4.0, where the expressive design system is the baseline of
`MaterialTheme` (the separate `MaterialExpressiveTheme` entry point is `internal` there), so the app
themes through `MaterialTheme` with an expressive shape scale on top of a violet/coral/mint palette
and Android 12+ dynamic color.

## What it does

**One interface, three wire protocols.** `LlmClient` has three implementations — OpenAI Chat
Completions, Anthropic Messages, Google `generateContent` — each streaming into the same
`StreamEvent` shape. Everything above that layer is protocol-agnostic, so a conversation can move
between vendors mid-turn.

**Seamless rotation, down to the individual key.** Each provider holds a *pool* of API keys, entered
comma-, semicolon- or newline-separated. The unit the engine schedules is an `Endpoint` — one
provider paired with one key — and each is tracked and cooled down on its own.

*Credential failures are silent.* **401, 402, 403 and 429** describe the credential, not the
request, so the engine moves to the next key — the same provider's next key first, then the next
provider — without showing anything. An error reaches the user only once every key of every
provider has been tried. These attempts also do not consume the retry budget, which exists for
flaky transports, so a pool of twenty keys gets twenty tries regardless of the "max attempts"
setting.

*Partial answers carry over.* If a provider dies *after* tokens have already been rendered, the
text so far is handed to the next endpoint as a prefill (native assistant prefill on Anthropic, a
trailing assistant turn plus a continuation instruction elsewhere), so the reply continues from the
cut-off point instead of restarting. A change of provider raises a small inline chip; walking a key
pool raises nothing.

Selection strategies apply at the provider level — **failover** (priority order), **round-robin**,
**weighted**, **least-used** — while keys inside a provider are always walked in order, so a pool
behaves predictably. Failing endpoints get an exponentially growing cooldown (capped), and each
carries live health: successes, failures, last latency, last error, all visible on the Providers
screen.

Malformed requests (400) are surfaced rather than retried, since they would fail identically
everywhere.

**Custom providers.** Every provider — preset or hand-made — is the same record: display name, API
mode (`openai` / `anthropic` / `google`), base URL, API key, arbitrary custom headers, model list,
weight, timeout, and a tool-support flag. Presets for Anthropic, OpenAI, Gemini, OpenRouter, Groq,
DeepSeek, Mistral, xAI, Together, Cerebras, Fireworks and local Ollama/LM Studio only pre-fill the
form; nothing is locked afterwards. "Fetch from endpoint" pulls the model catalogue and doubles as a
credentials check.

**Tools and the agent loop.** Offered to any provider that supports tool calling and executed
locally. Each turn runs:

```
generate -> tool calls -> tool output -> generate -> ...
```

The loop repeats while the model keeps asking for tools, and always ends on a generation: once the
tool budget is spent no further tools are offered, so the last round of output is turned into prose
rather than another round of calls.

| Tool | What it does |
| --- | --- |
| `web_search` | Ranked results via DuckDuckGo (keyless default), Tavily, Brave or a SearXNG instance |
| `web_fetch` | Fetches a URL and extracts readable text from the HTML |
| `exec_js` | Evaluates JavaScript in an off-screen WebView — no network, no filesystem, console captured, promises awaited, hard timeout |
| `read_file` `write_file` `edit_file` `delete_file` `list_files` | opencode-style file tools that operate in the agent's workspace directory |
| `shell` | Runs a shell command in the workspace — through `su` on a rooted device when enabled |

**Markdown and LaTeX.** Replies render through [marked](https://marked.js.org) and
[KaTeX](https://katex.org), both vendored into `assets/render` so nothing is fetched at runtime:
GFM tables, task lists, strikethrough, blockquotes, fenced code with a copy button, and TeX written
as `$…$`, `\(…\)`, `$$…$$`, `\[…\]` or a `\begin{align}`-style environment. Inline `$` is only
treated as math when it does not look like prose or currency, so "it costs $5 and $10" stays text.

Model output is untrusted, so raw HTML is escaped rather than executed, `javascript:` links are
dropped, and the render WebView blocks network loads — an image in a reply becomes a link instead of
a request. The page reports its own content height back over the bridge so each message sizes itself
inside the list, and content pushes are throttled because a streaming reply changes on every token.
A lighter built-in renderer (headings, emphasis, code, lists, tables) is available behind
Settings → Appearance if you would rather not run the WebView path.

**Workspace, files and shell.** File and shell tools operate in a *workspace* directory set in
Settings → Workspace & shell. Blank means an app-private folder that is always readable and
writable with no runtime permission; it can also point at shared storage or, on a rooted device,
anywhere.

- The five file tools mirror opencode: `read_file` returns numbered lines, `write_file` writes a
  file whole, `edit_file` replaces an exact unique snippet (or every occurrence with `replace_all`),
  `delete_file` removes a file or, with `recursive`, a tree, and `list_files` lists a directory.
  Relative paths resolve against the workspace; absolute paths are allowed. With **Restrict to
  workspace** on (the default) a path that climbs out with `..` or an absolute path is refused, so a
  confined agent cannot escape its directory.
- `shell` runs a command line in the workspace and returns combined stdout/stderr and the exit
  code. Android ships a POSIX shell, so `ls`, `cat`, `grep`, `find` and pipelines work in the app's
  own sandbox. Turn on **Run shell as root** and, on a rooted device, commands run through `su` with
  full-filesystem reach; the toggle shows whether a `su` grant is actually available. State does not
  carry between calls, so steps are chained with `&&` inside one command. The shell is off by
  default — it runs real commands on the device.

## Architecture

```
data/model      Provider, ApiMode, ChatMessage, Conversation, AppSettings, presets
data/store      JsonFileStore — atomic JSON files in app-private storage, mirrored to StateFlow
data/repo       Provider / Conversation / Settings repositories
net             LlmClient + OpenAiClient, AnthropicClient, GoogleClient, SSE reader, error taxonomy
rotation        RotationEngine (endpoint choice, health, handoff), ChatEngine (agent loop)
tools           AgentTool, ToolRegistry, WebSearchTool, WebFetchTool, ExecJsTool, JsSandbox
                WorkspaceManager + RootAccess, ShellTool, file tools (read/write/edit/delete/list)
ui              Compose screens: chat, providers, provider editor, settings
assets/render   Bundled marked + KaTeX renderer used for replies
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
- Paste several keys into one provider separated by commas to get silent per-key failover:
  `sk-aaa, sk-bbb, sk-ccc`.
- The route selector in the chat top bar pins one provider+model, or leaves it on automatic
  rotation. Pinning still allows failover to the rest of the pool.
- For a local Ollama or LM Studio server, `10.0.2.2` is the host machine as seen from an emulator.
- `exec_js` runs in a WebView, so it is real JavaScript with no DOM and no network access.
