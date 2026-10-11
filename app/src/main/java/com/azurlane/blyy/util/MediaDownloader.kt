package com.azurlane.blyy.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体文件统一下载工具 — 写入公共 Downloads 目录的 BLYY 子目录。
 *
 * 历史实现（ShipGallery/StudentGallery/VoiceScreen）直接 URL.openStream() 写
 * getExternalStoragePublicDirectory，在 API 29+ scoped storage 下无写入权限时静默失败。
 * 本工具统一改为：API 29+ 走 MediaStore.Downloads（无需存储权限）；
 * API 28- 回退 File API（旧版本仍受 WRITE_EXTERNAL_STORAGE 管辖，与原行为一致）。
 */
object MediaDownloader {

    /** 下载结果 */
    sealed class Result {
        data class Success(val displayName: String, val relativePath: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /** 下载根目录（Downloads/BLYY 下的业务子目录） */
    const val ROOT_DIR = "BLYY"

    /**
     * 下载 [url] 到 Downloads/[subPath]（如 "BLYY/Gallery"），文件名 [fileName] 需含扩展名。
     * 同名文件由 MediaStore 自动追加 " (1)" 去重。
     */
    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        subPath: String = ROOT_DIR
    ): Result = withContext(Dispatchers.IO) {
        try {
            val bytes = fetch(url) ?: return@withContext Result.Failure("网络错误或资源不可达")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bytes, fileName, subPath)
            } else {
                saveViaFileApi(bytes, fileName, subPath)
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "下载失败")
        }
    }

    /** 按主机推断 Referer，与语音/图片源站的防盗链要求保持一致 */
    fun refererForHost(host: String): String? = when {
        host.contains("gamekee") -> "https://www.gamekee.com/"
        host.contains("biligame") || host.contains("hdslb") -> "https://wiki.biligame.com/"
        else -> "https://www.google.com/"
    }

    private fun fetch(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            refererForHost(connection.url.host)?.let { connection.setRequestProperty("Referer", it) }
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 写入 MediaStore Downloads（API 29+ scoped storage）。
     * 标注 [RequiresApi] 供 lint 校验：唯一调用点已由 SDK_INT >= Q 守卫。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        subPath: String
    ): Result {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failure("无法创建下载项")
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.Failure("无法写入下载项")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Success(fileName, subPath)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            Result.Failure(e.message ?: "写入失败")
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaFileApi(bytes: ByteArray, fileName: String, subPath: String): Result {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            subPath
        )
        if (!dir.exists()) dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
        return Result.Success(fileName, subPath)
    }
}
