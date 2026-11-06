package com.azurlane.blyy.util.gameimport

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/**
 * 资源导入页可选的排序字段。
 *
 * @property label 芯片文案
 * @property defaultAscending 首次选中该字段时采用的方向：
 *  名称正序（A→Z，符合「按名字找东西」的直觉）、
 *  时间倒序（最新导入/最近改动的排最前，符合「刚弄的东西在哪」的直觉）。
 */
enum class GameSortField(
    val label: String,
    val defaultAscending: Boolean
) {
    NAME("名称", true),
    TIME("时间", false)
}

/**
 * 排序方案：字段 + 方向。
 *
 * 「资源包」与「已导入」两个分区共用同一份方案 —— 用户在两个 Tab 之间来回切时，
 * 不应该每次都重新选一遍；方案本身还会落盘（见 `PlayerSettingsDataStore.gameImportSort`），
 * 下次进入页面仍是上次的选择。
 */
data class GameSortOrder(
    val field: GameSortField = GameSortField.NAME,
    val ascending: Boolean = true
) {

    /** 方向文案（按钮用） */
    val directionLabel: String get() = if (ascending) "升序" else "降序"

    /**
     * 点击字段芯片后的新方案。
     *
     * - 点的是**另一个**字段：切过去并套用该字段的默认方向；
     * - 点的是**当前**字段：翻转方向（芯片上的箭头随之变化，等价于点右侧方向按钮）。
     *
     * 两种入口都能改方向，不靠用户猜「再点一次会发生什么」。
     */
    fun selecting(field: GameSortField): GameSortOrder =
        if (field == this.field) copy(ascending = !ascending)
        else GameSortOrder(field, field.defaultAscending)

    /** 翻转方向 */
    fun toggled(): GameSortOrder = copy(ascending = !ascending)

    /** 持久化编码：`name_asc` / `time_desc` */
    val storageKey: String
        // this.field 必须带接收者：属性访问器里的裸 field 是「幕后字段」关键字
        get() = "${this.field.name.lowercase(Locale.ROOT)}_${if (ascending) "asc" else "desc"}"

    companion object {
        /** 默认方案：按名称正序 */
        val Default = GameSortOrder()

        /**
         * 容错解析：脏数据 / 旧版本残留 / 手工改过的配置文件一律返回 null，
         * 调用方保留当前方案即可（排序不属于必须成功的功能）。
         */
        fun parse(raw: String?): GameSortOrder? {
            if (raw.isNullOrBlank()) return null
            val field = when (raw.substringBefore('_').trim().lowercase(Locale.ROOT)) {
                "name" -> GameSortField.NAME
                "time" -> GameSortField.TIME
                else -> return null
            }
            val ascending = when (raw.substringAfter('_', "").trim().lowercase(Locale.ROOT)) {
                "asc" -> true
                "desc" -> false
                // 只写了字段（历史/手写配置）时按该字段的默认方向
                else -> field.defaultAscending
            }
            return GameSortOrder(field, ascending)
        }
    }
}

/**
 * 资源列表排序（纯函数，无 Android 依赖，可直接单测）。
 *
 * 为什么不用 `sortedBy { it.displayName }`：
 * 资源名是「中文舰名 + 英文 id + 数字编号」混排，UTF-16 码点序对中文毫无意义
 * （「阿」会排在「白」后面，「企业」和「埃塞克斯」谁前谁后全看码位）。
 * 这里用 [Collator]（`Locale.CHINA`，ICU 拼音序）比较；并在排序前把每个名字转成
 * [CollationKey]：8000 条资源约 10 万次比较，逐次 `compare(String, String)` 每次都要
 * 走一遍 ICU 规则表，切排序时能感到卡顿，预生成 key 之后只是字节比较。
 *
 * 排序是**稳定**的，并且次关键字固定升序：主序翻转时（升↔降）只有主轴换向，
 * 时间相同/同名的那几条不会跟着乱跳，列表看起来才「只是倒过来了」。
 *
 * 混排规则完全交给 ICU（`Locale.CHINA`）：汉字按拼音、拉丁字母在汉字之前、
 * 数字按数值内的字符序 —— 与系统文件管理器一致，不做额外「让 123 挨着 Z」的私货规则。
 */
object GameAssetSorting {

    /** 资源包列表排序：时间取源包自身时间（[GameBundleEntry.modified]） */
    fun sortBundles(
        entries: List<GameBundleEntry>,
        order: GameSortOrder,
        nameOf: (GameBundleEntry) -> String
    ): List<GameBundleEntry> = sort(
        items = entries,
        order = order,
        nameOf = nameOf,
        timeOf = { it.modified },
        tieBreakerOf = { it.path }
    )

    /** 已导入资源排序：时间取资源目录的最后修改时间（≈ 导入时间） */
    fun sortAssets(
        assets: List<GameAssetInfo>,
        order: GameSortOrder,
        nameOf: (GameAssetInfo) -> String
    ): List<GameAssetInfo> = sort(
        items = assets,
        order = order,
        nameOf = nameOf,
        timeOf = { it.modifiedAt },
        tieBreakerOf = { "${it.kind.key}/${it.id}" }
    )

    /**
     * 通用排序。
     *
     * @param order 字段 + 方向
     * @param nameOf 显示名（可能是拼音反查/wiki 皮肤名，调用方通常已批量算好）
     * @param timeOf 时间戳（毫秒；0 表示未知）
     * @param tieBreakerOf 最后一级唯一键（路径 / `kind/id`），保证结果与输入顺序无关
     */
    fun <T> sort(
        items: List<T>,
        order: GameSortOrder,
        nameOf: (T) -> String,
        timeOf: (T) -> Long,
        tieBreakerOf: (T) -> String = { "" }
    ): List<T> {
        // 0/1 条不需要排序：直接返回原实例，remember 的引用比较才不会空转
        if (items.size < 2) return items

        val collator = Collator.getInstance(Locale.CHINA)
        val rows = ArrayList<SortRow<T>>(items.size)
        for (item in items) {
            rows += SortRow(
                name = collator.getCollationKey(nameOf(item).trim()),
                time = timeOf(item),
                tieBreaker = tieBreakerOf(item),
                item = item
            )
        }

        val primary: Comparator<SortRow<T>> = when (order.field) {
            GameSortField.NAME -> compareBy { it.name }
            GameSortField.TIME -> compareBy { it.time }
        }
        // 次关键字（固定升序）：时间相同看名字，名字相同看时间，再相同用唯一键兜底
        val secondary: Comparator<SortRow<T>> = when (order.field) {
            GameSortField.NAME -> compareBy({ it.time }, { it.tieBreaker })
            GameSortField.TIME -> compareBy({ it.name }, { it.tieBreaker })
        }
        val comparator =
            if (order.ascending) primary.then(secondary) else primary.reversed().then(secondary)

        return rows.sortedWith(comparator).map { it.item }
    }

    /** 排序中间行：名字先转成 [CollationKey]，避免每次比较都跑一遍 ICU 规则 */
    private class SortRow<T>(
        val name: CollationKey,
        val time: Long,
        val tieBreaker: String,
        val item: T
    )
}
