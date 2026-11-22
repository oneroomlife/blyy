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
 * 三条硬约束（都是踩过的坑）：
 * 1. Coil 返回的位图是**共享缓存实例**，绝不能 recycle（会毒化缓存，之后每次合成都拿不到图）；
 * 2. 任何 `runCatching` 都会吞掉协程取消信号，取数后要补 `ensureActive()`；
 * 3. 语音文件名必须带 URL 摘要（[VoiceCardPolicy.audioFileName]）：中配/日配是同一句台词的
 *    两个文件，只按「舰名_场景」命名会互相覆盖，分享出去的语音语言随机。
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
     * @return Coil 缓存里的**共享位图**，调用方不得 recycle（要画要压就自己建副本）
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
     * 返回的 File 归调用方支配：图片版随分享缓存一起被 prune（HTML 卡片不再下载音频）。
     */
    suspend fun downloadAudio(
        context: Context,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): File? = withContext(Dispatchers.IO) {
        if (!VoiceCardPolicy.isHttpUrl(audioUrl)) {
            if (audioUrl.isNotBlank()) {
                Log.w(TAG, "downloadAudio rejected non-http url: ${VoiceCardPolicy.takeCodePoints(audioUrl, 32)}")
            }
            return@withContext null
        }

        // 先写 .part 再改名：中途失败/取消不会留下半截 mp3 被下一次分享当成完整音频发出去，
        // 也不会把同名缓存里**已经下好的完整文件**截断（旧实现直接 target.outputStream() 会）
        val dir = File(context.cacheDir, AUDIO_DIR).apply { mkdirs() }
        val target = File(dir, VoiceCardPolicy.audioFileName(shipName, sceneName, audioUrl))
        val partial = File(dir, target.name + ".part")

        val result = runCatching {
            val url = URL(audioUrl.trim())
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
                    partial.outputStream().use { output ->
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
                if (partial.length() <= 0L) {
                    null
                } else if (!partial.renameTo(target)) {
                    // 改名失败（个别 ROM 的 cache 挂在特殊文件系统上）：退化成本地拷贝
                    Log.w(TAG, "downloadAudio rename failed, copy instead")
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                    target.takeIf { it.length() > 0L }
                } else {
                    target
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "downloadAudio failed: ${it.message}", it) }.getOrNull()

        if (result == null) {
            // 只清理本次的 .part；同名完整缓存（另一个语言分支/重试）不动
            runCatching { partial.delete() }
        }
        result
    }

    /**
     * 目录里只保留最近 [keep] 个文件，避免反复分享把缓存撑大。
     *
     * 两道保护：
     * - 跳过 `.part`：正在下载的临时文件被删掉，下载会以"文件不存在"失败；
     * - 跳过最近 [PROTECT_MS] 内写过的文件：分享面板弹出后，接收方应用可能还没读这个 Uri，
     *   立刻删掉的结果是"对方收到一个 0 字节附件"。分享完成后的清理只应处理明显的旧垃圾。
     */
    fun prune(dir: File, keep: Int) {
        runCatching {
            val now = System.currentTimeMillis()
            dir.listFiles()
                ?.filter { !it.name.endsWith(".part") }
                ?.filter { now - it.lastModified() > PROTECT_MS }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }

    /** 最近这么长时间内写过的文件不参与清理（分享面板还在路上） */
    private const val PROTECT_MS = 60_000L

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
}
