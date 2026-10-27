package com.azurlane.blyy.util.gameimport

import androidx.compose.runtime.Immutable

/**
 * 碧蓝航线游戏资源包的四类可导入资源。
 *
 * 游戏把「立绘画像 / SD 小人 / 动态皮肤 / Live2D」分别放在 `AssetBundles` 下的
 * 同名子目录里，资源包内容差异很大，因此导入链路按类分流：
 *
 * | 类型 | 目录 | 包内结构 | 渲染方式 |
 * |------|------|----------|----------|
 * | [LIVE2D] | `live2d/` | MonoBehaviour 内嵌 moc3 + 贴图 + Mecanim 动作 | pixi-live2d-display（WebView） |
 * | [SD_CHAR] | `char/` | `.skel` + `.atlas` TextAsset + 贴图 | libgdx Spine 3.8 |
 * | [DYNAMIC_SKIN] | `spinepainting/` | 同上（骨骼包在 `*_res` 内） | libgdx Spine 3.8 |
 * | [PAINTING] | `painting/` | 单张超大贴图（`*_tex`） | 静态图浏览 |
 *
 * [key] 同时用作路由参数、索引字段与库内子目录名，**不可随意改动**。
 */
@Immutable
enum class GameAssetKind(
    val key: String,
    /** 游戏 `AssetBundles/` 下的子目录名 */
    val bundleDir: String,
    val label: String,
    val shortLabel: String,
    val description: String,
    val isSpine: Boolean = false
) {
    LIVE2D(
        key = "live2d",
        bundleDir = "live2d",
        label = "Live2D 皮肤",
        shortLabel = "Live2D",
        description = "Cubism 3/4 模型（moc3 + 贴图 + 动作），支持陪伴模式"
    ),
    SD_CHAR(
        key = "char",
        bundleDir = "char",
        label = "SD 小人",
        shortLabel = "SD",
        description = "Spine 骨骼 Q 版小人，可作秘书舰小人",
        isSpine = true
    ),
    DYNAMIC_SKIN(
        key = "spinepainting",
        bundleDir = "spinepainting",
        label = "动态皮肤",
        shortLabel = "动态",
        description = "Spine 动态立绘皮肤（骨骼与贴图在同名 _res 包内）",
        isSpine = true
    ),
    PAINTING(
        key = "painting",
        bundleDir = "painting",
        label = "静态立绘",
        shortLabel = "立绘",
        description = "高清静态立绘贴图（取同名 _tex 包内的贴图）"
    );

    /** 该类型是否从 `*.skel` + `*.atlas` 三件套渲染 */
    val usesSpineSkeleton: Boolean get() = isSpine

    companion object {
        /**
         * 目录别名 → 类型。
         * `metaship` 是 META 舰的 Q 版小人目录，结构与 `char` 完全一致；
         * `sd` / `spine` 兼容用户自行整理过的资源目录。
         */
        private val DIR_ALIASES: Map<String, GameAssetKind> = mapOf(
            "live2d" to LIVE2D,
            "char" to SD_CHAR,
            "metaship" to SD_CHAR,
            "sd" to SD_CHAR,
            "spine" to SD_CHAR,
            "spinepainting" to DYNAMIC_SKIN,
            "painting" to PAINTING
        )

        /** 路由 / 索引里的类型字段（大小写不敏感；未知返回 null） */
        fun fromKey(key: String?): GameAssetKind? {
            if (key.isNullOrBlank()) return null
            val normalized = key.trim().lowercase()
            return entries.firstOrNull { it.key == normalized }
                ?: DIR_ALIASES[normalized]
        }

        /**
         * 从资源包路径（`/sdcard/.../AssetBundles/char/xxx.ys` 或 `char/xxx`）推断类型。
         * 取**最靠后的**匹配目录：`AssetBundles/live2d/...` 优于更外层的同名片段。
         */
        fun fromBundlePath(path: String?): GameAssetKind? {
            if (path.isNullOrBlank()) return null
            val segments = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
            for (i in segments.indices.reversed()) {
                DIR_ALIASES[segments[i].lowercase()]?.let { return it }
            }
            return null
        }

        /** 包内 `AssetBundle.m_Name`（形如 `char/anninvwang_3`）→ 类型 */
        fun fromBundleName(bundleName: String?): GameAssetKind? {
            if (bundleName.isNullOrBlank()) return null
            val head = bundleName.replace('\\', '/').substringBefore('/').lowercase()
            return DIR_ALIASES[head] ?: fromBundlePath(bundleName)
        }

        /** 列表展示顺序：与游戏资源目录一致 */
        val ordered: List<GameAssetKind> = listOf(LIVE2D, SD_CHAR, DYNAMIC_SKIN, PAINTING)
    }
}
