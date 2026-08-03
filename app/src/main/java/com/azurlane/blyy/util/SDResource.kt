package com.azurlane.blyy.util

import java.io.File

/**
 * SD 小人统一资源模型。
 *
 * 解除"舰娘列表驱动"限制：任何符合规范的 SD 资源（Spine 三件套或静态图片）
 * 都会被自动发现并包装为 [SDResource]，无需登记到舰娘数据库。
 *
 * 资源 ID = 资源目录名（拼音或自定义名），全局唯一。
 * 通过 [SDResourceManager.resolveById] 可直接按 ID 加载，无需舰名匹配。
 *
 * @property id 资源唯一标识（目录名，如 "boge" / "my_custom_char"）
 * @property name 显示名称（优先用 metadata 中的 name，回退到目录名）
 * @property type 资源类型（Spine 动画 / 静态图片 / 未知）
 * @property source 资源来源（舰娘 / 自定义导入）
 * @param skins 该资源的皮肤列表（至少含一个 default）
 * @property previewPath 预览图路径（atlas png 或静态图片本身），null 表示无预览
 * @property shipName 对应的中文舰名（仅舰娘资源有效，用于语音/台词联动），null 表示无对应舰娘
 * @property version 资源格式版本（预留扩展）
 */
data class SDResource(
    val id: String,
    val name: String,
    val type: SDResourceType,
    val source: SDResourceSource,
    private val skins: List<SDSkin>,
    val previewPath: String?,
    val shipName: String? = null,
    val version: Int = 1
) {
    /** 默认皮肤（列表第一个，通常是 default） */
    val defaultSkin: SDSkin get() = skins.firstOrNull { it.name == "default" } ?: skins.first()

    /** 所有皮肤名列表 */
    val skinNames: List<String> get() = skins.map { it.name }

    /** 皮肤数量 */
    val skinCount: Int get() = skins.size

    /** 是否为 Spine 动画资源 */
    val isSpine: Boolean get() = type == SDResourceType.SPINE

    /**
     * 获取指定皮肤的资源信息。
     * @param skinName 皮肤名（null / "" / "default" 用默认皮肤）
     * @return 对应皮肤，不存在则返回默认皮肤
     */
    fun getSkin(skinName: String?): SDSkin {
        if (skinName.isNullOrBlank() || skinName == "default") return defaultSkin
        return skins.firstOrNull { it.name == skinName } ?: defaultSkin
    }
}

/**
 * SD 资源皮肤信息。
 *
 * @param name 皮肤标识名（default / gai / skin2 / 自定义名）
 * @param displayName 用户可读的皮肤名（默认 / 改造 / 皮肤2 / ...）
 * @param dirPath 皮肤资源目录绝对路径
 * @param assetName 三件套主名（.skel 文件名去扩展名），静态图片时为图片文件名去扩展名
 * @param type 皮肤资源类型
 */
data class SDSkin(
    val name: String,
    val displayName: String,
    val dirPath: String,
    val assetName: String,
    val type: SDResourceType
)

/** SD 资源类型 */
enum class SDResourceType {
    /** Spine 骨骼动画（.skel + .atlas + .png） */
    SPINE,
    /** 单张静态图片（.png / .webp / .jpeg） */
    STATIC_IMAGE,
    /** 未识别格式 */
    UNKNOWN
}

/** SD 资源来源 */
enum class SDResourceSource {
    /** 舰娘资源（从舰名拼音匹配而来，向后兼容） */
    SHIP,
    /** 用户自定义导入的资源 */
    CUSTOM
}

/**
 * 皮肤名 → 中文显示名。
 * 兼容碧蓝航线标准皮肤后缀和自定义皮肤名。
 */
fun skinDisplayName(skin: String): String = when (skin.lowercase()) {
    "default" -> "默认"
    "gai" -> "改造"
    "huan" -> "换装"
    "huangxiang" -> "幻象"
    "doa" -> "DOA联动"
    "teyao" -> "特别计划"
    "mirror" -> "镜像"
    "left" -> "左半身"
    "right" -> "右半身"
    else -> if (skin.startsWith("skin")) "皮肤${skin.removePrefix("skin")}" else skin
}

/**
 * 检测文件是否为支持的静态图片格式。
 */
internal fun isImageFile(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
}

/**
 * 检测文件是否为 Spine 骨骼文件。
 */
internal fun isSkelFile(fileName: String): Boolean = fileName.lowercase().endsWith(".skel")

/**
 * 检测文件是否为 Spine atlas 文件。
 */
internal fun isAtlasFile(fileName: String): Boolean = fileName.lowercase().endsWith(".atlas")
