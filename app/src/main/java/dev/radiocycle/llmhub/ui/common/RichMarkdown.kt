package dev.radiocycle.llmhub.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Full Markdown + LaTeX rendering: GFM (tables, task lists, strikethrough, fenced code) via marked
 * and TeX via KaTeX, both bundled in `assets/render` so nothing is fetched at runtime.
 *
 * The page reports its own content height back over the bridge and the composable sizes itself to
 * match, so the view participates in the surrounding LazyColumn as an ordinary fixed-height item.
 */
@Composable
fun RichMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val contentColor = LocalContentColor.current
    val bodySize = MaterialTheme.typography.bodyLarge.fontSize
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var heightPx by remember { mutableStateOf(0) }

    val theme = remember(scheme, contentColor, bodySize) {
        buildJsonObject {
            put("fg", contentColor.css())
            put("muted", scheme.onSurfaceVariant.css())
            put("surface", scheme.surfaceContainerHigh.css())
            put("surface-2", scheme.surfaceContainerHighest.css())
            put("outline", scheme.outlineVariant.css())
            put("accent", scheme.primary.css())
            put("error", scheme.error.css())
            put("font-size", "${bodySize.value.takeIf { it > 0f } ?: 16f}px")
            put("code-size", "${(bodySize.value.takeIf { it > 0f } ?: 16f) * 0.82f}px")
        }.toString()
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.coerceAtLeast(1).toDp() }),
        factory = { context ->
            MarkdownWebView(context).apply {
                onHeightChanged = { heightPx = it }
                onCopyRequested = { clipboard.setText(AnnotatedString(it)) }
                onLinkClicked = { url -> runCatching { uriHandler.openUri(url) } }
                start(theme, markdown)
            }
        },
        update = { view ->
            view.applyTheme(theme)
            view.applyMarkdown(markdown)
        },
        onRelease = { it.release() },
    )
}

private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * WebView bound to `assets/render/index.html`. Content pushes are throttled because a streaming
 * reply changes on every token, and re-parsing plus re-typesetting on each one would be wasteful.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class MarkdownWebView(context: Context) : WebView(context) {

    var onHeightChanged: (Int) -> Unit = {}
    var onCopyRequested: (String) -> Unit = {}
    var onLinkClicked: (String) -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var released = false
    private var appliedTheme: String? = null
    private var appliedMarkdown: String? = null
    private var pendingMarkdown: String? = null
    private var lastPushAt = 0L
    private val flush = Runnable { pushPending() }

    init {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            // Everything the page needs is bundled; blocking loads keeps replies from phoning home.
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = (context.resources.configuration.fontScale * 100).toInt().coerceIn(50, 250)
        }
        addJavascriptInterface(Bridge(), BRIDGE)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                appliedTheme?.let { evaluate("window.LlmHub.setTheme(${it.jsString()})") }
                pushPending()
            }
        }
    }

    fun start(theme: String, markdown: String) {
        appliedTheme = theme
        pendingMarkdown = markdown
        loadUrl(PAGE)
    }

    fun applyTheme(theme: String) {
        if (theme == appliedTheme) return
        appliedTheme = theme
        if (pageReady) evaluate("window.LlmHub.setTheme(${theme.jsString()})")
    }

    fun applyMarkdown(markdown: String) {
        if (markdown == appliedMarkdown) return
        pendingMarkdown = markdown
        val elapsed = System.currentTimeMillis() - lastPushAt
        if (elapsed >= THROTTLE_MS) {
            pushPending()
        } else {
            handler.removeCallbacks(flush)
            handler.postDelayed(flush, THROTTLE_MS - elapsed)
        }
    }

    private fun pushPending() {
        if (!pageReady || released) return
        val markdown = pendingMarkdown ?: return
        if (markdown == appliedMarkdown) return
        appliedMarkdown = markdown
        lastPushAt = System.currentTimeMillis()
        evaluate("window.LlmHub.setContent(${markdown.jsString()})")
    }

    private fun evaluate(script: String) {
        if (!released) runCatching { evaluateJavascript(script, null) }
    }

    fun release() {
        released = true
        handler.removeCallbacks(flush)
        runCatching {
            removeJavascriptInterface(BRIDGE)
            stopLoading()
            destroy()
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun setHeight(cssPx: Int) {
            val px = (cssPx * resources.displayMetrics.density).toInt()
            handler.post { if (!released) onHeightChanged(px.coerceIn(0, MAX_HEIGHT_PX)) }
        }

        @JavascriptInterface
        fun copy(text: String) {
            handler.post { if (!released) onCopyRequested(text) }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            handler.post { if (!released) onLinkClicked(url) }
        }
    }

    private companion object {
        const val PAGE = "file:///android_asset/render/index.html"
        const val BRIDGE = "AndroidRender"
        const val THROTTLE_MS = 120L

        /** Guards against a runaway height report collapsing the list. */
        const val MAX_HEIGHT_PX = 200_000
    }
}

/** JSON string literals are valid JS literals once the two JS-only line separators are escaped. */
private fun String.jsString(): String = JsonPrimitive(this).toString()
    .replace("\u2028", "\\u2028")
    .replace("\u2029", "\\u2029")
