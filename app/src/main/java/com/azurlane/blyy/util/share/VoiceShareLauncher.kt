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
 * - 只要卡片：[ACTION_SEND] + `image/png`
 * - 卡片 + 语音：[ACTION_SEND_MULTIPLE] + `image/png` + `audio/mpeg`
 *
 * 音频**按需下载到 cache**（`cache/share_audio/`）后再分享，不触碰用户的 Downloads 目录，
 * 也不要求存储权限；文件随缓存清理自动消失。
 */
object VoiceShareLauncher {

    private const val TAG = "VoiceShareLauncher"
    private const val AUDIO_DIR = "share_audio"
    /** 单条台词音频通常 < 2 MB，超过这个体积就不下载（防止异常 URL 拖死分享流程） */
    private const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 分享台词卡。返回是否成功唤起分享面板（失败时由 UI 提示）。
     */
    fun shareCard(context: Context, card: File, shipName: String): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", card)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$shipName 的台词")
                putExtra(Intent.EXTRA_TEXT, "$shipName · 来自 BLYY")
                clipData = ClipData.newRawUri("voice_card", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享台词卡"))
            true
        }.onFailure { Log.w(TAG, "shareCard failed: ${it.message}", it) }.getOrDefault(false)
    }

    /**
     * 分享台词卡 + 语音文件。
     *
     * @param audioUrl 远程音频地址（失败时自动降级为只分享卡片）
     */
    suspend fun shareCardWithAudio(
        context: Context,
        card: File,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val audio = downloadAudio(context, audioUrl, shipName, sceneName)
        if (audio == null) {
            // 音频拿不到就只发卡片，而不是让整个分享失败
            return@withContext shareCard(context, card, shipName)
        }

        runCatching {
            val authority = "${context.packageName}.fileprovider"
            val cardUri = FileProvider.getUriForFile(context, authority, card)
            val audioUri = FileProvider.getUriForFile(context, authority, audio)
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf<Uri>(cardUri, audioUri)
                )
                putExtra(Intent.EXTRA_SUBJECT, "$shipName 的台词")
                putExtra(Intent.EXTRA_TEXT, "$shipName · 来自 BLYY")
                clipData = ClipData.newRawUri("voice_card", cardUri).apply {
                    addItem(ClipData.Item(audioUri))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享台词卡与语音"))
            true
        }.onFailure { Log.w(TAG, "shareCardWithAudio failed: ${it.message}", it) }.getOrDefault(false)
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

            val connection = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", RefererResolver.getRefererByHost(URL(audioUrl).host ?: ""))
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

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "voice" }
}
