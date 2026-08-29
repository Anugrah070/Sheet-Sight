package com.sheetsight.app.ui.editor

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val OSMD_HOST = "sheetsight.local"
private const val OSMD_ORIGIN = "https://$OSMD_HOST"

/**
 * Hosts OSMD in a locked-down local WebView. MusicXML is served from memory at
 * the same synthetic origin as the bundled renderer and never leaves the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OsmdScoreView(
    musicXml: String,
    title: String,
    initialSystemIndex: Int,
    systemCount: Int,
    zoom: Float,
    onSystemChanged: (Int) -> Unit,
    cursorStepIndex: Int? = null,
    modifier: Modifier = Modifier
) {
    var loading by remember(musicXml) { mutableStateOf(true) }
    var renderError by remember(musicXml) { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val renderKey = remember(musicXml) { musicXml.hashCode() }

    LaunchedEffect(musicXml) {
        delay(OSMD_RENDER_TIMEOUT_MILLIS)
        if (loading) {
            loading = false
            renderError = "Rendering timed out. Try reopening the score."
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("osmd_score_view"),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.rgb(236, 234, 229))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        blockNetworkLoads = true
                    }

                    val rendererState = OsmdRendererState(renderKey = renderKey)
                    tag = rendererState
                    val finishRender = {
                        if (!rendererState.rendered) {
                            Log.d(OSMD_LOG_TAG, "Score rendered successfully")
                            rendererState.rendered = true
                            rendererState.lastAppliedZoom = zoom
                            rendererState.lastCursorStepIndex = cursorStepIndex
                            loading = false
                            renderError = null
                        }
                    }
                    addJavascriptInterface(
                        OsmdJavascriptBridge(
                            postToMain = mainHandler::post,
                            onRendered = finishRender,
                            onError = { message ->
                                Log.e(OSMD_LOG_TAG, "OSMD render failed: $message")
                                loading = false
                                renderError = message
                            },
                            onSystemChanged = onSystemChanged
                        ),
                        "AndroidOsmd"
                    )
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val message = consoleMessage.message().ifBlank { "JavaScript failed to run." }
                                Log.e(OSMD_LOG_TAG, "$message (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                                if (!rendererState.rendered) {
                                    mainHandler.post {
                                        loading = false
                                        renderError = message
                                    }
                                }
                            }
                            return true
                        }
                    }
                    webViewClient = LocalOsmdWebViewClient(
                        assets = context.assets,
                        musicXml = musicXml,
                        onMainFrameError = { message ->
                            mainHandler.post {
                                loading = false
                                renderError = message
                            }
                        }
                    )
                    loadUrl(
                        Uri.parse("$OSMD_ORIGIN/index.html").buildUpon()
                            .appendQueryParameter("title", title)
                            .appendQueryParameter("zoom", zoom.toString())
                            .appendQueryParameter("system", initialSystemIndex.toString())
                            .appendQueryParameter("systems", systemCount.toString())
                            .apply {
                                cursorStepIndex?.let { appendQueryParameter("cursor", it.toString()) }
                            }
                            .build()
                            .toString()
                    )
                    rendererState.pollRunnable = object : Runnable {
                        override fun run() {
                            if (rendererState.released || rendererState.rendered) return
                            evaluateJavascript(
                                "window.sheetSight ? " +
                                    "(window.sheetSight.renderState === 'rendered' ? 1 : " +
                                    "(window.sheetSight.renderState === 'error' ? -1 : 0)) : 0"
                            ) { value ->
                                when (value) {
                                    "1" -> finishRender()
                                    "-1" -> {
                                        loading = false
                                        renderError = "OSMD could not render this score."
                                    }
                                    else -> mainHandler.postDelayed(
                                        rendererState.pollRunnable!!,
                                        OSMD_RENDER_POLL_MILLIS
                                    )
                                }
                            }
                        }
                    }
                    mainHandler.postDelayed(rendererState.pollRunnable!!, OSMD_RENDER_POLL_MILLIS)
                }
            },
            update = { webView ->
                val rendererState = webView.tag as? OsmdRendererState
                if (rendererState?.renderKey == renderKey && rendererState.rendered &&
                    abs(rendererState.lastAppliedZoom - zoom) >= 0.01f
                ) {
                    rendererState.lastAppliedZoom = zoom
                    webView.evaluateJavascript("window.sheetSight.setZoom($zoom)", null)
                }
                if (rendererState?.renderKey == renderKey && rendererState.rendered &&
                    rendererState.lastCursorStepIndex != cursorStepIndex
                ) {
                    rendererState.lastCursorStepIndex = cursorStepIndex
                    cursorStepIndex?.let { step ->
                        webView.evaluateJavascript("window.sheetSight.setCursorStep($step)", null)
                    }
                }
            },
            onRelease = { webView ->
                (webView.tag as? OsmdRendererState)?.let { state ->
                    state.released = true
                    state.pollRunnable?.let(mainHandler::removeCallbacks)
                }
                webView.stopLoading()
                webView.removeJavascriptInterface("AndroidOsmd")
                webView.destroy()
            }
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).testTag("osmd_render_loading")
            )
        }
        renderError?.let { message ->
            Text(
                text = "OSMD: $message",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).testTag("osmd_render_error")
            )
        }
    }
}

private data class OsmdRendererState(
    val renderKey: Int,
    var rendered: Boolean = false,
    var lastAppliedZoom: Float = Float.NaN,
    var lastCursorStepIndex: Int? = null,
    var pollRunnable: Runnable? = null,
    var released: Boolean = false
)

private class OsmdJavascriptBridge(
    private val postToMain: (Runnable) -> Boolean,
    private val onRendered: () -> Unit,
    private val onError: (String) -> Unit,
    private val onSystemChanged: (Int) -> Unit
) {
    @JavascriptInterface
    fun onRendered(zoom: Double) {
        Log.d(OSMD_LOG_TAG, "WebView bridge received rendered at zoom=$zoom")
        postToMain(Runnable(onRendered))
    }

    @JavascriptInterface
    fun onError(message: String) {
        Log.e(OSMD_LOG_TAG, "WebView bridge received error: $message")
        postToMain(Runnable { onError(message) })
    }

    @JavascriptInterface
    fun onZoomChanged(zoom: Double) = Unit

    @JavascriptInterface
    fun onSystemChanged(systemIndex: Int) {
        postToMain(Runnable { onSystemChanged(systemIndex) })
    }
}

private class LocalOsmdWebViewClient(
    private val assets: AssetManager,
    private val musicXml: String,
    private val onMainFrameError: (String) -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        request.url.host != OSMD_HOST

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        if (request.url.scheme != "https" || request.url.host != OSMD_HOST) return notFound()
        return when (request.url.path) {
            "/index.html" -> asset("osmd/index.html", "text/html")
            "/opensheetmusicdisplay.min.js" ->
                asset("osmd/opensheetmusicdisplay.min.js", "application/javascript")
            "/renderer.js" -> asset("osmd/renderer.js", "application/javascript")
            "/score.musicxml" -> response(
                mimeType = "application/vnd.recordare.musicxml+xml",
                data = ByteArrayInputStream(musicXml.toByteArray(Charsets.UTF_8)),
                cacheControl = "no-store"
            )
            else -> notFound()
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onMainFrameError(error.description.toString())
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onMainFrameError("The Android WebView renderer stopped unexpectedly.")
        return true
    }

    private fun asset(path: String, mimeType: String): WebResourceResponse =
        try {
            response(mimeType, assets.open(path), cacheControl = "public, max-age=31536000")
        } catch (failure: Exception) {
            Log.e(OSMD_LOG_TAG, "Could not serve $path", failure)
            notFound()
        }

    private fun response(
        mimeType: String,
        data: java.io.InputStream,
        cacheControl: String
    ) = WebResourceResponse(
        mimeType,
        "utf-8",
        200,
        "OK",
        mapOf(
            "Cache-Control" to cacheControl,
            "Access-Control-Allow-Origin" to OSMD_ORIGIN,
            "X-Content-Type-Options" to "nosniff"
        ),
        data
    )

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )
}

private const val OSMD_RENDER_TIMEOUT_MILLIS = 30_000L
private const val OSMD_RENDER_POLL_MILLIS = 250L
private const val OSMD_LOG_TAG = "SheetSightOsmd"
