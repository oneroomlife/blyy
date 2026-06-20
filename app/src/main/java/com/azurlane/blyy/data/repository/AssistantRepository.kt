package com.azurlane.blyy.data.repository

import android.util.Log
import com.azurlane.blyy.data.model.BuildRecordData
import com.azurlane.blyy.data.model.BuildRecordResponse
import com.azurlane.blyy.data.model.UserDetailData
import com.azurlane.blyy.data.model.UserDetailResponse
import com.azurlane.blyy.data.model.resolveServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AssistantRepository"

/**
 * 碧蓝航线小助手数据仓库
 *
 * 封装 B站碧蓝航线 API 的网络请求逻辑，
 * 对应 main.py 中 az / azb 两个指令的 API 调用。
 * 不依赖 Cookie，无需鉴权即可查询公开数据。
 */
@Singleton
class AssistantRepository @Inject constructor(
    private val client: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    companion object {
        private const val BASE_URL = "https://le3-api.game.bilibili.com/x/api/azurlane/get"
        private const val USER_DETAIL_URL = "$BASE_URL/user_detail"
        private const val BUILD_RECORD_URL = "$BASE_URL/build_record"

        // 与 main.py 中 headers 一致
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781"
        private const val REFERER =
            "https://servicewechat.com/wx3ee6dc49667f3444/26/page-frame.html"
    }

    /**
     * 查询玩家详情，对应 main.py 中 az <UID> <服务器> 指令
     */
    suspend fun fetchUserDetail(
        roleId: String,
        serverInput: String
    ): Result<UserDetailData> = withContext(Dispatchers.IO) {
        try {
            val server = resolveServer(serverInput)
                ?: return@withContext Result.failure(Exception("未识别服务器: $serverInput"))

            val url = "$USER_DETAIL_URL?role_id=$roleId&server_id=${server.id}&game_id=182"
            val request = buildRequest(url)

            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("空响应"))

            val data = json.decodeFromString<UserDetailResponse>(body)
            if (data.code != 0) {
                return@withContext Result.failure(Exception("接口提示: ${data.message}"))
            }
            if (data.data == null) {
                return@withContext Result.failure(Exception("数据为空"))
            }
            Result.success(data.data)
        } catch (e: Exception) {
            Log.e(TAG, "fetchUserDetail failed", e)
            Result.failure(Exception("网络错误: ${e.message}"))
        }
    }

    /**
     * 查询建造记录，对应 main.py 中 azb <UID> <服务器> [数量] 指令
     */
    suspend fun fetchBuildRecords(
        roleId: String,
        serverInput: String,
        count: Int = 10
    ): Result<BuildRecordData> = withContext(Dispatchers.IO) {
        try {
            val server = resolveServer(serverInput)
                ?: return@withContext Result.failure(Exception("未识别服务器: $serverInput"))

            val targetCount = count.coerceAtMost(500)
            val pageSize = 50
            val allRecords = mutableListOf<com.azurlane.blyy.data.model.BuildRecordItem>()
            var nickname = ""
            var realUid = ""
            var serverName = ""
            var totalApi = 0

            for (page in 1..(targetCount / pageSize + 2)) {
                if (allRecords.size >= targetCount) break
                val ps = minOf(pageSize, targetCount - allRecords.size)

                val url = "$BUILD_RECORD_URL?role_id=$roleId&server_id=${server.id}&page_num=$page&page_size=$ps"
                val request = buildRequest(url)

                try {
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: break
                    val data = json.decodeFromString<BuildRecordResponse>(body)
                    if (data.code != 0) break
                    val d = data.data ?: break

                    nickname = d.nickname
                    realUid = d.uid
                    serverName = d.serverName
                    totalApi = d.buildRecords.total_count

                    if (d.buildRecords.data.isEmpty()) break
                    allRecords.addAll(d.buildRecords.data)
                } catch (e: Exception) {
                    Log.w(TAG, "Build record page $page failed: ${e.message}")
                    break
                }
            }

            if (allRecords.isEmpty()) {
                return@withContext Result.failure(Exception("未查到建造记录"))
            }

            Result.success(
                BuildRecordData(
                    nickname = nickname,
                    uid = realUid,
                    serverName = serverName,
                    buildRecords = com.azurlane.blyy.data.model.BuildRecords(
                        total_count = totalApi,
                        data = allRecords
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchBuildRecords failed", e)
            Result.failure(Exception("网络错误: ${e.message}"))
        }
    }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Content-Type", "application/json")
            .build()
    }
}
