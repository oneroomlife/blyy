package com.azurlane.blyy.util.gameimport

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 已导入资源库中的一个资产（只读快照）。
 *
 * [id] 即库内目录名，全局唯一；[kind] 区分渲染方式。
 */
@Immutable
data class GameAssetInfo(
    val kind: GameAssetKind,
    val id: String,
    val dir: File,
    val sizeBytes: Long,
    val fileCount: Int,
    /** Spine：目录内全部骨骼主名（多骨骼时查看器可切换） */
    val skeletons: List<String> = emptyList(),
    /** Spine：默认骨骼主名 */
    val primarySkeleton: String? = null,
    /** Spine 二进制版本（如 3.8.99）；Cubism 的 Version；null 表示未知 */
    val spineVersion: String? = null,
    /** Spine 图集页数 / 立绘张数 */
    val pageCount: Int = 0,
    /** 静态立绘像素尺寸 */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /**
     * 静态立绘的还原方式：`layout`（按 prefab 多层合成）/ `mesh`（单层网格还原）/
     * `raw`（贴图本身就是成品）。null = 历史产物未记录，按原始贴图看待。
     */
    val paintingMode: String? = null,
    /** 合成使用的图层数（layout 模式多为 2~4） */
    val paintingLayers: Int = 0,
    /** 主图集尺寸（如 `2048x1132`），用于界面说明「由图集还原」 */
    val paintingAtlas: String? = null,
    /** 卡片预览图（立绘本身 / Live2D 截图） */
    val previewFile: File? = null,
    /** 内置舰娘头像兜底资产名 */
    val avatarAsset: String? = null,
    /** 变体说明（换装特效 / 改造 / 重绘 …） */
    val variantLabel: String? = null,
    /** 版本标签（Spine 3.8.99 / Cubism 4 …） */
    val versionLabel: String? = null,
    /** 来源备注（资源包名等） */
    val sourceNote: String? = null
) {
    val isSpine: Boolean get() = kind.isSpine
    val isPainting: Boolean get() = kind == GameAssetKind.PAINTING
    val isLoadingBg: Boolean get() = kind == GameAssetKind.LOADING_BG

    /** 单张图片型资源（静态立绘 / 过场图）：查看器用可缩放图片、支持导出 */
    val isImage: Boolean get() = isPainting || isLoadingBg
    val isLive2d: Boolean get() = kind == GameAssetKind.LIVE2D
}

/** 资源包内容探测结论 */
enum class GameBundleFormat {
    /** Cubism 模型（MonoBehaviour 内嵌 MOC3） */
    LIVE2D,

    /** Spine 三件套（.skel / .atlas / 贴图） */
    SPINE,

    /** 只有贴图（静态立绘） */
    TEXTURE,

    /** 只有 UI 预制体 / 材质，没有可直接渲染的资源 */
    PREFAB_ONLY,

    /** 无法识别 */
    UNKNOWN
}

/**
 * 资源包探测结果：决定用哪个提取器、落到哪个库、以及失败时给用户的可读原因。
 */
@Immutable
data class BundleProbeResult(
    val format: GameBundleFormat,
    /** 可判定的目标类型；[format] 为 [GameBundleFormat.PREFAB_ONLY] 时可能为 null */
    val kind: GameAssetKind?,
    /** 包内 `AssetBundle.m_Name`（`char/anninvwang_3`） */
    val bundleName: String? = null,
    /** Spine 骨骼主名（不含扩展名） */
    val skeletonBases: List<String> = emptyList(),
    val spineVersion: String? = null,
    val atlasNames: List<String> = emptyList(),
    val textureCount: Int = 0,
    val textureMaxSide: Int = 0,
    val textureFormatLabel: String? = null,
    /** 不可导入时的原因（面向用户） */
    val reason: String? = null
) {
    val importable: Boolean
        get() = format == GameBundleFormat.LIVE2D ||
            format == GameBundleFormat.SPINE ||
            format == GameBundleFormat.TEXTURE
}

/**
 * 资源 id / 显示名规则（纯函数，便于单测）。
 *
 * 碧蓝的立绘资源包是 `<皮肤>_tex`、动态皮肤是 `<皮肤>_res`，库里统一去掉这类
 * 后缀，让「同一个皮肤的立绘 / 动态皮肤 / Live2D」在列表中同名可辨；
 * 变体后缀（`_hx` / `_n` / `_rw` …）保留在 id 里，只用于追加中文说明。
 */
object GameAssetNaming {

    /** 资源包类型后缀（按类型限定，避免误删真实皮肤名里的 `_res`） */
    private val BUNDLE_SUFFIX: Map<GameAssetKind, List<String>> = mapOf(
        GameAssetKind.PAINTING to listOf("_tex"),
        GameAssetKind.DYNAMIC_SKIN to listOf("_res")
    )

