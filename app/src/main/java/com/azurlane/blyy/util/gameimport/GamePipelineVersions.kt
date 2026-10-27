package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.unity.AlLive2dExtractor

/**
 * 各类型提取管线的版本号。
 *
 * 产物结构变化（新增格式支持、修复贴图/坐标、新增动作）时**必须 +1**：
 * 索引里版本落后的记录会被判为「有更新」，用户下次扫描即可重新解包，
 * 不会出现「显示已导入但渲染还是坏的」。
 */
object GamePipelineVersions {

    /** Live2D：跟随 [AlLive2dExtractor.EXTRACTOR_VERSION]（共用同一套产物结构） */
    val LIVE2D: Int get() = AlLive2dExtractor.EXTRACTOR_VERSION

    /**
     * Spine（char / spinepainting）：
     * 1 = 初版；2 = 修复 `.skel` 被按 UTF-8 解码导致二进制损坏（旧产物必须重解包）
     */
    const val SPINE = 2

    /** 静态立绘：1 = 初版（单张 painting.png + 侧车） */
    const val PAINTING = 1

    fun of(kind: GameAssetKind): Int = when (kind) {
        GameAssetKind.LIVE2D -> LIVE2D
        GameAssetKind.PAINTING -> PAINTING
        GameAssetKind.SD_CHAR, GameAssetKind.DYNAMIC_SKIN -> SPINE
    }
}
