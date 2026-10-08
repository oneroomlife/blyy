package com.azurlane.blyy.util

import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.util.CacheManager
import com.azurlane.blyy.util.CacheNamespaces
import com.azurlane.blyy.util.SkinVoiceIndex
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * - 请求去重：同一舰名只拉一次，失败后允许重试
 * - 静默失败：拉不到时界面保留「换装N」回退名
 */
@Singleton
class Live2dSkinNameRegistry @Inject constructor(
    private val getVoicesUseCase: GetVoicesUseCase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requested = mutableSetOf<String>()

    /** baseName（中文舰名）→ 换装序列（skinSequence[k-1] = 换装k 的真实皮肤名） */
    private val _skinSequences = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val skinSequences: StateFlow<Map<String, List<String>>> = _skinSequences.asStateFlow()

    /** 请求某舰的皮肤名表（幂等；已在拉取中则忽略） */
    fun request(baseName: String) {
        if (baseName.isBlank()) return
        synchronized(requested) {
            if (baseName in requested) return
            requested += baseName
        }
        scope.launch {
            val lines = runCatching {
                CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, baseName) {
                    val (voices, _, _) = getVoicesUseCase(baseName)
                    voices
                }
            }.onFailure { Log.w(TAG, "拉取 $baseName 皮肤名失败：${it.message}") }
                .getOrDefault(emptyList())
            if (lines.isEmpty()) {
                // 失败允许下次再试
                synchronized(requested) { requested -= baseName }
                return@launch
            }
            val sequence = SkinVoiceIndex.classify(lines.map { it.skinName }).skinSequence
            // update{}（CAS）：本注册表运行在 IO 调度器上，多个舰名并发完成时
            // "读快照→+entry→写回"会互相覆盖，丢失先完成者的升级结果
            _skinSequences.update { it + (baseName to sequence) }
            Log.d(TAG, "皮肤名就绪: $baseName → $sequence")
        }
    }

    /** 换装序号 → 真实皮肤名（换装k = 序列第 k-1 位）；查不到返回 null */
    fun skinNameFor(baseName: String, skinIndex: Int): String? =
        skinSequences.value[baseName]?.getOrNull(skinIndex - 2)

    companion object {
        private const val TAG = "Live2dSkinName"
    }
}
