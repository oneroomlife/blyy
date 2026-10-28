package com.azurlane.blyy.util.gameimport

/**
 * 静态立绘「附属包」判定（纯函数，便于单测）。
 *
 * 立绘的完整画面由多个 bundle 拼成，但它们的命名**不是**简单的 `<主包>_<图层>`：
 *
 * ```
 * painting/2b_tex        基础立绘（背景 + 人物都在这一张图集里）
 * painting/2b_rw_tex     人物层
 * painting/2b_bj1_tex    背景条
 * painting/2b_hx_tex     HX 换装（独立作品）
 * painting/2b_rw_hx_tex  HX 换装的人物层   ← 变体标记夹在「基础名」和「图层名」之间
 * painting/2b_n_rw_tex   `_n` 换装的人物层（与 HX 不是同一套）
 * ```
 *
 * 只按 `<主包名>_` 前缀匹配会漏掉 `2b_rw_hx_tex`（实测让 `2b_hx` 的四层只合成出背景）；
 * 反过来宽松前缀又会把别的换装图层一起搬过来。判定规则：
 *
 * - **拆名**：去掉扩展名与 `_tex`，把变体标记（`hx`/`n`/`doa`/`shophx`/`buildhx`）
 *   从任意位置摘出来 → `(基础名, 变体集合)`，`2b_rw_hx_tex` = (`2b_rw`, {hx})；
 * - **prefab** 必须完全同名（基础名 + 变体集合都相同）；
 * - **图层包**的基础名 = `主包基础名 + 图层关键字`（`rw`/`front`/`bjN`），
 *   且图层包的变体集合必须是主包变体集合的**子集**（`{}` 表示共用基础图层）。
 *
 * 【性能】`AssetBundles/painting` 有 1 万+ 文件、候选立绘 6000+，
 * 逐个候选去扫全目录是 O(N²)（实测 6900 万次字符串解析，扫描卡 4 分钟）。
 * 因此对外主入口是 [companionsForAll]：**一次遍历**建好
 * `基础名 → 文件`、`主包基础名 → 图层` 两张索引，之后每个候选都是 O(1) 查表。
 */
internal object PaintingCompanionRules {

    /** 变体标记（可出现在包名的任意位置） */
    private val VARIANT_TOKENS = setOf("hx", "n", "doa", "shophx", "buildhx")

    /** 单次导入最多复制的附属包数量（每个 1~2MB，防止异常命名把整目录都拖进来） */
    const val MAX_COMPANIONS = 8

    private val BG_LAYER = Regex("^bj\\d*$")

    /** 图层关键字：`rw` 人物层、`front` 前景层、`bj1`/`bj2` 背景条 */
    private fun isLayerToken(token: String): Boolean =
        token == "rw" || token == "front" || BG_LAYER.matches(token)

    /**
     * 拆名结果。
     *
     * @param base 去掉变体标记与 `_tex` 的基础名（`2b_rw`）
     * @param variants 变体集合（`2b_rw_hx_tex` → `{hx}`）
     * @param lastToken 基础名的最后一段（`rw` / `bj1` / `2b`），用于判断是不是图层包
     */
    class SplitName(val base: String, val variants: Set<String>, val lastToken: String?) {
        operator fun component1() = base
        operator fun component2() = variants
    }

    /**
     * 拆包名（单遍扫描，避免 split + 两次 filter + joinToString 的临时对象；
     * 一万个文件时这段占了扫描时间的 1/5）。
     *
     * 入参可以带扩展名（`.ys` / `.unity3d`），也可以带 `_tex` 后缀。
     */
    fun splitName(name: String): SplitName {
        val dot = name.lastIndexOf('.')
        val core = if (dot > 0) name.substring(0, dot) else name
        val builder = StringBuilder(core.length)
        var variants: MutableSet<String>? = null
        var lastToken: String? = null
        var start = 0
        val length = core.length
        while (start <= length) {
            var end = core.indexOf('_', start)
            if (end < 0) end = length
            if (end > start) {
                val token = core.substring(start, end)
                val lower = token.lowercase()
                val isTexTail = end == length && lower == "tex"
                if (!isTexTail && lower in VARIANT_TOKENS) {
                    (variants ?: HashSet<String>(2).also { variants = it }).add(lower)
                } else if (!isTexTail) {
                    if (builder.isNotEmpty()) builder.append('_')
                    builder.append(lower)
                    lastToken = lower
                }
            }
            if (end == length) break
            start = end + 1
        }
        return SplitName(builder.toString(), variants ?: emptySet(), lastToken)
    }

