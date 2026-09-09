package dev.radiocycle.llmhub.tools

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.radiocycle.llmhub.core.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class JsRunResult(
    val result: String? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Runs JavaScript in an off-screen WebView. The document has no origin, no file access, no storage
 * and network loads blocked, so scripts get a plain ES engine plus a bridge to report the result.
 * Promises are supported — the harness resolves them before reporting back.
 */
class JsSandbox(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun evaluate(code: String, timeoutMs: Long): JsRunResult {
        var webView: WebView? = null
        val delivered = AtomicBoolean(false)
        return try {
            val payload = withTimeout(timeoutMs) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        val view = WebView(context)
                        webView = view
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            blockNetworkLoads = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        view.addJavascriptInterface(object {
                            @JavascriptInterface
                            fun deliver(json: String) {
                                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                                    continuation.resume(json)
                                }
                            }
                        }, BRIDGE)
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(harness(code), null)
                            }
                        }
                        view.loadDataWithBaseURL(null, BLANK_PAGE, "text/html", "utf-8", null)
                    }
                }
            }
            parse(payload)
        } catch (timeout: TimeoutCancellationException) {
            JsRunResult(error = "Execution timed out after ${timeoutMs}ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            JsRunResult(error = "Sandbox error: ${t.message ?: t::class.java.simpleName}")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                webView?.let { view ->
                    runCatching {
                        view.removeJavascriptInterface(BRIDGE)
                        view.stopLoading()
                        view.destroy()
                    }
                }
            }
        }
    }

    private fun parse(payload: String): JsRunResult = runCatching {
        val root = AppJson.parseToJsonElement(payload).jsonObject
        JsRunResult(
            result = root["result"]?.jsonPrimitive?.contentOrNull,
            logs = root["logs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            error = root["error"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrElse { JsRunResult(error = "Could not decode sandbox output: ${it.message}") }

    /**
     * Wraps user code so console output is captured, the completion value is stringified, and both
     * synchronous throws and rejected promises come back as one JSON payload.
     */
    private fun harness(code: String): String {
        val literal = jsStringLiteral(code)
        return """
            (function () {
              var __logs = [];
              function __show(v) {
                if (typeof v === 'string') return v;
                if (v instanceof Error) return (v.stack || (v.name + ': ' + v.message));
                if (typeof v === 'undefined') return 'undefined';
                if (typeof v === 'function') return v.toString();
                try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
              }
              var console = {
                log: function () { __logs.push(Array.prototype.map.call(arguments, __show).join(' ')); },
                info: function () { console.log.apply(null, arguments); },
                warn: function () { console.log.apply(null, arguments); },
                error: function () { console.log.apply(null, arguments); },
                debug: function () { console.log.apply(null, arguments); }
              };
              function __finish(result, error) {
                try {
                  $BRIDGE.deliver(JSON.stringify({ logs: __logs, result: result, error: error }));
                } catch (e) { }
              }
              try {
                var __out = eval($literal);
                if (__out && typeof __out.then === 'function') {
                  __out.then(
                    function (v) { __finish(__show(v), null); },
                    function (e) { __finish(null, __show(e)); }
                  );
                } else {
                  __finish(__show(__out), null);
                }
              } catch (e) {
                __finish(null, __show(e));
              }
            })();
        """.trimIndent()
    }

    /** A JSON string literal is a valid JS literal once the two JS-only line separators escape. */
    private fun jsStringLiteral(value: String): String = JsonPrimitive(value).toString()
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private companion object {
        const val BRIDGE = "__llmhubBridge"
        const val BLANK_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
    }
}
