package com.azurlane.blyy.util

import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.domain.GetVoicesUseCase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live2D 模型 → 真实皮肤名注册表。
 *
 * 模型目录名只有拼音序号（aierdeliqi_4），「换装4」这类通用名需要 wiki 语音表
 * 才能还原成真实皮肤名（正月的牵手）。本注册表按舰名后台拉取台词（走
 * [CacheManager] SHIP_VOICES 缓存，与互动语音共用一份请求），分类后发布
 * 换装序列，界面订阅后自动把「换装N」升级为真实皮肤名。
 *
 * ## 持久化（避免重复加载）
 *
 * 解析结果（舰名 → 换装序列）持久化到 DataStore，进程启动时异步预热恢复：
 * - 重新进入 Live2D 库/查看器时直接命中内存序列，真实名秒出，不再闪「换装N」
 * - 每条目记录落库时间戳，超过 [ENTRY_FRESH_MS]（7 天）视为陈旧：
 *   界面先显示磁盘结果，后台静默重新拉取自愈（覆盖 wiki 新增皮肤）
 * - 解析结果为派生小数据（纯文本序列），比缓存原始台词轻量得多
 *
 * - 请求去重：同一舰名同会话只拉一次，失败后允许重试
 * - 静默失败：拉不到时界面保留「换装N」回退名（或磁盘上的旧序列）
 */
@Singleton
class Live2dSkinNameRegistry @Inject constructor(
    private val getVoicesUseCase: GetVoicesUseCase,
    private val settings: PlayerSettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已发起请求的舰名（pending 或 done；失败时移除以允许重试） */
    private val requested = mutableSetOf<String>()

    /** 各舰名序列的落库时间戳（毫秒），用于新鲜判断；与 [_skinSequences] 同步更新 */
    private val entryTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** baseName（中文舰名）→ 换装序列（skinSequence[k-1] = 换装k 的真实皮肤名） */
    private val _skinSequences = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val skinSequences: StateFlow<Map<String, List<String>>> = _skinSequences.asStateFlow()

    /** 磁盘预热任务：进程启动后从 DataStore 恢复上次会话已解析的皮肤名序列 */
    private val diskRestoreJob: Job = scope.launch { restoreFromDisk() }

    /**
     * 请求某舰的皮肤名表。
     *
     * 新鲜序列（内存命中且未过 [ENTRY_FRESH_MS]）直接返回，零网络开销；
     * 无缓存或已陈旧时后台拉取（成功后更新内存并持久化）。
     */
    fun request(baseName: String) {
        if (baseName.isBlank()) return
        val now = System.currentTimeMillis()
        val cachedTs = entryTimestamps[baseName]
        if (cachedTs != null && now - cachedTs < ENTRY_FRESH_MS) return
        synchronized(requested) {
            if (baseName in requested) return
            requested += baseName
        }
        scope.launch {
            // 磁盘预热完成后再判断：预热先行到达的请求可能已被磁盘数据满足，避免白跑网络
            diskRestoreJob.join()
            val ts = entryTimestamps[baseName]
            if (ts != null && System.currentTimeMillis() - ts < ENTRY_FRESH_MS) {
                synchronized(requested) { requested -= baseName }
                return@launch
            }
            val lines = runCatching {
                CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, baseName) {
                    val (voices, _, _) = getVoicesUseCase(baseName)
                    voices
                }
            }.onFailure { Log.w(TAG, "拉取 $baseName 皮肤名失败：${it.message}") }
                .getOrDefault(emptyList())
            if (lines.isEmpty()) {
                // 失败允许下次再试；磁盘上有旧序列时界面继续用旧值（不回退）
                synchronized(requested) { requested -= baseName }
                return@launch
            }
            val sequence = SkinVoiceIndex.classify(lines.map { it.skinName }).skinSequence
            entryTimestamps[baseName] = System.currentTimeMillis()
            // update{}（CAS）：本注册表运行在 IO 调度器上，多个舰名并发完成时
            // "读快照→+entry→写回"会互相覆盖，丢失先完成者的升级结果
            _skinSequences.update { it + (baseName to sequence) }
            Log.d(TAG, "皮肤名就绪: $baseName → $sequence")
            persistAll()
        }
    }

    /** 换装序号 → 真实皮肤名（换装k = 序列第 k-1 位）；查不到返回 null */
    fun skinNameFor(baseName: String, skinIndex: Int): String? =
        skinSequences.value[baseName]?.getOrNull(skinIndex - 2)

    // ---------- 持久化 ----------

    /**
     * 从 DataStore 恢复上次会话的解析结果（内存预热，仅网络失败/陈旧时才有后续动作）。
     */
    private suspend fun restoreFromDisk() {
        val restored = runCatching {
            settings.getLive2dSkinNameSequences()?.let { parsePersisted(it) }
        }.onFailure { Log.w(TAG, "恢复皮肤名序列失败：${it.message}") }
            .getOrNull()
            ?: return
        if (restored.isEmpty()) return
        entryTimestamps.putAll(restored.mapValues { it.value.ts })
        _skinSequences.value = restored.mapValues { it.value.seq }
        Log.d(TAG, "皮肤名序列磁盘预热完成：${restored.size} 个舰名")
    }

    /** 将当前全部序列整体持久化（条目少、纯文本，覆盖写开销可忽略） */
    private suspend fun persistAll() {
        val snapshot = _skinSequences.value
        if (snapshot.isEmpty()) return
        val json = JSONObject().apply {
            snapshot.forEach { (base, seq) ->
                put(
                    base,
                    JSONObject().apply {
                        put("ts", entryTimestamps[base] ?: System.currentTimeMillis())
                        put("seq", org.json.JSONArray(seq))
                    }
                )
            }
        }.toString()
        runCatching { settings.setLive2dSkinNameSequences(json) }
            .onFailure { Log.w(TAG, "持久化皮肤名序列失败：${it.message}") }
    }

    private fun parsePersisted(json: String): Map<String, PersistedEntry> {
        val root = JSONObject(json)
        val result = mutableMapOf<String, PersistedEntry>()
        root.keys().forEach { base ->
            val entry = root.optJSONObject(base) ?: return@forEach
            val seq = entry.optJSONArray("seq") ?: return@forEach
            val list = buildList {
                for (i in 0 until seq.length()) add(seq.optString(i))
            }
            if (list.isNotEmpty()) {
                result[base] = PersistedEntry(entry.optLong("ts", 0L), list)
            }
        }
        return result
    }

    /** 单条持久化记录 */
    private data class PersistedEntry(val ts: Long, val seq: List<String>)

    companion object {
        private const val TAG = "Live2dSkinName"

        /**
         * 序列新鲜有效期（7 天）：wiki 皮肤数据极少变动，过期后走后台静默刷新自愈，
         * 不阻塞界面（界面始终优先显示磁盘/内存中的现有序列）。
         */
        private const val ENTRY_FRESH_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
