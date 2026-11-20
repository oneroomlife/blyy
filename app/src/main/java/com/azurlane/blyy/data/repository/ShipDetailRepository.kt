package com.azurlane.blyy.data.repository

import android.util.Log
import com.azurlane.blyy.data.local.ShipCacheDao
import com.azurlane.blyy.data.model.ShipDetail
import com.azurlane.blyy.data.model.SkillRow
import com.azurlane.blyy.util.NetworkHelper
import com.azurlane.blyy.util.wiki.WikiShipDetailParser
import com.azurlane.blyy.util.wiki.WikiUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 舰娘详情（属性/技能/装备/CV/画师/获取方式）仓库。
 *
 * 与 [ShipRepository] 的分工：那边负责"列表与媒体"（名录、语音、立绘、档案），
 * 这边只负责**详情页所需的半结构化数据**，并把它缓存进 `ship_cache.detailJson`——
 * 打开过一次的舰娘，之后断网也能看属性。
 *
 * 失败语义：网络失败 + 无缓存 → 返回空 [ShipDetail]（不抛异常）。
 * 详情页是"锦上添花"的浏览页，不该因为 wiki 抖动就让用户看到崩溃/全屏错误。
 */
@Singleton
class ShipDetailRepository @Inject constructor(
    private val shipCacheDao: ShipCacheDao,
    private val networkHelper: NetworkHelper
) {

    private companion object {
        private const val TAG = "ShipDetailRepository"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 读取详情。
     *
     * @param forceRefresh true 时跳过缓存（详情页手动刷新）
     */
    suspend fun load(
        shipName: String,
        archiveType: String = "DOCK",
        forceRefresh: Boolean = false
    ): ShipDetail = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            readCache(shipName, archiveType)?.takeIf { !it.isEmpty }?.let { return@withContext it }
        }

        val doc = networkHelper.fetchDocument(WikiUrls.page(shipName), maxRetries = 2).getOrNull()
        if (doc != null) {
            val detail = WikiShipDetailParser.parse(doc)
            if (!detail.isEmpty) {
                persist(shipName, archiveType, detail)
                return@withContext detail
            }
        } else {
            Log.w(TAG, "load detail failed for $shipName（将回落缓存）")
        }

        // 网络失败或页面解析为空 → 有缓存（哪怕只解析出一半）就用缓存
        readCache(shipName, archiveType) ?: ShipDetail()
    }

    /**
     * 读取某艘舰娘的技能列表（「识技能」玩法的题库来源）。
     *
     * 与 [load] 的差别只有两点，但两点都必须做：
     * 1. 只回传"能出题"的技能：默认要求 [SkillRow.iconUrl] 非空——图标就是题面，
     *    没有图标的技能抽出来只能显示一个名字，玩家没法作答；
     * 2. **旧缓存自愈**：`SkillRow.iconUrl` 是后加字段，老版本落库的 `detailJson`
     *    里没有它（反序列化后全是空串）。若直接按"没有图标"过滤，这些船会被永久
     *    踢出题库，所以这里检测到"解析出技能但全都没有图标"时强制重抓一次页面。
     *
     * 代价控制：调用方（GuessShipViewModel）用 [com.azurlane.blyy.util.CacheManager]
     * 对结果做 30 分钟内存缓存，因此"这艘船确实没有图标"的判定不会反复打网络。
     *
     * @param requireIcons false 时返回全部技能（详情页/调试用）
     */
    suspend fun loadSkills(
        shipName: String,
        archiveType: String = "DOCK",
        requireIcons: Boolean = true
    ): List<SkillRow> = withContext(Dispatchers.IO) {
        // 先只看缓存：命中就直接返回，避免"缓存里没有图标"时把一次网络请求白跑两遍
        val cachedSkills = usableSkills(readCache(shipName, archiveType)?.skills.orEmpty(), requireIcons)
        if (cachedSkills.isNotEmpty()) return@withContext cachedSkills

        val fresh = load(shipName, archiveType, forceRefresh = true)
        usableSkills(fresh.skills, requireIcons)
    }

    private fun usableSkills(skills: List<SkillRow>, requireIcons: Boolean): List<SkillRow> =
        skills.filter { skill ->
            skill.name.isNotBlank() && (!requireIcons || skill.iconUrl.isNotBlank())
        }

    private suspend fun readCache(shipName: String, archiveType: String): ShipDetail? {
        val raw = runCatching { shipCacheDao.get(shipName, archiveType)?.detailJson }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ShipDetail>(raw) }.getOrNull()
    }

    private suspend fun persist(shipName: String, archiveType: String, detail: ShipDetail) {
        runCatching {
            shipCacheDao.putDetail(
                shipName = shipName,
                archiveType = archiveType,
                json = json.encodeToString(ShipDetail.serializer(), detail)
            )
        }.onFailure { Log.w(TAG, "persist detail failed: ${it.message}") }
    }
}
