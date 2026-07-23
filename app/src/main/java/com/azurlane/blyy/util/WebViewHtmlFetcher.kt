package com.azurlane.blyy.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
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
    /** 内容检测轮询间隔（毫秒） */
    private const val POLL_INTERVAL_MS = 600L
    /** 内容检测初始延迟（毫秒）— 给 Vue SPA 初始化时间 */
    private const val POLL_INITIAL_DELAY_MS = 800L
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 获取渲染后 HTML（带 LRU 缓存 + 指数退避重试）
     *
     * 缓存策略：
     * 1. 先查 CacheManager LRU 缓存（5 分钟 TTL），命中直接返回
     * 2. 未命中则执行 WebView 渲染，失败后指数退避重试（1s, 2s）
     * 3. 成功后写入缓存
     *
     * @param contentSelector 可选的 CSS 选择器，用于动态检测内容是否渲染完成。
     *   当提供时，不再固定等待 waitMs，而是每 [POLL_INTERVAL_MS] 轮询检测该选择器
     *   是否匹配到元素。一旦匹配，立即提取 HTML 返回，大幅缩短等待时间（从 12s → 3-5s）。
     *   waitMs 退化为最大等待上限。适用于 gamekee SPA 的语音(.dub-item) / 立绘(.header-container img) 页。
     */
    suspend fun fetchRenderedHtmlWithCache(
        context: Context,
        url: String,
        waitMs: Long = 6000L,
        timeoutMs: Long = 30000L,
        maxRetries: Int = 2,
        contentSelector: String? = null
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
                val html = fetchRenderedHtml(context, url, waitMs, timeoutMs, contentSelector)
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

    /** 清除指定 URL 的 HTML 缓存 — 调用方解析失败时调用，防止缓存污染 */
    fun invalidateCache(url: String) {
        CacheManager.remove(CacheNamespaces.WEBVIEW_RENDERED_HTML, url)
        Log.d(TAG, "Cache invalidated for: $url")
    }

    /**
     * 获取经过 JavaScript 渲染后的页面 HTML。
     *
     * @param context Android Context
     * @param url 目标 URL
     * @param waitMs 最大渲染等待时间（毫秒）。当 [contentSelector] 为 null 时为固定等待时间；
     *   当 [contentSelector] 非 null 时为轮询检测上限，内容就绪后立即返回。
     * @param timeoutMs 总超时时间（毫秒），防止页面卡死时协程永不返回
     * @param contentSelector 可选的 CSS 选择器，用于动态检测内容是否渲染完成
     * @return 渲染后的完整 HTML 字符串
     */
    suspend fun fetchRenderedHtml(
        context: Context,
        url: String,
        waitMs: Long = 6000L,
        timeoutMs: Long = 30000L,
        contentSelector: String? = null
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
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        // 必须使用桌面版 UA：gamekee 根据 UA 决定渲染桌面布局还是移动布局。
                        // 移动布局只有 4 个 tab（技能/档案/养成/评测），没有"语音台词"和"影画鉴赏" tab，
                        // 且使用 .nav-tabs--top > .item 而非 .nav-sidebar-item，导致点击 JS 失效。
                        // 桌面布局有 6 个 .nav-sidebar-item tab（含语音/立绘），点击 JS 才能正常工作。
                        // 测试确认：桌面 UA + 移动视口仍渲染桌面布局，布局由 UA 决定而非视口。
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        settings.blockNetworkImage = true // 加速加载，不需要图片
                        settings.loadsImagesAutomatically = false

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // gamekee.com 新版 SPA 不会根据 URL 的 ?tab=N 参数自动切换侧边栏 tab，
                                // 需要主动注入 JS 点击对应的 .nav-sidebar-item 元素触发内容渲染。
                                // 否则语音/立绘内容不会出现在 DOM 中。
                                clickSidebarTabIfNeeded(view, url)

                                if (contentSelector != null && view != null) {
                                    // 动态内容检测：轮询检测目标内容是否渲染完成
                                    // 一旦检测到立即提取 HTML，大幅缩短等待时间
                                    waitForContentAndExtract(
                                        view, contentSelector, waitMs
                                    ) { html ->
                                        if (!finished && !cont.isCompleted) {
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
                                } else {
                                    // 无内容检测器：固定等待（原始逻辑）
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
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                // 拦截非必要资源加速渲染：字体、视频、音频文件
                                // 保留 HTML/JS/CSS/图片（图片已通过 blockNetworkImage 拦截）
                                val url = request?.url?.toString() ?: return null
                                // 拦截字体文件
                                if (url.contains(Regex("\\.(woff2?|ttf|otf|eot)(\\?|#|$)"))) {
                                    return emptyResponse()
                                }
                                // 拦截视频文件（立绘页的 .mp4 不需要预加载）
                                if (url.contains(Regex("\\.(mp4|webm|ogg)(\\?|#|$)"))) {
                                    return emptyResponse()
                                }
                                // 拦截音频文件（语音页的 .ogg/.mp3 不需要预加载，播放时单独请求）
                                if (url.contains(Regex("\\.(ogg|mp3|aac|wav)(\\?|#|$)"))) {
                                    return emptyResponse()
                                }
                                return null
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

    /**
     * 轮询检测目标内容是否渲染完成，一旦检测到立即提取 HTML。
     *
     * 策略：
     * 1. 初始等待 [POLL_INITIAL_DELAY_MS]（给 Vue SPA 初始化时间）
     * 2. 每 [POLL_INTERVAL_MS] 通过 evaluateJavascript 检测 contentSelector 匹配数
     * 3. 匹配数 > 0 时立即提取 HTML 并回调
     * 4. 超过 [maxWaitMs] 仍未检测到，降级提取当前 HTML（可能内容为空，由调用方处理）
     *
     * 相比固定等待 waitMs，此方法在内容渲染完成（通常 3-5s）后立即返回，
     * 避免浪费 7-9 秒等待时间。
     *
     * @param webView WebView 实例
     * @param contentSelector CSS 选择器（如 ".dub-item" 或 "#gfjs img"）
     * @param maxWaitMs 最大等待时间（毫秒）
     * @param onReady 内容就绪或超时后的回调
     */
    private fun waitForContentAndExtract(
        webView: WebView,
        contentSelector: String,
        maxWaitMs: Long,
        onReady: (String) -> Unit
    ) {
        var elapsed = 0L
        // 转义选择器中的单引号，防止 JS 注入
        val escapedSelector = contentSelector.replace("\\", "\\\\").replace("'", "\\'")

        fun check() {
            val js = """
                (function(){
                    try { return document.querySelectorAll('$escapedSelector').length; }
                    catch(e) { return 0; }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { result ->
                val count = result?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                if (count > 0) {
                    // 内容已就绪，立即提取 HTML
                    Log.d(TAG, "Content '$contentSelector' ready after ${elapsed}ms ($count elements)")
                    extractHtml(webView) { html -> onReady(html) }
                } else if (elapsed >= maxWaitMs) {
                    // 超时，降级提取当前 HTML
                    Log.w(TAG, "Content '$contentSelector' not found after ${maxWaitMs}ms, extracting anyway")
                    extractHtml(webView) { html -> onReady(html) }
                } else {
                    elapsed += POLL_INTERVAL_MS
                    mainHandler.postDelayed({ check() }, POLL_INTERVAL_MS)
                }
            }
        }

        // 初始延迟，给 Vue SPA 初始化时间
        mainHandler.postDelayed({ check() }, POLL_INITIAL_DELAY_MS)
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

    /**
     * 主动点击 gamekee.com 学生详情页的侧边栏 tab。
     *
     * gamekee.com 新版 Vue SPA 不会根据 URL 中的 ?tab=N 参数自动切换侧边栏 tab，
     * 导致语音(tab=2)/立绘(tab=3)内容不会渲染到 DOM。需要在 onPageFinished 后
     * 主动注入 JS 点击对应的 .nav-sidebar-item 元素触发 Vue 路由切换和异步内容加载。
     *
     * tab 索引对照：
     *   0=角色技能, 1=学生档案, 2=语音台词, 3=影画鉴赏, 4=养成模拟, 5=角色评测
     *
     * 采用轮询点击策略：Vue SPA 初始化需要时间，侧边栏元素可能在 onPageFinished 后
     * 才渲染到 DOM。每 200ms 重试一次，最多尝试 30 次（6 秒），确保在元素出现后立即点击。
     * 间隔从原 500ms 缩短到 200ms 加快点击速度。
     */
    private fun clickSidebarTabIfNeeded(webView: WebView?, url: String?) {
        if (webView == null || url == null) return
        // 仅对 gamekee.com 学生详情页(/ba/tj/)且带 ?tab= 参数的 URL 生效
        if (!url.contains("gamekee.com/ba/tj/")) return
        val tabMatch = Regex("""[?&]tab=(\d+)""").find(url) ?: return
        val tabIndex = tabMatch.groupValues[1]
        // 注入轮询 JS：每 200ms 尝试点击侧边栏 tab，最多重试 30 次（6 秒）
        // Vue SPA 初始化后侧边栏元素才会出现，轮询确保在元素可用时立即点击
        val js = """
            (function(){
                var key = 'wikiBaTj|tj|nav|$tabIndex';
                var attempts = 0;
                var maxAttempts = 30;
                function tryClick() {
                    try {
                        var target = document.querySelector('.nav-sidebar-item[data-report-key="' + key + '"]');
                        if (target) {
                            target.click();
                            return;
                        }
                    } catch(e) {}
                    if (++attempts < maxAttempts) {
                        setTimeout(tryClick, 200);
                    }
                }
                tryClick();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Log.d(TAG, "Injected sidebar tab click JS for tab=$tabIndex on $url")
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

    /** 创建空响应 — 用于拦截非必要资源请求 */
    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
