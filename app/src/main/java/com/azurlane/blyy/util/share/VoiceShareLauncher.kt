package com.azurlane.blyy.util.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.azurlane.blyy.util.MediaDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 台词卡 / 语音的分享出口。
 *
 * 三条通道，按「信息量」从高到低：
 * 1. **HTML 台词语音卡**（[shareHtmlCard]）：单文件、头像内嵌、语音走原站链接、自带播放按钮，
 *    收到的人点开就能听——这是「语音档案」的主推分享形态；
 * 2. 图片卡 + mp3 两个附件（[shareCardWithAudio]）：老通道，兼容只接 `SEND_MULTIPLE` 的应用；
 * 3. 纯图片卡（[shareCard]）：最稳的一条，几乎任何聊天/相册都能接。
 *
 * 返回值是 [ShareResult] 而不是 Boolean：分享失败的原因（FileProvider 未配置 / 无宿主 App /
 * 文件丢失）对用户与排查都太关键，不能统一显示成"没有可用的分享应用"。
 *
 * 无目标的降级链（裸环境/模拟器/被裁剪的 ROM 都可能出现零分享目标，此时系统 Chooser
 * 只会弹"没有应用可执行此操作"空面板）：
 * - HTML 卡无 `text/html|text/plain` 目标 → 直接落盘到 `Downloads/BLYY` 并告知路径（[ShareResult.SavedLocally]）；
 * - SEND_MULTIPLE 无目标但有 SEND 目标 → 自动只分享台词卡并说明；
 * - 完全无目标 → 台词卡（+语音）落盘并告知位置。
 */
object VoiceShareLauncher {

    private const val TAG = "VoiceShareLauncher"

    /**
     * HTML 语音卡的 MIME。
     *
     * ⚠️ 探测阶段必须用 text 通配（[MIME_HTML_PROBE]）而不是死磕 [MIME_HTML]：
     * 微信/QQ/Telegram 这类聊天应用只声明 text/plain，拿 text/html 去 `queryIntentActivities`
     * 会得到空列表 → 误判"没有可分享的应用" → 明明能发出去却只给了个"已保存到 Downloads"。
     * 选择器阶段再用 text/html 发（能接 html 的应用才会正确按网页打开）。
     *
     * （注意别在 KDoc 里写 text 加斜杠加星号——Kotlin 块注释是**可嵌套**的，
     * 那个组合会开一个新注释层，把后面整个文件吃掉。）
     */
    private const val MIME_HTML = "text/html"
    private const val MIME_HTML_PROBE = "text/*"

    sealed class ShareResult {
        data object Success : ShareResult()
        /** 分享面板已弹出，但语音没拿到（已降级为纯图片） */
        data class AudioSkipped(val message: String) : ShareResult()
        /** 环境里没有任何可分享应用：没有弹分享面板，改为已保存到 Download/BLYY */
        data class SavedLocally(val message: String) : ShareResult()
        data class Failed(val message: String) : ShareResult()
    }

    // ── HTML 台词语音卡 ──

