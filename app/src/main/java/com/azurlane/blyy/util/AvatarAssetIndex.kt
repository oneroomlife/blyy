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

    /** 全部头像文件名（懒加载一次） */
    fun assetNames(): Set<String> = names ?: synchronized(lock) {
        names ?: runCatching { context.assets.list("blhx_avatar")?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
            .also { names = it }
    }

    fun resolve(assetId: String): String? {
        if (assetId.isBlank()) return null
        val all = assetNames()
        if (all.isEmpty()) return null
        for (base in candidateBases(assetId)) {
            val hit = setOfNotNull("$base.webp", "$base.png", "$base.jpg").firstOrNull { it in all }
            if (hit != null) return hit
        }
        return null
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
