package com.azurlane.blyy.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 使用 WebView 渲染 JavaScript 动态页面并获取完整 HTML。
 *
 * 适用于 Jsoup 无法直接解析的 SPA 页面（如 gamekee.com 的语音页/学生列表页）。
 * 在主线程创建 WebView，加载 URL，等待 JS 渲染完成后提取 HTML。
 */
object WebViewHtmlFetcher {

    private const val TAG = "WebViewHtmlFetcher"
    private const val HTML_CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5 分钟
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 获取渲染后 HTML（带 LRU 缓存 + 指数退避重试）
     *
     * 缓存策略：
     * 1. 先查 CacheManager LRU 缓存（5 分钟 TTL），命中直接返回
     * 2. 未命中则执行 WebView 渲染，失败后指数退避重试（1s, 2s）
     * 3. 成功后写入缓存
     */
    suspend fun fetchRenderedHtmlWithCache(
        context: Context,
        url: String,
        waitMs: Long = 6000L,
        timeoutMs: Long = 30000L,
        maxRetries: Int = 2
    ): String {
        val cached = CacheManager.get<String>(CacheNamespaces.WEBVIEW_RENDERED_HTML, url)
        if (cached != null) {
            Log.d(TAG, "HTML cache hit: $url")
            return cached
        }

        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                Log.d(TAG, "Fetching rendered HTML (attempt ${attempt + 1}/${maxRetries + 1}): $url")
                val html = fetchRenderedHtml(context, url, waitMs, timeoutMs)
                if (html.isNotEmpty()) {
                    CacheManager.put(CacheNamespaces.WEBVIEW_RENDERED_HTML, url, html, HTML_CACHE_EXPIRY_MS)
                    Log.d(TAG, "HTML cached: $url, size=${html.length}")
                    return html
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for $url: ${e.message}")
                if (attempt < maxRetries) {
                    val backoffMs = (attempt + 1) * 1000L
                    Log.d(TAG, "Retrying in ${backoffMs}ms...")
                    delay(backoffMs)
                }
            }
        }
        Log.e(TAG, "All retries exhausted for: $url")
        throw lastException ?: IllegalStateException("WebView 渲染失败: $url")
    }

    /**
     * 获取经过 JavaScript 渲染后的页面 HTML。
     *
     * @param context Android Context
     * @param url 目标 URL
     * @param waitMs 渲染等待时间（毫秒），确保动态内容加载完成
     * @param timeoutMs 总超时时间（毫秒），防止页面卡死时协程永不返回
     * @return 渲染后的完整 HTML 字符串
     */
    suspend fun fetchRenderedHtml(
        context: Context,
        url: String,
        waitMs: Long = 6000L,
        timeoutMs: Long = 30000L
    ): String = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                var webView: WebView? = null
                var finished = false

                try {
                    @SuppressLint("SetJavaScriptEnabled")
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        settings.blockNetworkImage = true // 加速加载，不需要图片
                        settings.loadsImagesAutomatically = false

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // 页面加载完成后，等待 JS 渲染
                                mainHandler.postDelayed({
                                    if (!finished && !cont.isCompleted) {
                                        extractHtml(view) { html ->
                                            finished = true
                                            cleanup(webView)
                                            if (html.isNotEmpty()) {
                                                cont.resume(html)
                                            } else {
                                                cont.resumeWithException(
                                                    IllegalStateException("渲染后 HTML 为空")
                                                )
                                            }
                                        }
                                    }
                                }, waitMs)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // 仅处理主帧错误，子资源错误不影响整体
                                if (request?.isForMainFrame == true && !finished && !cont.isCompleted) {
                                    finished = true
                                    cleanup(webView)
                                    cont.resumeWithException(
                                        RuntimeException("WebView 加载错误: ${error?.description}")
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                // 仅处理主帧 HTTP 错误
                                if (request?.isForMainFrame == true && !finished && !cont.isCompleted) {
                                    finished = true
                                    cleanup(webView)
                                    cont.resumeWithException(
                                        RuntimeException("HTTP 错误: ${errorResponse?.statusCode}")
                                    )
                                }
                            }
                        }

                        webChromeClient = WebChromeClient()
                    }

                    webView.loadUrl(url)
                } catch (e: Exception) {
                    cleanup(webView)
                    if (!cont.isCompleted) {
                        cont.resumeWithException(e)
                    }
                }

                cont.invokeOnCancellation {
                    cleanup(webView)
                }
            }
        }
    }

    private fun extractHtml(webView: WebView?, callback: (String) -> Unit) {
        webView?.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})();"
        ) { result ->
            // evaluateJavascript 返回的字符串带引号和转义字符，需要处理
            val html = if (result != null && result != "null") {
                // 去除首尾引号，反转义
                result
                    .removeSurrounding("\"")
                    .replace("\\u003C", "<")
                    .replace("\\u003E", ">")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
            } else {
                ""
            }
            callback(html)
        }
    }

    private fun cleanup(webView: WebView?) {
        mainHandler.post {
            try {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    (parent as? android.view.ViewGroup)?.removeView(this)
                    destroy()
                }
            } catch (e: Exception) {
                // 忽略清理异常
            }
        }
    }
}
