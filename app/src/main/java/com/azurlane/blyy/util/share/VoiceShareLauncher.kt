package com.azurlane.blyy.util.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.azurlane.blyy.util.RefererResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 台词卡 / 语音的分享出口。
 *
 * 与旧实现的差别：以前「分享」= 把台词和链接写进剪贴板（用户还得自己再粘一遍）。
 * 现在走系统分享面板：
 * - 只要卡片：[Intent.ACTION_SEND] + `image/png`
 * - 卡片 + 语音：[Intent.ACTION_SEND_MULTIPLE] + `image/png` + `audio/mpeg`
 *
 * 返回值是 [ShareResult] 而不是 Boolean：分享失败的原因（FileProvider 未配置 / 无宿主 App /
 * 文件丢失）对用户与排查都太关键，不能统一显示成"没有可用的分享应用"。
 */
object VoiceShareLauncher {

    private const val TAG = "VoiceShareLauncher"
    private const val AUDIO_DIR = "share_audio"

    /** 单条台词音频通常 < 2 MB，超过这个体积就不下载（防止异常 URL 拖死分享流程） */
    private const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    sealed class ShareResult {
        data object Success : ShareResult()
        /** 分享面板已弹出，但语音没拿到（已降级为纯图片） */
        data class AudioSkipped(val message: String) : ShareResult()
        data class Failed(val message: String) : ShareResult()
    }

    /**
     * 分享台词卡（纯图片）。
     *
     * 注意是 suspend：`startActivity` 必须在主线程发起，而本方法也可能从
     * 音频下载的 IO 分支里被调用（见 [shareCardWithAudio]），统一在这里切线程。
     */
    suspend fun shareCard(context: Context, card: File, shipName: String): ShareResult {
        if (!card.isFile || card.length() <= 0L) {
            return ShareResult.Failed("台词卡文件不存在，请重试")
        }
        return withContext(Dispatchers.Main) {
            try {
                val uri = buildUri(context, card)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "$shipName 的台词")
                    putExtra(Intent.EXTRA_TEXT, "$shipName · 来自 BLYY")
                    clipData = ClipData.newRawUri("voice_card", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享台词卡"))
                ShareResult.Success
            } catch (e: Exception) {
                Log.w(TAG, "shareCard failed: ${e.message}", e)
                ShareResult.Failed(describe(e))
            }
        }
    }

    /**
     * 分享台词卡 + 语音文件。
     *
     * @param audioUrl 远程音频地址；下载失败时**降级为只分享卡片**并如实告知
     */
    suspend fun shareCardWithAudio(
        context: Context,
        card: File,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): ShareResult = withContext(Dispatchers.IO) {
        if (!card.isFile || card.length() <= 0L) {
            return@withContext ShareResult.Failed("台词卡文件不存在，请重试")
        }

        val audio = downloadAudio(context, audioUrl, shipName, sceneName)
        if (audio == null) {
            // 音频拿不到就只发卡片，并把原因带回去（而不是静默只发图）
            val fallback = shareCard(context, card, shipName)
            return@withContext if (fallback is ShareResult.Success) {
                ShareResult.AudioSkipped("语音下载失败，已仅分享台词卡")
            } else {
                fallback
            }
        }

        try {
            withContext(Dispatchers.Main) {
                val cardUri = buildUri(context, card)
                val audioUri = buildUri(context, audio)
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(cardUri, audioUri))
                    putExtra(Intent.EXTRA_SUBJECT, "$shipName 的台词")
                    putExtra(Intent.EXTRA_TEXT, "$shipName · 来自 BLYY")
                    clipData = ClipData.newRawUri("voice_card", cardUri).apply {
                        addItem(ClipData.Item(audioUri))
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享台词卡与语音"))
            }
            ShareResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "shareCardWithAudio failed: ${e.message}", e)
            ShareResult.Failed(describe(e))
        }
    }

    /**
     * 下载音频到缓存目录。使用与播放器一致的 UA/Referer（源自 [RefererResolver]），
     * 否则 wiki 侧会 403。失败返回 null（调用方降级为纯图片分享）。
     */
    suspend fun downloadAudio(
        context: Context,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): File? = withContext(Dispatchers.IO) {
        if (audioUrl.isBlank()) return@withContext null
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
    }

    /** 只保留最近的分享音频，避免长期占用缓存 */
    fun pruneAudioCache(context: Context, keep: Int = 5) {
        runCatching {
            File(context.cacheDir, AUDIO_DIR)
                .listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }

    private fun buildUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 把底层异常翻译成用户能看懂的提示（FileProvider 配置错误是最容易踩的坑） */
    private fun describe(error: Throwable): String = when {
        error is IllegalArgumentException ->
            "分享配置异常：${error.message ?: "文件不在 FileProvider 允许的目录"}"
        error is android.content.ActivityNotFoundException -> "没有可用的分享应用"
        else -> "分享失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "voice" }
}
