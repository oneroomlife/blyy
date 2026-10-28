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
     * 1 = 初版；
     * 2 = 修复 `.skel` 被按 UTF-8 解码导致二进制损坏（旧产物必须重解包）；
     * 3 = 图集默认保持原分辨率（不再缩放到 3072/2048），并修正旋转区域补偿，
     *     旧产物即使能渲染也存在像素级错位，需重解包；
     * 4 = **撤销旋转归一化**：图集 `rotate` 原样保留（libgdx 1.13.5 会把
     *     true/90/180/270 全部解析成 degrees，spine 3.8 的 mesh 分支完整支持），
     *     不再对页像素做逐区域 180° 翻转。版本 3 的产物里 270/180 区域已被
     *     改写并翻过像素，渲染必然错位，必须重解包。
     */
    const val SPINE = 4

    /**
     * 静态立绘：
     * 1 = 初版（单张 painting.png + 侧车）；
     * 2 = 侧车新增 `layered` 标记（源包内含 Mesh = 运行时分层拼装的碎片图集），
     *     并把人物层/背景层/前景层等**残图包**排除出立绘库。旧产物没有该标记，
     *     界面无法区分「完整立绘」与「分层素材」，需要重解包补齐；
     * 3 = **按游戏本体的方式还原**：有网格就按网格把碎片图集拼回整图，
     *     有 prefab 就按 MeshImage 图层布局多层合成（背景 + 人物 + 舰装）。
     *     侧车改为记录 `mode/layers/atlas`。版本 2 的产物是**图集本身**
     *     （碎片乱飞、人物错位），必须重解包；
     * 4 = 附属包判定改为「变体感知」：换装图层实际命名为
     *     `2b_rw_hx_tex`（变体夹在中间），旧规则只按 `<主包>_` 前缀匹配，
     *     导致 `2b_hx` 这类换装只合成出背景。版本 3 的产物缺图层，需重解包。
     *     （附属包判定另有性能修复：O(N²) → 一次遍历建索引，不影响产物结构。）
     */
    const val PAINTING = 4

    /**
     * 过场图：
     * 1 = 初版（取包内最大贴图，1920×1080 ASTC 6x6；侧车复用立绘结构）
     */
    const val LOADING_BG = 1

    fun of(kind: GameAssetKind): Int = when (kind) {
        GameAssetKind.LIVE2D -> LIVE2D
        GameAssetKind.PAINTING -> PAINTING
        GameAssetKind.LOADING_BG -> LOADING_BG
        GameAssetKind.SD_CHAR, GameAssetKind.DYNAMIC_SKIN -> SPINE
    }
}
