package com.azurlane.blyy.domain

import com.azurlane.blyy.data.model.BuildRecordItem

/**
 * 建造查询「属性筛选」——纯逻辑层
 *
 * 对应建造记录弹窗（图2）的两组属性：
 * - 建造类型：按 [BuildRecordItem.taskName]（建造池名称）做关键词匹配
 * - 稀有度：按本地舰船图鉴（ships 表）把 [BuildRecordItem.roleName] 解析为稀有度后匹配
 *
 * 稀有度不在接口返回里，只能靠本地图鉴反查；图鉴未收录的舰船记为「未知」，
 * 只有在稀有度筛选激活时才被排除，并把条数透出给 UI 提示用户，避免"记录凭空消失"。
 */

// ── 建造类型 ──

/**
 * 建造类型筛选项 — 顺序与图2的按钮排布一致
 *
 * @param label 展示文案
 * @param keywords 匹配 [BuildRecordItem.taskName] 的关键词；
 *   同时兼容「轻型建造」「轻型舰建造」等不同措辞
 */
enum class BuildPoolFilter(
    val label: String,
    private val keywords: List<String> = emptyList()
) {
    ALL("全部"),
    LIMITED("限时建造", listOf("限时")),
    LIGHT("轻型建造", listOf("轻型")),
    HEAVY("重型建造", listOf("重型")),
    SPECIAL("特型建造", listOf("特型")),
    WISH("祈愿建造", listOf("祈愿")),
    ROOKIE("新手限定建造", listOf("新手"));

    /** 建造池名称是否命中本筛选项；[ALL] 恒命中 */
    fun matches(taskName: String): Boolean {
        if (this == ALL) return true
        val name = taskName.trim()
        if (name.isEmpty()) return false
        return keywords.any { name.contains(it) }
    }
}

// ── 稀有度 ──

/**
 * 稀有度筛选项 — 顺序与图2一致
 *
 * 取值与 ships 表 `rarity` 字段、[com.azurlane.blyy.ui.theme.AppColors.Rarity] 完全同源。
 */
enum class BuildRarityFilter(val label: String) {
    ALL("全部"),
    ULTRA_RARE("海上传奇"),
    SUPER_RARE("超稀有"),
    ELITE("精锐"),
    RARE("稀有"),
    COMMON("普通");

    /** 稀有度是否命中本筛选项（null = 图鉴未收录）；[ALL] 恒命中 */
    fun matches(rarity: String?): Boolean {
        if (this == ALL) return true
        if (rarity.isNullOrBlank()) return false
        return rarity.trim() == label
    }
}

// ── 筛选结果 ──

/** 当前生效的筛选组合 */
data class BuildFilterSelection(
    val pool: BuildPoolFilter = BuildPoolFilter.ALL,
    val rarity: BuildRarityFilter = BuildRarityFilter.ALL
) {
    /** 生效的筛选维度数（0 表示「全部」） */
    val activeCount: Int
        get() = (if (pool != BuildPoolFilter.ALL) 1 else 0) +
            (if (rarity != BuildRarityFilter.ALL) 1 else 0)

    val isActive: Boolean get() = activeCount > 0

    /** 筛选条摘要，用于筛选入口按钮的副标题 */
    fun summary(): String = when (activeCount) {
        0 -> "全部"
        1 -> if (pool != BuildPoolFilter.ALL) pool.label else rarity.label
        else -> "${pool.label} · ${rarity.label}"
    }
}

/**
 * 建造记录展示行 — 附带本地图鉴解析出的稀有度
 *
 * @param sourceIndex 在原始记录中的下标，作为 LazyColumn 的稳定 key
 * @param rarity 本地图鉴稀有度，null = 图鉴未收录
 */
data class BuildRecordRow(
    val roleName: String,
    val taskName: String,
    val rarity: String?,
    val sourceIndex: Int
)

/**
 * 筛选结果
 *
 * @param rows 通过筛选、可直接渲染的记录
 * @param total 筛选前总条数
 * @param unknownRarity 命中建造类型、但本地图鉴查不到稀有度的条数
 */
data class BuildFilterResult(
    val rows: List<BuildRecordRow> = emptyList(),
    val total: Int = 0,
    val unknownRarity: Int = 0
) {
    /** 是否发生了实际过滤（有生效筛选且条数减少） */
    val isNarrowed: Boolean get() = rows.size < total
}

/**
 * 按 [selection] 过滤建造记录
 *
 * 处理顺序：先按建造类型过筛，再用本地图鉴稀有度过筛。
 * [rarityByName] 为 `name → rarity` 映射（由 ships 表投影而来）。
 */
fun filterBuildRecords(
    records: List<BuildRecordItem>,
    selection: BuildFilterSelection,
    rarityByName: Map<String, String>
): BuildFilterResult {
    if (records.isEmpty()) return BuildFilterResult()

    val rows = ArrayList<BuildRecordRow>(records.size)
    var unknownRarity = 0

    records.forEachIndexed { index, item ->
        if (!selection.pool.matches(item.taskName)) return@forEachIndexed

        val rarity = rarityByName[normalizeShipName(item.roleName)]
        if (rarity.isNullOrBlank()) unknownRarity++

        if (!selection.rarity.matches(rarity)) return@forEachIndexed

        rows += BuildRecordRow(
            roleName = item.roleName,
            taskName = item.taskName,
            rarity = rarity,
            sourceIndex = index
        )
    }

    return BuildFilterResult(
        rows = rows,
        total = records.size,
        unknownRarity = unknownRarity
    )
}

/**
 * 舰名归一到图鉴主键
 *
 * 接口返回的舰名带前后空白/全角空格的情况并不罕见，图鉴主键是去空白的原名。
 */
internal fun normalizeShipName(name: String): String =
    name.trim().replace('\u3000', ' ').trim()
