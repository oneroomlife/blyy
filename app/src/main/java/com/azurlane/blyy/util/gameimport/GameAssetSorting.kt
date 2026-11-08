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
 * 一条记录的排序键。
 *
 * 名字在构造时就折成 [CollationKey]：8000 条资源排序约 10 万次比较，
 * 逐次 `Collator.compare` 每次都要跑一遍 ICU 规则表；预生成之后只剩字节比较。
 *
 * 键可以**跨升降序、跨排序字段复用**：切换方向只是换一个 [Comparator]，
 * 切到「时间」也只需再读一次已经算好的字段 —— 不必重跑 ICU，也不必重查舰名。
 */
class GameAssetSortKey<T> internal constructor(
    /** 自然序归一后的名称键（见 [GameAssetSorting.naturalize]） */
    val name: CollationKey,
    /** 时间戳（毫秒；0 表示未知） */
    val time: Long,
    /** 最后一级唯一键（路径 / `kind/id`），保证结果与输入顺序无关 */
    val tieBreaker: String,
    val item: T
)

/**
 * 资源列表排序（纯函数，无 Android 依赖，可直接单测）。
 *
 * 为什么不用 `sortedBy { it.displayName }`：
 * 资源名是「中文舰名 + 英文 id + 数字编号」混排，UTF-16 码点序对中文毫无意义
 * （「阿」会排在「白」后面，「企业」和「埃塞克斯」谁前谁后全看码位）。
 * 这里用 [Collator]（`Locale.CHINA`，ICU 拼音序）比较；混排规则整体交给 ICU：
 * 汉字按拼音、拉丁字母在汉字之前，与系统文件管理器一致。
 *
 * 为什么要「自然序」（[naturalize]）：资源名大量带编号（`22_tex` / `2_tex`、
 * `abeikelongbi_4` / `abeikelongbi_14`、`立绘 10` / `立绘 2`）。纯字符序会把
 * 「10」排在「2」前面，用户看到的就是「名字没按顺序排」。这里把连续数字段补零到
 * 固定宽度再比较，等于按数值大小排；补零对中文/字母的 ICU 排序没有任何影响。
 *
 * 排序分两步（[keysOf] → [sorted]）：
 * 1. 生成键：名字反查 + 折 [CollationKey]，跟数据量成正比，**只在数据/名字变了时**做；
 * 2. 排序：纯比较，O(n log n)，切成百上千次方案也只是重排既有键。
 * UI 层因此不用在组合期跑 ICU —— 那正是原来「切排序/扫描结束卡一下」的来源。
 *
 * 排序是**稳定**的，并且次关键字固定升序：主序翻转时（升↔降）只有主轴换向，
 * 时间相同/同名的那几条不会跟着乱跳，列表看起来才「只是倒过来了」。
 */
object GameAssetSorting {

    /**
     * 数字段补零宽度：10 位足够覆盖游戏内的所有编号（9_999_999_999）。
     *
     * 超过宽度的数字段不补零也能正确比较：补零后所有段都以 0 开头，
     * 长数字的首位必然大于 0，位数多的自然更大。
     */
    private const val NUMBER_PAD_WIDTH = 10

    // ---------------------------------------------------------------- 两段式 API

    /** 资源包排序键：时间取源包自身时间（[GameBundleEntry.modified]） */
    fun bundleKeys(
        entries: List<GameBundleEntry>,
        nameOf: (GameBundleEntry) -> String
    ): List<GameAssetSortKey<GameBundleEntry>> = keysOf(
        items = entries,
        nameOf = nameOf,
        timeOf = { it.modified },
        tieBreakerOf = { it.path }
    )

    /** 已导入资源排序键：时间取资源目录的最后修改时间（≈ 导入时间） */
    fun assetKeys(
        assets: List<GameAssetInfo>,
        nameOf: (GameAssetInfo) -> String
    ): List<GameAssetSortKey<GameAssetInfo>> = keysOf(
        items = assets,
        nameOf = nameOf,
        timeOf = { it.modifiedAt },
        tieBreakerOf = { "${it.kind.key}/${it.id}" }
    )