    /**
     * 已知的变体后缀。**顺序敏感**：长后缀优先（`_shophx` 必须先于 `_hx` 判定）。
     *
     * 展示标签**不做语义猜测**，直接回显后缀（`_hx` → `HX`）：碧蓝不同版本对
     * `hx` / `n` 的用法并不统一，猜错比不猜更糟——用户一眼能对上资源包名。
     */
    private val VARIANT_SUFFIXES: List<String> = listOf("_shophx", "_rw", "_hx", "_doa", "_n")

    /** 包内文件名 → 资源 id（含后缀剥离 + 非法字符归一） */
    fun assetIdOf(bundleFileName: String, kind: GameAssetKind?): String {
        var base = bundleFileName.substringBeforeLast('.')
        if (kind != null) {
            BUNDLE_SUFFIX[kind]?.forEach { suffix ->
                if (base.endsWith(suffix, ignoreCase = true) && base.length > suffix.length) {
                    base = base.dropLast(suffix.length)
                }
            }
        }
        return sanitizeId(base)
    }

    /** `..._bj1` / `..._bj2_hx` 这类背景层条带 */
    private val PAINTING_LAYER_BG = Regex(".*_bj\\d+(?:_hx)?$")

    /**
     * 是否为立绘**分层包**（人物层 / 前景层 / 背景层条带 / 商店小图）。
     *
     * 碧蓝 `painting/` 下一个皮肤往往有多个包：
     * - `X_tex`      主角立绘（完整图，可以独立展示）
     * - `X_rw_tex`   人物层：把人物从背景抠出来的**碎片图集**，运行时由 Mesh 摆放
     * - `X_front_tex` 前景层
     * - `X_bj1_tex` / `X_bj2_tex` 背景层条带（常见 1024×362 这种窄条）
     * - `X_shophx_tex` 商店/缩略图
     *
     * 这些层单独看都是残图（散落碎片或一条窄带）。早先只按 `_tex` 过滤，
     * 它们被当成独立立绘进库，用户点开看到的就是「拼接错位 / 部分缺失」。
     * 现在扫描与资源库扫描共用本判定，只保留主立绘。
     *
     * 入参允许带 `_tex` 后缀（传目录名或包名都能用）。
     */
    fun isPaintingLayer(id: String): Boolean {
        val core = if (id.endsWith("_tex", ignoreCase = true) && id.length > 4) {
            id.dropLast(4)
        } else {
            id
        }
        val lower = core.lowercase()
        if (PAINTING_LAYER_BG.matches(lower)) return true
        return lower.contains("_rw") || lower.contains("_front") || lower.contains("_shophx")
    }

    /** 变体拆解：`zhaohe_4_hx` → ("zhaohe_4", "HX")；无变体返回 null */    fun variantOf(id: String): Pair<String, String>? {
        for (suffix in VARIANT_SUFFIXES) {
            if (id.endsWith(suffix, ignoreCase = true) && id.length > suffix.length) {
                return id.dropLast(suffix.length) to suffix.removePrefix("_").uppercase()
            }
        }
        return null
    }

    /**
     * 目录名归一：仅保留字母/数字/下划线/短横线，压缩到 48 字符。
     *
     * **保留原始大小写**：已有资源的目录名是不能随意改写的（改了就等于
     * 让用户已导入的资产失联），因此这里保持历史行为。
     * 大小写差异统一在**查找侧**消化：
     * - 头像匹配走 [com.azurlane.blyy.util.AvatarAssetIndex] 的小写索引；
     * - 舰名解析走 [com.azurlane.blyy.util.Live2dNameResolver] 的大小写不敏感比对。
     * 这样 `2B_2` 这类含大写的资源既不会失联，也能正确匹配到 `2B.webp`。
     *
     * 与 [com.azurlane.blyy.util.Live2dLibrary.isValidId] 的约束保持一致。
     */
    fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '_' || ch == '-' -> ch
                else -> '_'
            }
        }.joinToString("").trim('_').take(48)
        return cleaned.ifBlank { "asset" }
    }

    /** 便于日志/提示的可读类型名 */
    fun formatLabel(format: GameBundleFormat): String = when (format) {
        GameBundleFormat.LIVE2D -> "Live2D 模型"
        GameBundleFormat.SPINE -> "Spine 骨骼"
        GameBundleFormat.TEXTURE -> "静态贴图"
        GameBundleFormat.PREFAB_ONLY -> "预制体配置"
        GameBundleFormat.UNKNOWN -> "未知资源"
    }
}
