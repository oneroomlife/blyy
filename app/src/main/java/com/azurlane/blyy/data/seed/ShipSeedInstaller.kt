package com.azurlane.blyy.data.seed

import android.content.Context
import android.util.Log
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.viewmodel.ArchiveType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首启名录种子安装器（拆掉「冷启动必须联网」的另一半）。
 *
 * 问题：`ships` 表完全由 wiki 抓取填充，**首装 + 无网**时船坞是一片空白——
 * 连舰名、头像（本地 assets 里明明有 1100+ 张）、生日都看不到。
 *
 * 方案：打包一份最小名录 `assets/blhx_seed_ships.json`（1004 条，只含舰名与舰种，
 * 约 32 KB，由 wiki 抓取快照离线生成），首启且表为空时写入。
 * - 头像不需要种子：`LocalAvatarResolver` 会按舰名拼音直接命中 assets；
 * - 生日不需要种子：`blhx_birthdays.json` 已有全量名单，启动后会回填；
 * - 稀有度/阵营/档案字段留空，**首次联网刷新时由 wiki 数据覆盖**
 *   （`ShipDao.upsertShips` 以网络数据为准，同时保留收藏与生日）。
 *
 * 这样最坏情况（首装无网）用户看到的是：1004 个可搜索、可点、有本地头像、
 * 有生日的舰娘卡片，舰种筛选可用；联网后自动补齐稀有度/阵营。
 */
@Singleton
class ShipSeedInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shipDao: ShipDao
) {

    companion object {
        private const val TAG = "ShipSeedInstaller"
        private const val ASSET_NAME = "blhx_seed_ships.json"
        private const val BIRTHDAY_ASSET = "blhx_birthdays.json"
    }

    @Serializable
    private data class SeedShip(val n: String, val t: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 表为空时导入种子。
     *
     * 生日一并写入：`blhx_birthdays.json` 本来就在 assets 里（1004 条离线名单），
     * 但此前只由"联网刷新后"的 `seedDockBirthdays` 回填——首装无网时生日日历是空的。
     * 这里在插入时直接带上，首启离线也能用日历与生日组件。
     *
     * @return 写入条数（0 = 已有数据/资源缺失/解析失败，均不抛异常）
     */
    suspend fun seedIfEmpty(): Int = withContext(Dispatchers.IO) {
        runCatching {
            if (shipDao.countByArchiveType(ArchiveType.DOCK.name) > 0) return@runCatching 0
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val seeds = json.decodeFromString<List<SeedShip>>(text)
                .filter { it.n.isNotBlank() }
            if (seeds.isEmpty()) return@runCatching 0

            val birthdays = loadBirthdays()

            val ships = seeds.map { seed ->
                Ship(
                    name = seed.n,
                    // 空 avatarUrl → 头像解析链会回落到本地 assets（pinyin 命名）
                    avatarUrl = "",
                    borderUrl = null,
                    // 详情页/Wiki 跳转用舰名即可（buildWikiUrl 会做归一化与编码）
                    link = seed.n,
                    type = seed.t,
                    rarity = "",
                    faction = "",
                    extra = "",
                    archiveType = ArchiveType.DOCK.name,
                    isFavorite = false,
                    filterData = "",
                    birthday = birthdays[seed.n].orEmpty()
                )
            }
            // 直接 insertAll（不走 upsertShips：后者会读库保留状态，此处表本来就是空的）
            shipDao.insertAll(ships)
            Log.i(TAG, "seeded ${ships.size} ships (birthdays=${ships.count { it.birthday.isNotBlank() }})")
            ships.size
        }.onFailure { Log.w(TAG, "seed failed: ${it.message}") }.getOrDefault(0)
    }

    /** 读取打包的生日名单（舰名 → "MM-DD"）；缺失/损坏返回空表，不影响种子主体 */
    private fun loadBirthdays(): Map<String, String> = runCatching {
        val text = context.assets.open(BIRTHDAY_ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString<Map<String, String>>(text)
    }.onFailure { Log.w(TAG, "birthday asset unreadable: ${it.message}") }.getOrDefault(emptyMap())
}
