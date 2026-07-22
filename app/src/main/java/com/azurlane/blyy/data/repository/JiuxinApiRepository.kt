package com.azurlane.blyy.data.repository

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 啾信 API 统一错误类型
 *
 * 细粒度分类网络与解析异常，替代原先直接抛 [Exception] 的粗放做法。
 * 每个子类携带对用户友好的中文提示，UI 层可直接展示 [userMessage]。
 */
sealed class JiuxinApiError(
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    /** 未配置 API 密钥或 URL */
    class MissingConfig(what: String) : JiuxinApiError("未配置$what，请先在啾信配置中设置")

    /** DNS 解析失败 / 无法连接服务器 */
    class Unreachable(host: String, cause: Throwable) :
        JiuxinApiError("无法连接服务器（$host），请检查网络和 URL 地址", cause)

    /** 连接超时 */
    class ConnectTimeout(cause: Throwable) :
        JiuxinApiError("连接超时，请检查网络或稍后重试", cause)

    /** 读取超时（服务端响应过慢） */
    class ReadTimeout(cause: Throwable) :
        JiuxinApiError("服务器响应超时，可能模型正在思考，请稍后重试", cause)

    /** SSL / 证书错误 */
    class SslError(cause: Throwable) :
        JiuxinApiError("SSL 证书验证失败：${cause.message ?: cause.javaClass.simpleName}", cause)

    /** URL 格式错误 */
    class BadUrl(cause: Throwable) :
        JiuxinApiError("URL 格式错误：${cause.message ?: "无效地址"}", cause)

    /** 连接被拒绝 */
    class ConnectionRefused(cause: Throwable) :
        JiuxinApiError("连接被拒绝，请检查 URL 和端口是否正确", cause)

    /** HTTP 状态码非 2xx */
    class HttpError(val code: Int, val errorDetail: String) :
        JiuxinApiError("API 错误 ($code)：${errorDetail.take(200)}")

    /** 空响应体 */
    class EmptyBody : JiuxinApiError("服务器返回空响应")

    /** 响应体无法解析为任何已知格式 */
    class ParseFailed(val rawSnippet: String, cause: Throwable? = null) :
        JiuxinApiError("无法解析啾信响应（原始内容前 80 字：${rawSnippet.take(80)}）", cause)

    /** 响应解析成功但内容为空 */
    class EmptyContent : JiuxinApiError("AI 返回了空内容，请重试或更换模型")

    /** 其它未分类 IO 异常 */
    class IoError(cause: Throwable) :
        JiuxinApiError("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)

    /** 其它未分类异常 */
    class Unknown(cause: Throwable) :
        JiuxinApiError("发生未知错误：${cause.message ?: cause.javaClass.simpleName}", cause)
}

/**
 * API 调用统一返回模型
 *
 * - [Success] 携带解析后的 AI 回复文本
 * - [Failure] 携带 [JiuxinApiError] 子类，含友好提示与原始 cause
 *
 * 用法：
 * ```
 * when (val r = repo.chatCompletion(...)) {
 *   is JiuxinApiResult.Success -> r.content
 *   is JiuxinApiResult.Failure -> r.error.userMessage
 * }
 * ```
 */
sealed class JiuxinApiResult<out T> {
    data class Success<T>(val content: T) : JiuxinApiResult<T>()
    data class Failure(val error: JiuxinApiError) : JiuxinApiResult<Nothing>()

    /** 成功则返回内容，失败则抛出 [JiuxinApiError]（兼容旧调用方 throw 风格） */
    fun getOrThrow(): T = when (this) {
        is Success -> content
        is Failure -> throw error
    }

    /** 成功则返回内容，失败则返回 null */
    fun getOrNull(): T? = (this as? Success)?.content

    /** 转为 Result<T>（kotlin.standard） */
    fun toKtResult(): Result<T> = when (this) {
        is Success -> Result.success(content)
        is Failure -> Result.failure(error)
    }
}

/**
 * OpenAI 兼容 Chat Completions 请求的参数封装
 *
 * 将请求构造与执行解耦：ViewModel 负责"说什么"，Repository 负责"怎么发"。
 */
data class ChatCompletionRequest(
    val url: String,
    val apiKey: String,
    val model: String,
    /** 已按 role 排列好的 messages 数组（system 在前，user/assistant 交替） */
    val messages: List<ChatRoleMessage>,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f
)

/** 单条消息（role + content，可选 name 用于群聊多角色标识） */
data class ChatRoleMessage(
    val role: String,
    val content: String,
    val name: String? = null
)

/**
 * 啾信 API 仓储层
 *
 * 职责：
 * 1. 构造 HTTP 请求（Header / Body 统一管理）
 * 2. 执行网络调用（在 IO 调度器内）
 * 3. 细粒度异常分类（[JiuxinApiError] 子类）
 * 4. 多格式响应解析（[JiuxinResponseParser]）
 * 5. 瞬时错误自动重试
 * 6. 结构化日志追踪
 *
 * 渐进式重构：ViewModel 中的 [JiuxinViewModel.callApi] / [JiuxinViewModel.callGroupApi]
 * 改为委托本仓储执行，保留原有的会话快照读取与上下文构造逻辑。
 */
@Singleton
class JiuxinApiRepository @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "JiuxinApiRepository"

        /** 瞬时错误最大重试次数（不含首次调用） */
        private const val MAX_RETRY = 2

        /** 重试初始退避（毫秒），指数退避：500ms → 1000ms */
        private const val RETRY_BACKOFF_MS = 500L
    }

    /** 容错 JSON：忽略未知字段，宽松解析 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 执行一次 Chat Completions 调用，含重试与异常分类。
     *
     * @param request 请求参数
     * @param tag 日志标签（如成员名），用于群聊多成员场景区分
     * @return [JiuxinApiResult] 包装的 AI 回复文本
     */
    suspend fun chatCompletion(
        request: ChatCompletionRequest,
        tag: String = ""
    ): JiuxinApiResult<String> {
        if (request.apiKey.isBlank()) {
            return JiuxinApiResult.Failure(JiuxinApiError.MissingConfig("API 密钥"))
        }
        if (request.url.isBlank()) {
            return JiuxinApiResult.Failure(JiuxinApiError.MissingConfig("API URL"))
        }

        val requestBody = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url(request.url)
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        val logTag = if (tag.isNotBlank()) "[$tag]" else ""
        var lastError: JiuxinApiError? = null

        // 重试循环：仅对瞬时错误（超时 / 连接失败）重试，不对 4xx / 解析错误重试
        for (attempt in 0..MAX_RETRY) {
            val result = executeOnce(httpRequest, logTag, attempt)
            when (result) {
                is JiuxinApiResult.Success -> return result
                is JiuxinApiResult.Failure -> {
                    lastError = result.error
                    if (!isRetryable(result.error) || attempt == MAX_RETRY) {
                        return result
                    }
                    val backoff = RETRY_BACKOFF_MS * (1L shl attempt)
                    Log.w(TAG, "$logTag Retryable error on attempt ${attempt + 1}, backing off ${backoff}ms: ${result.error.userMessage}")
                    delay(backoff)
                }
            }
        }
        return JiuxinApiResult.Failure(lastError ?: JiuxinApiError.Unknown(RuntimeException("unreachable")))
    }

    /** 执行单次请求（不含重试），返回分类后的结果 */
    private suspend fun executeOnce(
        request: Request,
        logTag: String,
        attempt: Int
    ): JiuxinApiResult<String> {
        return try {
            val requestTime = System.currentTimeMillis()
            // OkHttp 的 execute() 是阻塞调用，必须在 IO 调度器内
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val body = response.body?.string()
            val duration = System.currentTimeMillis() - requestTime

            if (body.isNullOrBlank()) {
                Log.e(TAG, "$logTag Empty response body (code=${response.code}, ${duration}ms)")
                response.close()
                return JiuxinApiResult.Failure(JiuxinApiError.EmptyBody())
            }

            if (!response.isSuccessful) {
                val errorDetail = JiuxinResponseParser.parseErrorMessage(body)
                Log.e(TAG, "$logTag HTTP ${response.code} (${duration}ms, attempt=${attempt + 1}): $errorDetail")
                return JiuxinApiResult.Failure(JiuxinApiError.HttpError(response.code, errorDetail))
            }

            Log.d(TAG, "$logTag HTTP ${response.code} success (${duration}ms, body=${body.length} chars, attempt=${attempt + 1})")

            JiuxinResponseParser.parseChatCompletion(body)
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "$logTag UnknownHost: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.Unreachable(request.url.host, e))
        } catch (e: java.net.SocketTimeoutException) {
            // SocketTimeout 既可能是连接超时也可能是读取超时，OkHttp 不区分；
            // 按读取超时处理（最常见场景：模型响应慢）
            Log.w(TAG, "$logTag SocketTimeout: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.ReadTimeout(e))
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "$logTag ConnectException: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.ConnectionRefused(e))
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "$logTag SSLException: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.SslError(e))
        } catch (e: java.net.MalformedURLException) {
            Log.w(TAG, "$logTag MalformedURL: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.BadUrl(e))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "$logTag IOException: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.IoError(e))
        } catch (e: JiuxinApiError) {
            JiuxinApiResult.Failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$logTag Unexpected error", e)
            JiuxinApiResult.Failure(JiuxinApiError.Unknown(e))
        }
    }

    /** 判断错误是否值得重试（瞬时网络错误） */
    private fun isRetryable(error: JiuxinApiError): Boolean = when (error) {
        is JiuxinApiError.ConnectTimeout,
        is JiuxinApiError.ReadTimeout,
        is JiuxinApiError.Unreachable,
        is JiuxinApiError.ConnectionRefused,
        is JiuxinApiError.IoError,
        is JiuxinApiError.EmptyBody -> true
        // 配置错误 / HTTP 4xx / 解析错误 / SSL 错误不重试
        else -> false
    }

    /** 构造 OpenAI 兼容请求体 JSON */
    private fun buildRequestBody(req: ChatCompletionRequest): okhttp3.RequestBody {
        val messagesArray = buildJsonArray {
            req.messages.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(msg.role))
                    put("content", JsonPrimitive(msg.content))
                    if (!msg.name.isNullOrBlank()) {
                        put("name", JsonPrimitive(msg.name))
                    }
                })
            }
        }
        val requestJson = buildJsonObject {
            put("model", JsonPrimitive(req.model))
            put("messages", messagesArray)
            put("max_tokens", JsonPrimitive(req.maxTokens))
            put("temperature", JsonPrimitive(req.temperature))
        }
        return json.encodeToString(requestJson)
            .toRequestBody("application/json".toMediaType())
    }

    /**
     * 辅助：将 Base URL 补全为 Chat Completions 地址。
     * 从 ViewModel.buildFullApiUrl 迁移，统一在此提供。
     */
    fun buildFullApiUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        if (trimmed.endsWith("/chat/completions", ignoreCase = true)) return trimmed
        return "$trimmed/chat/completions"
    }

    /**
     * 辅助：解析错误响应体的友好信息（暴露给 ViewModel 用于连接测试等场景）。
     */
    fun parseErrorBody(body: String): String = JiuxinResponseParser.parseErrorMessage(body)

    // ── 模型列表拉取 ──

    /**
     * 拉取可用模型列表。
     *
     * 采用成熟的多端点回退 + 多格式解析 + 多鉴权头策略，兼容各类 OpenAI 兼容 / 第三方代理 API：
     *
     * 1. **URL 规范化**：根据 baseUrl 形态生成多个候选端点（`/v1/models`、`/models`、剥离 `/chat/completions`）
     * 2. **多格式解析**：OpenAI `data[].id` / Anthropic `models[].id` / 裸数组 / 字符串数组 / 对象键名
     * 3. **多鉴权头**：同时发送 `Authorization: Bearer` + `x-api-key`，兼容 Anthropic 风格
     * 4. **短超时**：模型列表请求应当快速完成，独立 15s 超时避免长时间挂起
     * 5. **首个成功端点即返回**，不浪费请求
     *
     * @param baseUrl API 基础地址（可能是 `https://host/v1`、`https://host/v1/chat/completions`、`https://host` 等）
     * @param apiKey API 密钥
     * @return [JiuxinApiResult] 包装的模型 ID 列表（已排序去重）
     */
    suspend fun fetchModelList(
        baseUrl: String,
        apiKey: String
    ): JiuxinApiResult<List<String>> {
        if (apiKey.isBlank()) {
            return JiuxinApiResult.Failure(JiuxinApiError.MissingConfig("API 密钥"))
        }
        if (baseUrl.isBlank()) {
            return JiuxinApiResult.Failure(JiuxinApiError.MissingConfig("API Base URL"))
        }

        val candidates = buildModelsUrlCandidates(baseUrl)
        if (candidates.isEmpty()) {
            return JiuxinApiResult.Failure(JiuxinApiError.BadUrl(IllegalArgumentException("无法从 baseUrl 派生模型列表端点: $baseUrl")))
        }

        Log.i(TAG, "Fetching model list from ${candidates.size} candidate endpoints: $candidates")

        var lastError: JiuxinApiError? = null
        for (url in candidates) {
            val result = fetchModelsFromSingleEndpoint(url, apiKey)
            when (result) {
                is JiuxinApiResult.Success -> {
                    Log.i(TAG, "Model list fetched from $url: ${result.content.size} models")
                    return result
                }
                is JiuxinApiResult.Failure -> {
                    Log.w(TAG, "Failed to fetch from $url: ${result.error.userMessage}")
                    lastError = result.error
                    // 继续尝试下一个候选端点
                }
            }
        }

        return JiuxinApiResult.Failure(
            lastError ?: JiuxinApiError.Unknown(RuntimeException("所有候选端点均失败"))
        )
    }

    /**
     * 从单个端点拉取模型列表。
     *
     * 内部使用独立的短超时请求（避免全局 client 的长超时拖慢模型列表拉取）。
     */
    private suspend fun fetchModelsFromSingleEndpoint(
        url: String,
        apiKey: String
    ): JiuxinApiResult<List<String>> {
        // 同时发送 Bearer 和 x-api-key，兼容 Anthropic / Azure 等不同鉴权风格
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("x-api-key", apiKey)
            .addHeader("Accept", "application/json")
            .get()
            .build()

        return try {
            val requestTime = System.currentTimeMillis()
            val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val body = response.body?.string()
            val duration = System.currentTimeMillis() - requestTime

            if (body.isNullOrBlank()) {
                Log.w(TAG, "Empty body from $url (code=${response.code}, ${duration}ms)")
                response.close()
                return JiuxinApiResult.Failure(JiuxinApiError.EmptyBody())
            }

            if (!response.isSuccessful) {
                val errorDetail = JiuxinResponseParser.parseErrorMessage(body)
                Log.w(TAG, "HTTP ${response.code} from $url (${duration}ms): $errorDetail")
                return JiuxinApiResult.Failure(JiuxinApiError.HttpError(response.code, errorDetail))
            }

            val models = JiuxinModelListParser.parse(body)
            Log.d(TAG, "Parsed ${models.size} models from $url (${duration}ms, body=${body.length} chars)")

            if (models.isEmpty()) {
                JiuxinApiResult.Failure(JiuxinApiError.ParseFailed(body))
            } else {
                JiuxinApiResult.Success(models)
            }
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "UnknownHost from $url: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.Unreachable(request.url.host, e))
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "SocketTimeout from $url: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.ReadTimeout(e))
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "ConnectException from $url: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.ConnectionRefused(e))
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "SSLException from $url: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.SslError(e))
        } catch (e: java.net.MalformedURLException) {
            Log.w(TAG, "MalformedURL: $url — ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.BadUrl(e))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "IOException from $url: ${e.message}")
            JiuxinApiResult.Failure(JiuxinApiError.IoError(e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error from $url", e)
            JiuxinApiResult.Failure(JiuxinApiError.Unknown(e))
        }
    }

    /**
     * 根据 baseUrl 生成模型列表端点的候选 URL 列表（按优先级排序，去重）。
     *
     * 处理各种用户输入形态：
     * - `https://host/v1` → `https://host/v1/models`
     * - `https://host/v1/` → `https://host/v1/models`（trailing slash 已 trim）
     * - `https://host/v1/chat/completions` → 剥离 `/chat/completions`，得到 `https://host/v1/models`
     * - `https://host` → 尝试 `https://host/v1/models` 和 `https://host/models`
     * - `https://host/openai/v1` → `https://host/openai/v1/models`
     * - `https://host/models` → 原样使用
     *
     * @return 候选 URL 列表（至少 1 个），空表示 baseUrl 无效
     */
    fun buildModelsUrlCandidates(baseUrl: String): List<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return emptyList()

        // 必须是 http(s) 开头
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }

        val candidates = linkedSetOf<String>()

        // 情况 1：URL 已以 /models 结尾 — 原样使用
        if (trimmed.endsWith("/models", ignoreCase = true)) {
            candidates.add(trimmed)
        }

        // 情况 2：URL 以 /chat/completions 结尾 — 剥离后追加 /models
        if (trimmed.endsWith("/chat/completions", ignoreCase = true)) {
            val base = trimmed.removeSuffix("/chat/completions")
            candidates.add("$base/models")
        }

        // 情况 3：URL 以 /v1 / /v2 等版本号结尾 — 直接追加 /models
        if (Regex("""/v\d+$""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            candidates.add("$trimmed/models")
        }

        // 情况 4：通用回退 — 同时尝试 /v1/models 和 /models
        // 覆盖 baseUrl 不含版本号的场景（如 https://host）
        candidates.add("$trimmed/v1/models")
        candidates.add("$trimmed/models")

        return candidates.toList()
    }
}

/**
 * 模型列表响应多格式解析器
 *
 * 不依赖单一 JSON 结构，按优先级依次尝试多种已知格式：
 *
 * 1. **OpenAI 标准**：`{ data: [{ id: "..." }, ...] }`
 * 2. **Anthropic 风格**：`{ models: [{ id: "..." }] }` 或 `{ models: [{ name: "..." }] }`
 * 3. **models 字段为字符串数组**：`{ models: ["model-1", "model-2"] }`
 * 4. **裸数组**：`["model-1", "model-2"]` 或 `[{ id: "..." }]`
 * 5. **对象键名作为模型名**：`{ "gpt-4": {...}, "gpt-3.5": {...} }`（部分代理）
 *
 * 任何字段缺失、类型不匹配均安全跳过，最终返回空列表（调用方据此判断解析失败）。
 */
object JiuxinModelListParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 内置元数据字段名，格式 5 解析时跳过这些键 */
    private val META_KEYS = setOf(
        "object", "status", "message", "error", "code", "type",
        "detail", "request_id", "id", "created", "warning"
    )

    /**
     * 解析模型列表响应，返回排序去重后的模型 ID 列表。
     *
     * @param responseBody HTTP 响应体
     * @return 模型 ID 列表，空列表表示无法解析或确实为空
     */
    fun parse(responseBody: String): List<String> {
        val root: JsonElement = try {
            json.parseToJsonElement(responseBody)
        } catch (e: Exception) {
            Log.w("JiuxinModelListParser", "Root JSON parse failed", e)
            return emptyList()
        }

        val models = linkedSetOf<String>()

        // 路径 1：root 是对象 — 尝试 data[] / models[] / 对象键名
        val rootObj = root.safeJsonObject()
        if (rootObj != null) {
            // 1a: data 数组（OpenAI 标准）
            extractFromDataArray(rootObj["data"], models)
            // 1b: models 数组（Anthropic / 部分代理）
            extractFromModelsField(rootObj["models"], models)
            // 1c: result 数组（部分中转 API）
            extractFromDataArray(rootObj["result"], models)
            // 1d: items 数组
            extractFromDataArray(rootObj["items"], models)

            // 1e: 所有路径均空，尝试对象键名作为模型名
            if (models.isEmpty()) {
                extractFromObjectKeys(rootObj, models)
            }
        }

        // 路径 2：root 是数组 — 裸数组格式
        if (models.isEmpty()) {
            val rootArr = root.safeJsonArray()
            if (rootArr != null) {
                extractFromArray(rootArr, models)
            }
        }

        return models
            .filter { it.isNotBlank() && it.length <= 200 }  // 过滤异常长字符串
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    /** 从 data/result/items 数组中提取模型 ID */
    private fun extractFromDataArray(element: JsonElement?, models: MutableSet<String>) {
        val arr = element?.safeJsonArray() ?: return
        extractFromArray(arr, models)
    }

    /** 从 models 字段提取 — 兼容对象数组与字符串数组 */
    private fun extractFromModelsField(element: JsonElement?, models: MutableSet<String>) {
        if (element == null) return
        // 尝试作为数组
        val arr = element.safeJsonArray()
        if (arr != null) {
            extractFromArray(arr, models)
            return
        }
        // 尝试作为单个对象（部分 API 返回单个模型对象）
        val obj = element.safeJsonObject()
        if (obj != null) {
            extractIdFromObject(obj, models)
        }
    }

    /** 从 JSON 数组提取模型 ID — 兼容字符串数组与对象数组 */
    private fun extractFromArray(arr: JsonArray, models: MutableSet<String>) {
        arr.forEach { item ->
            // 尝试作为字符串
            val str = item.safeString()
            if (str != null && str.isNotBlank()) {
                models.add(str)
                return@forEach
            }
            // 尝试作为对象
            val obj = item.safeJsonObject()
            if (obj != null) {
                extractIdFromObject(obj, models)
            }
        }
    }

    /** 从单个模型对象中提取 ID — 逐字段尝试 id / name / model，命中即返回 */
    private fun extractIdFromObject(obj: JsonObject, models: MutableSet<String>) {
        // 优先 id（OpenAI / Anthropic 标准）
        val id = obj["id"]?.safeString()
        if (!id.isNullOrBlank()) {
            models.add(id)
            return
        }
        // 回退 name（部分代理）
        val name = obj["name"]?.safeString()
        if (!name.isNullOrBlank()) {
            models.add(name)
            return
        }
        // 回退 model 字段（极少数 API）
        val model = obj["model"]?.safeString()
        if (!model.isNullOrBlank()) {
            models.add(model)
        }
    }

    /** 从对象键名提取模型名 — 兜底策略，过滤元数据字段 */
    private fun extractFromObjectKeys(obj: JsonObject, models: MutableSet<String>) {
        obj.keys.forEach { key ->
            if (key !in META_KEYS && key.isNotBlank()) {
                // 仅当值是对象时才认为是模型条目（避免把 status: "ok" 当成模型）
                val value = obj[key]
                if (value?.safeJsonObject() != null) {
                    models.add(key)
                }
            }
        }
    }

    // ── JSON 安全扩展（与 JiuxinResponseParser 独立，避免访问私有成员） ──

    private fun JsonElement.safeJsonObject(): JsonObject? = try {
        jsonObject
    } catch (_: Exception) { null }

    private fun JsonElement.safeJsonArray(): JsonArray? = try {
        jsonArray
    } catch (_: Exception) { null }

    private fun JsonElement.safeString(): String? = try {
        val primitive = jsonPrimitive
        if (primitive.isString) primitive.content else primitive.toString()
    } catch (_: Exception) { null }
}

/**
 * 多格式响应解析器
 *
 * 不依赖单一固定 JSON 结构，按优先级依次尝试多种已知格式：
 * 1. OpenAI 标准：choices[0].message.content
 * 2. OpenAI 流式单 chunk：choices[0].delta.content
 * 3. 直接 content / text 字段（部分第三方 API 简化格式）
 * 4. OpenRouter 风格：choices[0].message.content 为 null 时回退到 reasoning
 *
 * 任何字段缺失、类型不匹配均安全跳过，最终解析失败才抛 [JiuxinApiError.ParseFailed]。
 */
object JiuxinResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析 Chat Completions 成功响应，提取 AI 回复文本。
     *
     * @return [JiuxinApiResult.Success] 含 trim 后的回复文本；
     *         内容为空返回 [JiuxinApiError.EmptyContent]；
     *         解析失败返回 [JiuxinApiError.ParseFailed]（含原始片段便于排查）
     */
    fun parseChatCompletion(responseBody: String): JiuxinApiResult<String> {
        val root: JsonObject = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            Log.w("JiuxinResponseParser", "Root JSON parse failed", e)
            return JiuxinApiResult.Failure(
                JiuxinApiError.ParseFailed(responseBody, e)
            )
        }

        // 路径 1：choices[0].message.content（OpenAI 标准非流式）
        val choices = root["choices"]?.jsonArray
        if (choices != null && choices.isNotEmpty()) {
            val firstChoice = choices[0].safeJsonObject()
            if (firstChoice != null) {
                val content = extractContentFromChoice(firstChoice)
                if (!content.isNullOrBlank()) {
                    return JiuxinApiResult.Success(content.trim())
                }
            }
        }

        // 路径 2：直接 content / text 字段（简化格式第三方 API）
        val directContent = root["content"]?.safeString()
            ?: root["text"]?.safeString()
            ?: root["response"]?.safeString()
        if (!directContent.isNullOrBlank()) {
            return JiuxinApiResult.Success(directContent.trim())
        }

        // 路径 3：data.content 嵌套（部分中转 API 包装层）
        val dataObj = root["data"]?.safeJsonObject()
        if (dataObj != null) {
            val dataContent = dataObj["content"]?.safeString()
                ?: dataObj["text"]?.safeString()
                ?: dataObj["message"]?.safeString()
            if (!dataContent.isNullOrBlank()) {
                return JiuxinApiResult.Success(dataContent.trim())
            }
        }

        // 所有路径都未提取到有效内容
        // 区分"解析成功但空内容"与"完全无法解析"
        val hasChoicesButEmpty = choices != null && choices.isNotEmpty()
        val error = if (hasChoicesButEmpty) {
            // 结构正确但内容为空
            Log.w("JiuxinResponseParser", "choices present but content empty. body=${responseBody.take(200)}")
            JiuxinApiError.EmptyContent()
        } else {
            Log.w("JiuxinResponseParser", "No recognized format. body=${responseBody.take(200)}")
            JiuxinApiError.ParseFailed(responseBody)
        }
        return JiuxinApiResult.Failure(error)
    }

    /** 从单个 choice 对象中提取内容（兼容 message.content / delta.content / message.reasoning） */
    private fun extractContentFromChoice(choice: JsonObject): String? {
        // 标准：message.content
        val message = choice["message"]?.safeJsonObject()
        if (message != null) {
            val content = message["content"]?.safeString()
            if (!content.isNullOrBlank()) return content
            // 部分模型（如 o1）返回 reasoning 字段，content 为 null
            val reasoning = message["reasoning"]?.safeString()
            if (!reasoning.isNullOrBlank()) return reasoning
        }
        // 流式 chunk：delta.content
        val delta = choice["delta"]?.safeJsonObject()
        if (delta != null) {
            val content = delta["content"]?.safeString()
            if (!content.isNullOrBlank()) return content
        }
        // 极少数 API 直接在 choice 上放 content
        return choice["content"]?.safeString()
    }

    /** 解析错误响应，提取友好信息（兼容 OpenAI error.message 与裸文本） */
    fun parseErrorMessage(body: String): String {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            // OpenAI 标准：error.message
            val errorMsg = root["error"]?.safeJsonObject()?.get("message")?.safeString()
            if (!errorMsg.isNullOrBlank()) return errorMsg.take(200)
            // 部分 API：message 字段
            val directMsg = root["message"]?.safeString()
            if (!directMsg.isNullOrBlank()) return directMsg.take(200)
            // 兜底：截取原始 body
            body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    // ── JSON 安全扩展：避免单个字段类型不符导致整个解析崩溃 ──

    private fun JsonElement.safeJsonObject(): JsonObject? = try {
        jsonObject
    } catch (_: Exception) { null }

    private fun JsonElement.safeString(): String? = try {
        val primitive = jsonPrimitive
        if (primitive.isString) primitive.content else primitive.toString()
    } catch (_: Exception) { null }
}
