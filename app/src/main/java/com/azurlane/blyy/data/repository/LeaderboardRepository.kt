package com.azurlane.blyy.data.repository

import android.util.Base64
import android.util.Log
import com.azurlane.blyy.data.model.LeaderboardCategory
import com.azurlane.blyy.data.model.LeaderboardData
import com.azurlane.blyy.data.model.LeaderboardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LeaderboardRepository"

/**
 * 基于 GitHub 仓库的排行榜数据仓库
 *
 * 使用 GitHub Contents API 读写 JSON 文件作为排行榜后端：
 * - 读取: raw.githubusercontent.com（公开仓库无需鉴权）
 * - 写入: api.github.com/repos/{owner}/{repo}/contents/leaderboard.json（需要 Token）
 *
 * Token 通过 [LeaderboardCrypto] 在运行时解密，明文仅存在于内存中。
 */
@Singleton
class LeaderboardRepository @Inject constructor(
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 镜像专用客户端：复用主客户端的连接池与缓存，但使用更短的超时时间，
     * 避免某个不可用的镜像长时间阻塞导致整体加载过慢。
     */
    private val mirrorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(MIRROR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(MIRROR_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(
                MIRROR_CONNECT_TIMEOUT_MS + MIRROR_READ_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            .build()
    }

    companion object {
        private const val FILE_PATH = "leaderboard.json"
        private const val MAX_ENTRIES_PER_BOARD = 100
        private const val REPO_OWNER = "oneroomlife"
        private const val REPO_NAME = "blyy-leaderboard"

        /**
         * GitHub 加速镜像列表（按优先级排序）。
         *
         * 直连 raw.githubusercontent.com 在大陆地区访问缓慢甚至超时，
         * 这里配置多个公开加速镜像，按顺序尝试，任一可用即返回。
         * 镜像 URL 模板中使用 `{owner}` `{repo}` `{path}` 占位符。
         *
         * 注意：仅用于读取（raw）场景，写入仍走 api.github.com 官方接口。
         */
        private val GITHUB_MIRRORS: List<String> = listOf(
            // 直连（保留作为首选，海外节点或已配置 VPN 时性能最佳）
            "https://raw.githubusercontent.com/{owner}/{repo}/main/{path}",
            // 大陆加速镜像（顺序即优先级，失败自动切换下一个）
            "https://ghp.ci/{owner}/{repo}/main/{path}",
            "https://gh-proxy.com/{owner}/{repo}/main/{path}",
            "https://mirror.ghproxy.com/{owner}/{repo}/main/{path}",
            "https://ghproxy.net/{owner}/{repo}/main/{path}"
        )

        /** 单个镜像请求超时时间（毫秒），避免某个镜像卡死拖慢整体加载 */
        private const val MIRROR_CONNECT_TIMEOUT_MS = 8000L
        /** 单镜像读取超时（毫秒） */
        private const val MIRROR_READ_TIMEOUT_MS = 10000L
    }

    /**
     * 对排行榜条目按 UID 去重，每个 UID 仅保留最高分记录。
     * 同分时保留准确率更高、时间更近的记录（最新优先）。
     */
    private fun deduplicateByUid(entries: List<LeaderboardEntry>): List<LeaderboardEntry> {
        return entries
            .groupBy { it.uid }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareByDescending<LeaderboardEntry> { it.score }
                        .thenByDescending { it.accuracy }
                        .thenByDescending { it.timestamp }
                ).first()
            }
            .values
            .sortedWith(
                compareByDescending<LeaderboardEntry> { it.score }
                    .thenByDescending { it.accuracy }
                    .thenByDescending { it.timestamp }
            )
    }

    /**
     * 净化 UID：移除前导控制字符（如 \u0016 SYN），仅保留可见字符。
     * 修复历史数据中 UID 前缀混入控制字符导致同一用户被识别为不同 UID 的问题。
     */
    private fun sanitizeUid(uid: String): String {
        return uid.trimStart { it.isISOControl() || it <= ' ' }.trim()
    }

    /** 净化所有条目的 UID，过滤掉 UID 为空的无效记录 */
    private fun sanitizeEntries(data: LeaderboardData): LeaderboardData {
        fun cleanList(list: List<LeaderboardEntry>) =
            list.map { it.copy(uid = sanitizeUid(it.uid)) }.filter { it.uid.isNotBlank() }
        return data.copy(
            image_easy = cleanList(data.image_easy),
            image_hard = cleanList(data.image_hard),
            voice_easy = cleanList(data.voice_easy),
            voice_hard = cleanList(data.voice_hard)
        )
    }

    /** 验证排行榜条目数据合法性，返回失败原因或 null */
    private fun validateEntry(entry: LeaderboardEntry): Result<Unit> {
        val errors = mutableListOf<String>()
        if (entry.uid.isBlank()) errors.add("UID 为空")
        if (entry.server.isBlank()) errors.add("服务器为空")
        if (entry.score < 0) errors.add("分数为负数")
        if (entry.totalQuestions < 0) errors.add("题数为负数")
        if (entry.correctAnswers < 0 || entry.correctAnswers > entry.totalQuestions) {
            errors.add("答题数据不合法(correct=${entry.correctAnswers}, total=${entry.totalQuestions})")
        }
        if (entry.accuracy < 0f || entry.accuracy > 1f) {
            errors.add("准确率越界(${entry.accuracy})")
        }
        if (entry.timestamp <= 0L) errors.add("时间戳无效")
        if (entry.formatVersion <= 0) errors.add("格式版本无效(${entry.formatVersion})")
        return if (errors.isEmpty()) Result.success(Unit)
        else Result.failure(Exception("数据校验失败: ${errors.joinToString("; ")}"))
    }

    /**
     * 拉取排行榜数据
     *
     * 读取流程（按优先级自动切换）：
     * 1. 依次尝试 [GITHUB_MIRRORS] 中的镜像（含直连 raw URL），任一成功即返回
     * 2. 全部镜像失败时，回退到 GitHub Contents API（需要 Token）
     *
     * 每个镜像请求均带时间戳参数破除 CDN 缓存，确保获取最新数据。
     * 返回的数据已净化 UID 并按 UID 去重，每个 UID 仅保留最高分。
     */
    suspend fun fetchLeaderboard(): Result<LeaderboardData> = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()

        // ── 阶段 1：依次尝试镜像列表 ──
        for ((index, template) in GITHUB_MIRRORS.withIndex()) {
            val url = template
                .replace("{owner}", REPO_OWNER)
                .replace("{repo}", REPO_NAME)
                .replace("{path}", FILE_PATH)
                .plus("?t=$timestamp")

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "blyy-leaderboard/1.0")
                    .build()
                mirrorClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val data = json.decodeFromString<LeaderboardData>(body)
                            val sanitized = sanitizeEntries(data)
                            Log.i(TAG, "镜像读取成功: #${index + 1}/${GITHUB_MIRRORS.size} -> $url")
                            return@withContext Result.success(deduplicateData(sanitized))
                        }
                    } else {
                        Log.w(TAG, "镜像 #${index + 1} HTTP ${response.code}: $url")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "镜像 #${index + 1} 失败，切换下一个: ${e.message}")
            }
        }

        // ── 阶段 2：所有镜像失败，回退到 GitHub Contents API（需要 Token） ──
        Log.w(TAG, "所有镜像均失败，回退到 GitHub API")
        val token = try {
            LeaderboardCrypto.decryptToken()
        } catch (e: SecurityException) {
            return@withContext Result.failure(Exception("Token 解密失败，请检查应用完整性"))
        }
        try {
            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$FILE_PATH"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext Result.failure(Exception("空响应"))
                    val jsonObj = JSONObject(body)
                    val content = jsonObj.getString("content")
                    val decoded = Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
                    val data = json.decodeFromString<LeaderboardData>(decoded)
                    val sanitized = sanitizeEntries(data)
                    Log.i(TAG, "GitHub API 读取成功")
                    return@withContext Result.success(deduplicateData(sanitized))
                } else if (response.code == 404) {
                    // 文件不存在，返回空排行榜
                    Log.i(TAG, "排行榜文件不存在，返回空数据")
                    return@withContext Result.success(LeaderboardData())
                }
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API fetch failed: ${e.message}")
            return@withContext Result.failure(Exception("读取排行榜失败: ${e.message}"))
        }
    }

    /** 对四个榜单分别按 UID 去重 */
    private fun deduplicateData(data: LeaderboardData): LeaderboardData {
        return data.copy(
            image_easy = deduplicateByUid(data.image_easy).take(MAX_ENTRIES_PER_BOARD),
            image_hard = deduplicateByUid(data.image_hard).take(MAX_ENTRIES_PER_BOARD),
            voice_easy = deduplicateByUid(data.voice_easy).take(MAX_ENTRIES_PER_BOARD),
            voice_hard = deduplicateByUid(data.voice_hard).take(MAX_ENTRIES_PER_BOARD)
        )
    }

    // ── 本地缓存读写（与 [PlayerSettingsDataStore] 协作） ──

    /**
     * 将排行榜数据序列化为 JSON 字符串，供本地缓存使用。
     */
    fun encodeCacheJson(data: LeaderboardData): String {
        return json.encodeToString(data)
    }

    /**
     * 从 JSON 字符串反序列化排行榜数据，用于读取本地缓存。
     * 解析失败时返回 null。
     */
    fun decodeCacheJson(jsonStr: String): LeaderboardData? {
        return try {
            val data = json.decodeFromString<LeaderboardData>(jsonStr)
            deduplicateData(sanitizeEntries(data))
        } catch (e: Exception) {
            Log.w(TAG, "本地缓存解析失败: ${e.message}")
            null
        }
    }

    /**
     * 上传一条成绩到排行榜
     * 读取当前文件 → 验证数据 → 按 UID 去重追加 → 排序截断 → 写回
     *
     * 详细日志记录各阶段状态，便于追踪上传问题。
     */
    suspend fun uploadScore(
        entry: LeaderboardEntry,
        category: LeaderboardCategory
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 净化 UID（移除控制字符），防止脏数据写入
        val cleanEntry = entry.copy(uid = sanitizeUid(entry.uid))

        // 数据合法性验证
        validateEntry(cleanEntry).onFailure {
            Log.e(TAG, "上传数据校验失败: uid=${cleanEntry.uid}, category=${category.key}, reason=${it.message}")
            return@withContext Result.failure(it)
        }
        Log.i(TAG, "开始上传: uid=${cleanEntry.uid}, category=${category.key}, score=${cleanEntry.score}, " +
                "appVer=${cleanEntry.appVersion}, fmtVer=${cleanEntry.formatVersion}")

        val token = try {
            LeaderboardCrypto.decryptToken()
        } catch (e: SecurityException) {
            Log.e(TAG, "Token 解密失败，上传中止", e)
            return@withContext Result.failure(Exception("Token 解密失败，请检查应用完整性"))
        }
        try {
            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$FILE_PATH"

            // 1. 获取当前文件内容和 SHA
            var sha: String? = null
            var currentData = LeaderboardData()

            val getRequest = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext Result.failure(Exception("空响应"))
                    val jsonObj = JSONObject(body)
                    sha = jsonObj.optString("sha").takeIf { it.isNotBlank() }
                    val content = jsonObj.getString("content")
                    val decoded = Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
                    currentData = json.decodeFromString<LeaderboardData>(decoded)
                    Log.d(TAG, "读取到现有数据: schema=${currentData.schema_version}, " +
                            "image_easy=${currentData.image_easy.size}, " +
                            "image_hard=${currentData.image_hard.size}, " +
                            "voice_easy=${currentData.voice_easy.size}, " +
                            "voice_hard=${currentData.voice_hard.size}")
                } else if (response.code != 404) {
                    Log.e(TAG, "读取排行榜失败: HTTP ${response.code}")
                    return@withContext Result.failure(Exception("读取排行榜失败: HTTP ${response.code}"))
                }
                // 404 = 文件不存在，使用空数据
            }

            // 2. 追加条目并按 UID 去重（同一 UID 仅保留最高分），保留 schema_version
            val updatedData = when (category) {
                LeaderboardCategory.IMAGE_EASY -> {
                    val list = deduplicateByUid(currentData.image_easy + cleanEntry).take(MAX_ENTRIES_PER_BOARD)
                    currentData.copy(image_easy = list)
                }
                LeaderboardCategory.IMAGE_HARD -> {
                    val list = deduplicateByUid(currentData.image_hard + cleanEntry).take(MAX_ENTRIES_PER_BOARD)
                    currentData.copy(image_hard = list)
                }
                LeaderboardCategory.VOICE_EASY -> {
                    val list = deduplicateByUid(currentData.voice_easy + cleanEntry).take(MAX_ENTRIES_PER_BOARD)
                    currentData.copy(voice_easy = list)
                }
                LeaderboardCategory.VOICE_HARD -> {
                    val list = deduplicateByUid(currentData.voice_hard + cleanEntry).take(MAX_ENTRIES_PER_BOARD)
                    currentData.copy(voice_hard = list)
                }
            }

            // 3. 写回 GitHub
            val newContent = json.encodeToString(updatedData)
            val encodedContent = Base64.encodeToString(newContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val putBody = JSONObject().apply {
                put("message", "Update leaderboard: ${category.displayName} - score=${cleanEntry.score} (fmt=${cleanEntry.formatVersion})")
                put("content", encodedContent)
                sha?.let { put("sha", it) }
            }.toString()

            val putRequest = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .put(putBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "上传成功: category=${category.key}, score=${cleanEntry.score}")
                    return@withContext Result.success(Unit)
                } else {
                    Log.e(TAG, "上传失败: HTTP ${response.code}, category=${category.key}")
                    return@withContext Result.failure(Exception("上传失败: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传异常: uid=${cleanEntry.uid}, category=${category.key}", e)
            return@withContext Result.failure(Exception("上传失败: ${e.message}"))
        }
    }
}