    /** 目录内的一个文件（拆名结果只算一次） */
    private class Entry(
        /** 归一化后的文件名（小写、去掉扩展名）：同一 bundle 带不带 `.ys` 都能命中 */
        val key: String,
        val path: String,
        val base: String,
        val variants: Set<String>
    )

    /**
     * 一次遍历算出「目录内每个文件」的附属包清单。
     *
     * @param nameIndex 文件名（小写）→ 远端绝对路径
     * @param uiAtlases 共享 UI 图集的候选路径（装饰层用；不同客户端命名带不带 `.ys` 都有，
     *   缺失的那份在复制阶段自然被跳过）
     * @return 文件名（小写）→ 附属包路径列表
     */
    fun companionsForAll(
        nameIndex: Map<String, String>,
        uiAtlases: List<String>,
        limit: Int = MAX_COMPANIONS
    ): Map<String, List<String>> {
        val entries = ArrayList<Entry>(nameIndex.size)
        // 基础名 → 同基础名的文件（prefab 就是「基础名与变体都相同」的那个）
        val byBase = HashMap<String, MutableList<Entry>>(nameIndex.size / 2)
        // 主包基础名 → 它的图层包（图层名以 rw/front/bjN 结尾）
        val layersByMain = HashMap<String, MutableList<Entry>>(nameIndex.size / 4)

        for ((rawKey, path) in nameIndex) {
            val key = rawKey.substringBeforeLast('.').lowercase()
            val split = splitName(key)
            val entry = Entry(key, path, split.base, split.variants)
            entries += entry
            byBase.getOrPut(split.base) { ArrayList(2) } += entry

            val last = split.lastToken
            if (last != null && isLayerToken(last)) {
                val mainBase = split.base.removeSuffix("_$last")
                if (mainBase.isNotEmpty()) {
                    layersByMain.getOrPut(mainBase) { ArrayList(4) } += entry
                }
            }
        }

        val out = HashMap<String, List<String>>(entries.size)
        for (entry in entries) {
            val companions = ArrayList<String>(4)
            // prefab：基础名 + 变体集合都相同，且不是自己
            byBase[entry.base]?.firstOrNull {
                it.key != entry.key && it.variants == entry.variants
            }?.let { companions += it.path }

            val maxLayers = limit - companions.size - uiAtlases.size
            if (maxLayers > 0) {
                val budget = companions.size + maxLayers
                for (layer in layersByMain[entry.base].orEmpty()) {
                    if (companions.size >= budget) break
                    if (layer.key == entry.key) continue
                    // 图层包的变体集合必须是主包子集：{} 表示共用基础图层
                    if (!entry.variants.containsAll(layer.variants)) continue
                    companions += layer.path
                }
            }
            for (atlas in uiAtlases) {
                if (companions.size >= limit) break
                companions += atlas
            }
            out[entry.key] = companions.take(limit)
        }
        return out
    }

    /**
     * 单个文件的附属包（内部走 [companionsForAll]，保证与批量结果完全一致）。
     *
     * 仅供测试与排查使用；扫描链路必须用 [companionsForAll] 避免 O(N²)。
     */
    fun companions(
        nameIndex: Map<String, String>,
        mainFileName: String,
        uiAtlas: String?,
        limit: Int = MAX_COMPANIONS
    ): List<String> = companionsForAll(nameIndex, listOfNotNull(uiAtlas), limit)[
        mainFileName.substringBeforeLast('.').lowercase()
    ] ?: emptyList()
}
