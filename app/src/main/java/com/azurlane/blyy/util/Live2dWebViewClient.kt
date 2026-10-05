package com.azurlane.blyy.util

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Live2D 查看器专用 WebView 拦截器。
 *
 * 页面与模型全部经由虚拟域名 `https://live2d.local` 提供：
 *  - /viewer.html /viewer.css /viewer.js 以及 /js/ 目录下的运行时脚本 → assets/live2d/
 *  - /models/<id>/__l2d_manifest__.json → 现场生成（含 model3.json 文件名）
 *  - /models/<id>/<相对路径> → <live2d根>/<id>/ 下的真实文件（带防穿越校验）
 *
 * 统一走 https 虚拟域名而非 file:// 的原因：同源（无 CORS 限制）、
 * 无需打开 allowFileAccessFromFileURLs 危险开关、URL 语义清晰。
 */
class Live2dWebViewClient(
    context: Context,
    private val library: Live2dLibrary,
    /** 读取当前模型信息（用于现场生成 manifest；后台线程调用，需线程安全） */
    private val infoProvider: () -> Live2dModelInfo?
) : WebViewClient() {

    companion object {
        const val VIRTUAL_HOST = "live2d.local"
        private const val TAG = "Live2dWebViewClient"
        private const val MANIFEST_NAME = "__l2d_manifest__.json"

        private val MIME: Map<String, String> = mapOf(
            "html" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "json" to "application/json",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "moc3" to "application/octet-stream",
            "mtn" to "application/octet-stream",
            "wav" to "audio/wav",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg"
        )

        private fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MIME[ext] ?: "application/octet-stream"
        }
    }

    private val appContext = context.applicationContext

    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != VIRTUAL_HOST) return null
        return runCatching { serve(url.pathSegments.orEmpty(), url.path ?: "/") }
            .onFailure { Log.w(TAG, "拦截请求失败: $url", it) }
            .getOrNull()
    }

    private fun serve(segments: List<String>, rawPath: String): WebResourceResponse? {
        return when {
            segments.isEmpty() -> assetResponse("viewer.html")
            segments.size == 1 && segments[0] == "viewer.html" -> assetResponse("viewer.html")
            segments.size == 1 && segments[0] == "viewer.css" -> assetResponse("viewer.css")
            segments.size == 1 && segments[0] == "viewer.js" -> assetResponse("viewer.js")
            segments.size == 2 && segments[0] == "js" -> assetResponse("js/${segments[1]}")
            segments.size >= 2 && segments[0] == "models" -> serveModel(segments[1], segments.drop(2))
            else -> null
        }?.let { resp ->
            resp.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            resp
        }
    }

    // ---------- assets ----------

    private fun assetResponse(assetPath: String): WebResourceResponse? {
        val stream = runCatching { appContext.assets.open("live2d/$assetPath") }.getOrNull()
            ?: return null
        return WebResourceResponse(mimeFor(assetPath), if (assetPath.endsWith(".html")) "utf-8" else null, stream)
    }

    // ---------- 模型文件 ----------

    private fun serveModel(id: String, rest: List<String>): WebResourceResponse? {
        if (rest.isEmpty() || !library.isValidId(id)) return null
        val relPath = rest.joinToString("/")

        if (rest.size == 1 && rest[0] == MANIFEST_NAME) {
            val info = infoProvider()
                ?: return jsonResponse("{\"error\":\"model not found\"}")
            val manifest = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(info.id),
                    "model3" to JsonPrimitive(info.model3File),
                    "moc" to JsonPrimitive(info.mocFile ?: "")
                )
            ).toString()
            return jsonResponse(manifest)
        }

        val modelDir = File(library.rootDir(), id)
        if (!modelDir.isDirectory) return null
        val target = File(modelDir, relPath)
        if (!target.canonicalPath.startsWith(modelDir.canonicalPath + File.separator) &&
            target.canonicalPath != modelDir.canonicalPath
        ) {
            Log.w(TAG, "拦截到越界请求: $relPath")
            return null
        }
        if (!target.isFile) return null
        return WebResourceResponse(mimeFor(target.name), null, FileInputStream(target))
    }

    private fun jsonResponse(body: String): WebResourceResponse =
        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
}
