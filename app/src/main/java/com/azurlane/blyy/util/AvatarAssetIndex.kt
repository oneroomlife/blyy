package com.azurlane.blyy.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `assets/blhx_avatar` 头像资产索引（进程级单例）。
 *
 * 资源 id（拼音）→ 头像文件名的兜底匹配，供 Live2D 皮肤库、游戏资源库等
 * 「没有渲染缩略图时」的卡片封面使用。
 *
 * 匹配规则（逐级放宽，全部基于文件名，不做模糊猜测）：
 * 1. 原样小写（`aierdeliqi` → `aierdeliqi.webp`）
 * 2. 去掉换装序号后缀（`aierdeliqi_4` → `aierdeliqi`）
 * 3. 去掉形态/变体后缀（`zhaohe_4_hx` → `zhaohe`）；`_N` 与变体后缀可叠加
 */
@Singleton
class AvatarAssetIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** 换装序号后缀：`_2` / `_12` */
        private val SKIN_INDEX = Regex("""^[0-9]{1,2}$""")

        /**
         * 形态/变体后缀（与 [com.azurlane.blyy.util.gameimport.GameAssetNaming] 的展示标签
         * 保持同一批后缀，但这里只用于**找图**，不产出文案）。
         * 顺序敏感：长后缀优先。
         */
        private val VARIANT_SUFFIXES = listOf("_shophx", "_doa", "_rw", "_hx", "_n")
    }

    private val lock = Any()

    @Volatile
    private var names: Set<String>? = null

    /** 小写基名 → 真实头像文件名（懒构建一次） */
    @Volatile
    private var lowerNames: Map<String, String>? = null

    /** 全部头像文件名（懒加载一次） */
    fun assetNames(): Set<String> = names ?: synchronized(lock) {
        names ?: runCatching { context.assets.list("blhx_avatar")?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
            .also { names = it }
    }

    /**
     * 资源 id → 头像文件名。
     *
     * 资源 id 一律小写（目录名由 `GameAssetNaming.sanitizeId` 生成 / 手机端目录为小写），
     * 而头像文件名保留原始大小写（`2B.webp` / `A2.webp` / `HDN101.webp`）。
     * 因此查找必须**忽略大小写**，否则 `2b_2` / `a2_2` 这类舰娘永远找不到封面
     * （表现就是资源卡空图、像是没被正确加载）。
     */
    fun resolve(assetId: String): String? {
        if (assetId.isBlank()) return null
        if (assetNames().isEmpty()) return null
        val lookup = lowerNameMap()
        for (base in candidateBases(assetId)) {
            lookup[base.lowercase()]?.let { return it }
        }
        return null
    }

    /** 小写基名 → 真实文件名（同基名多扩展名时 webp 优先，其次 png、jpg） */
    private fun lowerNameMap(): Map<String, String> = lowerNames ?: synchronized(lock) {
        lowerNames ?: run {
            val priority = listOf("webp", "png", "jpg")
            fun rank(fileName: String): Int {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val i = priority.indexOf(ext)
                return if (i < 0) priority.size else i
            }
            val map = HashMap<String, String>()
            assetNames().forEach { name ->
                val base = name.substringBeforeLast('.').lowercase()
                val current = map[base]
                if (current == null || rank(name) < rank(current)) map[base] = name
            }
            map.also { lowerNames = it }
        }
    }

    /** 逐级放宽的候选基名（去重、保序） */
    internal fun candidateBases(assetId: String): List<String> {
        val out = LinkedHashSet<String>()
        var current = assetId.trim().lowercase()
        out += current
        var changed = true
        while (changed) {
            changed = false
            val variant = VARIANT_SUFFIXES.firstOrNull {
                current.endsWith(it) && current.length > it.length
            }
            if (variant != null) {
                current = current.dropLast(variant.length)
                out += current
                changed = true
            }
            val lastUnderscore = current.lastIndexOf('_')
            if (lastUnderscore > 0 && SKIN_INDEX.matches(current.substring(lastUnderscore + 1))) {
                current = current.substring(0, lastUnderscore)
                out += current
                changed = true
            }
        }
        return out.filter { it.isNotBlank() }
    }
}
