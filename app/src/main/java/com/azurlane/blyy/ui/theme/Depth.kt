package com.azurlane.blyy.ui.theme

import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 四层深度模型（设计语言 V2「深海舰队」）。
 *
 * 全 app 表面归入四层，每层绑定不可拆分的光影规格：
 * 禁止跨层混用（例如 L1 面板配 L3 阴影）——组件属于哪层，
 * 全部光影即由 [blyyDepth] 该层规格决定。
 */
enum class DepthLayer(val elevation: Dp) {
    /** L0 海床 — 屏幕背景、AGSL 流体层：无阴影 */
    Sea(0.dp),

    /** L1 甲板 — 内容面板（BlyyPanel / BlyySectionPanel）、卡片 */
    Deck(4.dp),

    /** L2 仪表 — 浮起控件：主按钮、播放器、题面卡、FAB 级圆钮 */
    Instrument(8.dp),

    /** L3 瞭望 — 对话框、BottomSheet、全屏查看器 */
    Lookout(12.dp)
}

/**
 * 统一深度阴影修饰符 — 任意海拔的双色有色阴影（ambient 环境光 + spot 直射光）。
 * 颜色默认取自 [AppColors.Depth]（深海蓝黑，永不纯黑），亮暗模式自动切换。
 *
 * 是全 App 阴影的唯一来源：[blyyDepth] 提供四层语义封装；
 * 需要动态海拔（按压缩放、选中态升降）或语境染色时直接使用本函数。
 *
 * @param spotColor 直射光整色（含透明度）——稀有度染色、主色辉光等语境染色例外，
 * 环境光保持统一，保证卡片阵列不花（如 ShipCard 的稀有度染色阴影）。
 * @param ambientColor 环境光整色——仅局部氛围光效使用（如 VoiceScreen 选中按钮），默认跟随亮暗。
 * @param clip 是否同时按 shape 裁剪，透传给 [shadow]。
 */
@Composable
fun Modifier.blyyDepthShadow(
    elevation: Dp,
    shape: Shape,
    spotColor: Color? = null,
    ambientColor: Color? = null,
    clip: Boolean = true
): Modifier {
    val isDark = LocalIsDark.current
    val ambient = ambientColor ?: if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight
    val spot = spotColor ?: if (isDark) AppColors.Depth.SpotDark else AppColors.Depth.SpotLight
    return this.shadow(
        elevation = elevation,
        shape = shape,
        clip = clip,
        ambientColor = ambient,
        spotColor = spot
    )
}

/**
 * 四层语义封装 — 组件归入 [DepthLayer] 后，全部光影即由该层规格决定，
 * 禁止跨层混用（例如 L1 面板配 L3 阴影）。
 *
 * @param spotTint 稀有度等语境染色例外：仅染直射光（spot，强制 25% 透明度），
 * 环境光保持统一，保证卡片阵列不花。
 */
@Composable
fun Modifier.blyyDepth(
    layer: DepthLayer,
    shape: Shape,
    spotTint: Color? = null
): Modifier {
    if (layer == DepthLayer.Sea) return this
    return blyyDepthShadow(
        elevation = layer.elevation,
        shape = shape,
        spotColor = spotTint?.copy(alpha = 0.25f)
    )
}