    /**
     * 分享 HTML 台词语音卡。
     *
     * 注意 `startActivity` 必须在主线程发起，而卡片生成/读取在 IO 线程，
     * 统一在这里 `withContext(Dispatchers.Main)` 收口。
     */
    suspend fun shareHtmlCard(
        context: Context,
        card: File,
        shipName: String,
        dialogue: String,
        sceneName: String = ""
    ): ShareResult {
        if (!card.isFile || card.length() <= 0L) {
            return ShareResult.Failed("语音卡文件不存在，请重试")
        }
        // 聊天类应用普遍不声明 text/html，先按 text/* 探测，避免弹一个只有"复制到剪贴板"的空面板
        if (!hasShareTargets(context, Intent.ACTION_SEND, MIME_HTML_PROBE)) {
            // 无接收方时直接落盘；提示语已经说明"存到哪 + 怎么听"，不再追加解释
            // （Toast 在多数 ROM 上只显示两行，多一句就会被截断成"…"）
            return saveHtmlCard(context, card, shipName, sceneName)
        }
        return withContext(Dispatchers.Main) {
            try {
                val uri = buildUri(context, card)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = MIME_HTML
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "$shipName · 台词语音卡")
                    putExtra(Intent.EXTRA_TEXT, buildShareText(shipName, dialogue))
                    clipData = ClipData.newRawUri("voice_card_html", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享台词语音卡"))
                ShareResult.Success
            } catch (c: kotlinx.coroutines.CancellationException) {
                // 离开页面导致的取消不是"分享失败"，向上传播而不是弹"分享失败"
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "shareHtmlCard failed: ${e.message}", e)
                ShareResult.Failed(describe(e))
            }
        }
    }

    /** 把 HTML 语音卡落到 `Downloads/BLYY`（无分享目标 / 用户主动保存） */
    suspend fun saveHtmlCard(
        context: Context,
        card: File,
        shipName: String,
        sceneName: String
    ): ShareResult = withContext(Dispatchers.IO) {
        if (!card.isFile || card.length() <= 0L) {
            return@withContext ShareResult.Failed("语音卡文件不存在，请重试")
        }
        val bytes = runCatching { card.readBytes() }.getOrNull()
            ?: return@withContext ShareResult.Failed("语音卡读取失败，请重试")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val baseName = buildString {
            append(safeLabel(shipName))
            append("_台词语音卡_")
            if (sceneName.isNotBlank()) {
                append(safeLabel(sceneName))
                append('_')
            }
            append(stamp)
        }
        when (
            val result = MediaDownloader.saveBytes(
                context,
                bytes,
                "$baseName.html",
                // HTML 落到独立子目录：和 mp3/png 混在一起时，用户按"音频"筛选找不到它，
                // 且同名去重行为会随相册索引变化
                "${MediaDownloader.ROOT_DIR}/VoiceCard"
            )
        ) {
            is MediaDownloader.Result.Success -> ShareResult.SavedLocally(
                "语音卡已保存到 Download/${result.relativePath}/${result.displayName}，用浏览器打开可播放语音"
            )
            is MediaDownloader.Result.Failure -> ShareResult.Failed("保存语音卡失败：${result.message}")
        }
    }

    /** 分享文案：正文 + 来源，附带一句"点开能听"的提示（部分应用会把 EXTRA_TEXT 直接展示） */
    private fun buildShareText(shipName: String, dialogue: String): String {
        val body = VoiceCardPolicy.excerpt(dialogue)
        return buildString {
            if (body.isNotEmpty()) {
                append(body)
                append("\n\n")
            }
            append("—— $shipName · BLYY 台词语音卡（打开即可播放语音）")
        }
    }

    // ── 图片卡 ──

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
        if (!hasShareTargets(context, Intent.ACTION_SEND, "image/*")) {
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

        // 目标探测放在下载音频**之前**：没有 SEND_MULTIPLE 目标时不该白等几十秒下载完再弹空面板。
        // 探测 MIME 用 `*/*` 是刻意的：SEND_MULTIPLE 的接收方几乎都声明 `*/*` 或 `image/*`，
        // 用具体类型探测会漏掉只声明 `*/*` 的相册/文件管理器。
        if (!hasShareTargets(context, Intent.ACTION_SEND_MULTIPLE, "*/*")) {
            if (hasShareTargets(context, Intent.ACTION_SEND, "image/*")) {
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

    // ── 语音素材 ──

    /**
     * 下载音频到缓存目录（带 Referer/UA，wiki 侧缺 Referer 会 403）。
     * 失败返回 null（调用方降级）。
     */
    suspend fun downloadAudio(
        context: Context,
        audioUrl: String,
        shipName: String,
        sceneName: String
    ): File? = VoiceCardAssets.downloadAudio(context, audioUrl, shipName, sceneName)

    /** 只保留最近的分享音频，避免长期占用缓存 */
    fun pruneAudioCache(context: Context, keep: Int = 5) {
        VoiceCardAssets.prune(File(context.cacheDir, VoiceCardAssets.AUDIO_DIR), keep)
    }

    /**
     * 是否存在能接 [action] + [mimeType] 的分享目标。
     *
     * Android 11+（API 30）package visibility 下，未在 manifest 声明 `<queries>` 时查询会被
     * 过滤成空列表——因此 SEND/SEND_MULTIPLE 的 `<queries>` 声明是本方法成立的前提。
     */
    private suspend fun hasShareTargets(context: Context, action: String, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            // 查询本身抛异常（个别 ROM 的 PackageManager 会）时按"有目标"处理：
            // 宁可直接弹 Chooser（有降级兜底），也不要无端把用户踹到"已保存到 Downloads"
            runCatching {
                context.packageManager.queryIntentActivities(
                    Intent(action).apply { type = mimeType },
                    PackageManager.MATCH_DEFAULT_ONLY
                ).isNotEmpty()
            }.getOrDefault(true)
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
        // 读文件放在 try 里：卡片可能已被 prune/被系统清掉，readBytes 抛 FileNotFoundException
        // 会在旧实现里直接穿出 suspend 函数变成崩溃（调用侧只 finally 复位 flag，不接异常）
        val cardBytes = runCatching { card.readBytes() }.getOrNull()
            ?: return@withContext ShareResult.Failed("没有可分享的应用，且台词卡已失效，请重新生成")

        val cardResult = MediaDownloader.saveBytes(
            context,
            cardBytes,
            safeLabel("${shipName}_台词卡_$stamp") + ".png",
            MediaDownloader.ROOT_DIR
        )
        if (cardResult !is MediaDownloader.Result.Success) {
            val why = (cardResult as? MediaDownloader.Result.Failure)?.message ?: "未知原因"
            return@withContext ShareResult.Failed("没有可分享的应用，且保存台词卡失败：$why")
        }

        val audioNote = audio?.let {
            val bytes = runCatching { it.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                "（语音读取失败，未保存）"
            } else {
                val audioResult = MediaDownloader.saveBytes(
                    context,
                    bytes,
                    safeLabel("${shipName}_${sceneName}_$stamp", fallback = "voice") + ".mp3",
                    MediaDownloader.ROOT_DIR
                )
                if (audioResult is MediaDownloader.Result.Success) {
                    "；语音已保存到 Download/${audioResult.relativePath}/${audioResult.displayName}"
                } else {
                    "（语音保存失败）"
                }
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
        error is SecurityException -> "分享被系统拦截：请检查是否停用了相关应用"
        else -> "分享失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun safeLabel(raw: String, fallback: String = "voice"): String =
        VoiceCardPolicy.safeName(raw, max = 40, fallback = fallback)
}
