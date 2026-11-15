package com.azurlane.blyy.util.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.azurlane.blyy.util.MediaDownloader
import com.azurlane.blyy.util.RefererResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * 无目标的降级链（裸环境/模拟器/被裁剪的 ROM 都可能出现零分享目标，此时系统 Chooser
 * 只会弹"没有应用可执行此操作"空面板）：
 * - SEND_MULTIPLE 无目标但有 SEND 目标 → 自动只分享台词卡并说明；
 * - 完全无目标 → 台词卡（+语音）落盘到 `Downloads/BLYY` 并告知位置（[ShareResult.SavedLocally]）。
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
        /** 环境里没有任何可分享应用：没有弹分享面板，改为已保存到 Download/BLYY */
        data class SavedLocally(val message: String) : ShareResult()
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
        // 先探测分享目标：裸环境（无任何可接分享的应用）弹 Chooser 只会得到系统的
        // "没有应用可执行此操作"空面板；改为把卡片存到 Download/BLYY 并告知位置
        if (!hasShareTargets(context, Intent.ACTION_SEND, "image/png")) {
            return saveLocally(context, card, null, shipName, "")
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
            } catch (c: kotlinx.coroutines.CancellationException) {
                // 离开页面导致的取消不是"分享失败"，向上传播而不是弹"分享失败"
                throw c
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

        // 目标探测放在下载音频**之前**：没有 SEND_MULTIPLE 目标时不该白等几十秒下载完再弹空面板
        if (!hasShareTargets(context, Intent.ACTION_SEND_MULTIPLE, "*/*")) {
            if (hasShareTargets(context, Intent.ACTION_SEND, "image/png")) {
                // 退化到纯图片分享（shareCard 内部自带无目标兜底）
                val fallback = shareCard(context, card, shipName)
                if (fallback !is ShareResult.Success) return@withContext fallback
                return@withContext ShareResult.AudioSkipped("没有应用支持同时分享图片和语音，已仅分享台词卡")
            }
            // 完全没有可分享应用 → 卡片 + 语音一起存本地
            val audio = downloadAudio(context, audioUrl, shipName, sceneName)
            return@withContext saveLocally(context, card, audio, shipName, sceneName)
        }

        val audio = downloadAudio(context, audioUrl, shipName, sceneName)
        if (audio == null) {
            // 音频拿不到就只发卡片，并把原因带回去（而不是静默只发图）
            val fallback = shareCard(context, card, shipName)
            if (fallback !is ShareResult.Success) return@withContext fallback
            return@withContext ShareResult.AudioSkipped(
                if (audioUrl.isBlank()) "该台词暂无语音地址，已仅分享台词卡"
                else "语音下载失败，已仅分享台词卡"
            )
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
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 离开页面导致的取消不是"分享失败"，向上传播而不是弹"分享失败"
            throw c
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

        // 目标文件引用提升出来：runCatching 内的失败路径（异常/超限/空文件）返回 null 后
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

    /**
     * 是否存在能接 [action] + [mimeType] 的分享目标。
     *
     * Android 11+（API 30）package visibility 下，未在 manifest 声明 `<queries>` 时查询会被
     * 过滤成空列表——因此 SEND/SEND_MULTIPLE 的 `<queries>` 声明是本方法成立的前提。
     */
    private suspend fun hasShareTargets(context: Context, action: String, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(
                Intent(action).apply { type = mimeType },
                PackageManager.MATCH_DEFAULT_ONLY
            ).isNotEmpty()
        }

    /**
     * 无分享目标时的兜底：把台词卡（及可选语音）写入 `Downloads/BLYY`。
     *
     * 复用 [MediaDownloader] 的 MediaStore/File 双路径（API 29+ 免权限，minSdk 24 全覆盖），
     * 不弹系统 Chooser——裸环境弹它只会得到"没有应用可执行此操作"。
     */
    private suspend fun saveLocally(
        context: Context,
        card: File,
        audio: File?,
        shipName: String,
        sceneName: String
    ): ShareResult = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val cardResult = MediaDownloader.saveBytes(
            context,
            card.readBytes(),
            sanitize("${shipName}_台词卡_$stamp") + ".png",
            MediaDownloader.ROOT_DIR
        )
        if (cardResult !is MediaDownloader.Result.Success) {
            val why = (cardResult as? MediaDownloader.Result.Failure)?.message ?: "未知原因"
            return@withContext ShareResult.Failed("没有可分享的应用，且保存台词卡失败：$why")
        }

        val audioNote = audio?.let {
            val audioResult = MediaDownloader.saveBytes(
                context,
                it.readBytes(),
                sanitize("${shipName}_${sceneName}_$stamp") + ".mp3",
                MediaDownloader.ROOT_DIR
            )
            if (audioResult is MediaDownloader.Result.Success) {
                "；语音已保存到 Download/${audioResult.relativePath}/${audioResult.displayName}"
            } else {
                "（语音保存失败）"
            }
        } ?: ""

        ShareResult.SavedLocally(
            "没有可分享的应用，台词卡已保存到 Download/${cardResult.relativePath}/${cardResult.displayName}$audioNote"
        )
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
