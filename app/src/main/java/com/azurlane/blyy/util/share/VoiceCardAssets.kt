package com.azurlane.blyy.util.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.azurlane.blyy.util.RefererResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 台词卡素材层：头像 + 语音原始文件的获取与缓存清理。
 *
 * 图片版（[VoiceCardComposer]）与 HTML 语音版（[VoiceCardHtmlComposer]）共用同一套取数逻辑：
 * - 头像走 Coil 的内存/磁盘缓存（与列表里显示的是同一张，命中即 0 网络）；
 * - 语音必须带 Referer + 移动端 UA 直连（wiki CDN 防盗链，缺 Referer 会 403），
 *   所以「图片 + mp3 双附件」这条老通道必须在 App 侧下好再分享；HTML 卡片则直接引用原站链接。
 *
 * 两条硬约束（踩过的坑，见 [VoiceCardComposer] 里的说明）：
 * 1. Coil 返回的位图是**共享缓存实例**，绝不能 recycle；
 * 2. 任何 `runCatching` 都会吞掉协程取消信号，取数后要补 `ensureActive()`。
 */
internal object VoiceCardAssets {

    private const val TAG = "VoiceCardAssets"

    /** 语音临时目录（FileProvider 已声明 share_audio/） */
    const val AUDIO_DIR = "share_audio"

    /** 单条台词音频通常 < 1 MB；超过这个体积直接放弃，防止异常 URL 拖死分享流程 */
    const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 加载分享卡片用的头像。
     *
     * 加 3 秒超时：弱网/图床抽风时，用户点「分享」不应该对着转圈的 Toast 等半分钟——
     * 超时就用首字占位，卡片照常生成、照常分享。
     *
     * @return Coil 缓存里的**共享位图**，调用方不得 recycle
     */
    suspend fun loadAvatar(context: Context, url: String, sizePx: Int): Bitmap? {
        if (url.isBlank()) return null
        // 自定义头像是「同路径覆盖写」：内存缓存键必须带上文件 mtime，
        // 否则用户换完头像再分享，卡片里还是上一张图
        val cacheKey = localFileCacheKey(url)
        return withTimeoutOrNull(3_000) {
            val bitmap = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(sizePx)
                    .allowHardware(false)
                    .apply { cacheKey?.let { memoryCacheKey(it) } }
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) result.drawable.toBitmap() else null
            }.getOrNull()
            // runCatching 会连外层的 CancellationException 一起吞掉：
            // 超时/外层取消时这里补抛，让 withTimeoutOrNull 按约定返回 null
            currentCoroutineContext().ensureActive()
            bitmap
        }
    }

    /** 本地真实文件（自定义头像）才生成带 mtime 的缓存键；assets 虚拟路径没有 mtime，返回 null */
    private fun localFileCacheKey(url: String): String? = runCatching {
        if (!url.startsWith("file://")) return null
        val path = android.net.Uri.parse(url).path ?: return null
        val file = File(path)
        if (!file.isFile) null else "$url#rev${file.lastModified()}"
    }.getOrNull()

    /**
     * 下载语音到 `cache/share_audio`。使用与播放器一致的 UA/Referer（源自 [RefererResolver]），
     * 否则 wiki 侧会 403。失败返回 null（调用方降级为纯图片分享）。
     *
     * ⚠️ 返回的 File 归调用方支配：图片版随分享缓存一起被 prune（HTML 卡片不再下载音频）。
     */
    suspend fun downloadAudio(
        context: Context,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): File? = withContext(Dispatchers.IO) {
        if (audioUrl.isBlank()) return@withContext null

        // 目标文件引用提升出来：失败路径（异常/超限/空文件）返回 null 后
        // 在这里统一清掉残缺文件，避免半截 mp3 占着缓存
        var written: File? = null
        runCatching {
            val dir = File(context.cacheDir, AUDIO_DIR).apply { mkdirs() }
            val safeName = buildString {
                append(sanitize(shipName))
                if (sceneName.isNotBlank()) {
                    append('_')
                    append(sanitize(sceneName))
                }
                append(".mp3")
            }
            val target = File(dir, safeName)
            written = target

            val url = URL(audioUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", RefererResolver.getRefererByHost(url.host ?: ""))
            }
            connection.connect()
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "downloadAudio HTTP ${connection.responseCode}")
                    return@runCatching null
                }
                val declared = connection.contentLengthLong
                if (declared > MAX_AUDIO_BYTES) {
                    Log.w(TAG, "downloadAudio too large: $declared")
                    return@runCatching null
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_AUDIO_BYTES) {
                                Log.w(TAG, "downloadAudio exceeded cap, abort")
                                return@runCatching null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (target.length() <= 0L) null else target
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "downloadAudio failed: ${it.message}", it) }.getOrNull()
            ?: run { written?.let { runCatching { it.delete() } }; null }
    }

    /** 目录里只保留最近 [keep] 个文件，避免反复分享把缓存撑大 */
    fun prune(dir: File, keep: Int) {
        runCatching {
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
        if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) return bitmap
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "voice" }
}