    /**
     * 按已有键排出顺序（切方向/切字段走这里，不再碰 ICU）。
     *
     * 返回的是**键**而不是条目：键里存的是生成那一刻的条目快照，
     * 而「导入成功 / 导入失败」只改条目状态、不改 mtime 与路径（签名因此不变、键会命中缓存）。
     * 调用方拿到顺序后应按 [GameAssetSortKey.tieBreaker] 换回最新条目，
     * 否则列表会一直显示旧的「待导入」徽章与「导入」按钮。
     */
    fun <T> sortedKeys(
        keys: List<GameAssetSortKey<T>>,
        order: GameSortOrder
    ): List<GameAssetSortKey<T>> {
        // 0/1 条不需要排序：直接返回原列表，省掉一次 Comparator 分配与排序
        if (keys.size < 2) return keys

        val primary: Comparator<GameAssetSortKey<T>> = when (order.field) {
            GameSortField.NAME -> compareBy { it.name }
            GameSortField.TIME -> compareBy { it.time }
        }
        // 次关键字（固定升序）：时间相同看名字，名字相同看时间，再相同用唯一键兜底
        val secondary: Comparator<GameAssetSortKey<T>> = when (order.field) {
            GameSortField.NAME -> compareBy({ it.time }, { it.tieBreaker })
            GameSortField.TIME -> compareBy({ it.name }, { it.tieBreaker })
        }
        val comparator =
            if (order.ascending) primary.then(secondary) else primary.reversed().then(secondary)

        return keys.sortedWith(comparator)
    }

    /** 按已有键排序并取回键里带的条目（键与数据同步时用；数据可能变旧时用 [sortedKeys]） */
    fun <T> sorted(keys: List<GameAssetSortKey<T>>, order: GameSortOrder): List<T> =
        sortedKeys(keys, order).map { it.item }

    // ---------------------------------------------------------------- 一步到位（小列表用）

    /** 资源包列表排序：一次性生成键并排序（列表很小、或调用方不缓存键时用） */
    fun sortBundles(
        entries: List<GameBundleEntry>,
        order: GameSortOrder,
        nameOf: (GameBundleEntry) -> String
    ): List<GameBundleEntry> =
        // 0/1 条无需排序，返回原实例：Compose 侧的 remember/LaunchedEffect 靠引用相等跳过重组
        if (entries.size < 2) entries else sorted(bundleKeys(entries, nameOf), order)

    /** 已导入资源排序：一次性生成键并排序 */
    fun sortAssets(
        assets: List<GameAssetInfo>,
        order: GameSortOrder,
        nameOf: (GameAssetInfo) -> String
    ): List<GameAssetInfo> =
        if (assets.size < 2) assets else sorted(assetKeys(assets, nameOf), order)

    // ---------------------------------------------------------------- 基础件

    /**
     * 生成排序键。
     *
     * @param nameOf 显示名（可能是拼音反查/wiki 皮肤名，调用方通常已批量算好）
     * @param timeOf 时间戳（毫秒；0 表示未知）
     * @param tieBreakerOf 最后一级唯一键，保证结果与输入顺序无关
     */
    fun <T> keysOf(
        items: List<T>,
        nameOf: (T) -> String,
        timeOf: (T) -> Long,
        tieBreakerOf: (T) -> String = { "" }
    ): List<GameAssetSortKey<T>> {
        if (items.isEmpty()) return emptyList()
        // Collator 不是线程安全的：每次生成键都取一个（getInstance 只是 clone，很轻）
        val collator = Collator.getInstance(Locale.CHINA)
        val keys = ArrayList<GameAssetSortKey<T>>(items.size)
        for (item in items) {
            keys += GameAssetSortKey(
                name = collator.getCollationKey(naturalize(nameOf(item))),
                time = timeOf(item),
                tieBreaker = tieBreakerOf(item),
                item = item
            )
        }
        return keys
    }

    /**
     * 自然序归一：把每一段连续 ASCII 数字左侧补零到 [NUMBER_PAD_WIDTH] 位。
     *
     * `立绘 2` → `立绘 0000000002`，`立绘 10` → `立绘 0000000010`，
     * 于是 ICU 的字符序就等价于数值序（2 在 10 之前）。
     * 非数字字符原样保留，中英文混排规则仍由 ICU 决定。
     */
    internal fun naturalize(raw: String): String {
        if (raw.isEmpty()) return raw
        // 没有数字就原样返回：绝大多数名字走这条快路径，不分配新串
        var hasDigit = false
        for (ch in raw) if (ch in '0'..'9') { hasDigit = true; break }
        if (!hasDigit) return raw.trim()

        val out = StringBuilder(raw.length + NUMBER_PAD_WIDTH)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch !in '0'..'9') {
                out.append(ch)
                index++
                continue
            }
            var end = index
            while (end < raw.length && raw[end] in '0'..'9') end++
            val digits = end - index
            var pad = NUMBER_PAD_WIDTH - digits
            while (pad-- > 0) out.append('0')
            out.append(raw, index, end)
            index = end
        }
        return out.toString().trim()
    }
}
