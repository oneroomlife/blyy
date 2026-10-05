package com.azurlane.blyy.util

import androidx.compose.runtime.Immutable
import java.io.File

/** 一个动作组内的单条动作定义 */
@Immutable
data class Live2dMotion(
    /** 动作文件相对模型目录的路径，如 motions/idle.motion3.json */
    val file: String,
    /** 声音文件相对路径（可空，碧蓝航线官方rip通常无声音） */
    val sound: String? = null
)

/** 单条表情定义 */
@Immutable
data class Live2dExpression(
    val name: String,
    val file: String
)

/**
 * Live2D 模型元信息（扫描结果，只读快照）。
 *
 * id 即模型目录名（live2d 根目录下的一级文件夹），全局唯一；
 * 其余字段均来自递归扫描 + model3.json 解析。
 */
@Immutable
data class Live2dModelInfo(
    val id: String,
    val dir: File,
    /** model3.json 文件名（相对 dir），如 aierdeliqi_4.model3.json */
    val model3File: String,
    /** Cubism 版本号字符串（model3.json 的 Version 字段） */
    val version: String?,
    val mocFile: String?,
    val textures: List<String>,
    val physicsFile: String?,
    val poseFile: String?,
    val motionGroups: List<Pair<String, List<Live2dMotion>>>,
    val expressions: List<Live2dExpression>,
    /** 缩略图文件（查看器加载后自动生成），null = 尚未生成 */
    val thumbFile: File?,
    /** 匹配到的内置舰娘头像资产名（如 "aierdeliqi.webp"），作缩略图兜底 */
    val avatarAsset: String?,
    val sizeBytes: Long,
    val fileCount: Int
) {
    val totalMotions: Int get() = motionGroups.sumOf { it.second.size }
    val hasExpressions: Boolean get() = expressions.isNotEmpty()
}

/** 扫描过程中解析失败的单个目录（不含有效 moc3 或 json 损坏） */
@Immutable
data class Live2dInvalidDir(
    val id: String,
    val reason: String
)
