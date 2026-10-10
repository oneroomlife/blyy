package com.azurlane.blyy.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object AppAnimation {
    
    object Duration {
        const val Instant = 100
        const val Fast = 200
        const val Normal = 350
        const val Slow = 500
        const val VerySlow = 800
        const val PageTransition = 450
        const val StaggerDelay = 40
    }
    
    object Easings {
        val Standard = FastOutSlowInEasing

        /** Material LinearOutSlowIn — 元素进入时减速 */
        val DecelerateIn = LinearOutSlowInEasing

        /** Material FastOutLinearIn — 元素退出时加速 */
        val AccelerateOut = FastOutLinearInEasing

        /** EaseInOutSine — 平滑的正弦进出，用于呼吸/光晕等循环动画 */
        val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

        // ── Material 3 规范缓动（官方三次贝塞尔，替代旧多项式近似）──
        // Emphasized: 标准强调曲线 — 快出缓入，用于常规状态过渡
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        // EmphasizedDecelerate: 强调进入 — 高速起步、长尾减速，元素入场
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        // EmphasizedAccelerate: 强调退出 — 缓慢起步、加速离场，元素退场
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        
        val Decelerate = Easing { f -> 1f - (1f - f) * (1f - f) }
        
        val Accelerate = Easing { f -> f * f }
        
        val Linear = LinearEasing
        
        val Bounce = Easing { f ->
            if (f < 0.5f) {
                4f * f * f * f
            } else {
                1f - (-2f * f + 2f) * (-2f * f + 2f) * (-2f * f + 2f) / 2f
            }
        }
        
        val Smooth = Easing { f ->
            val t = f * 2f
            when {
                t < 1f -> 0.5f * t * t * t
                else -> 0.5f * ((t - 2f) * (t - 2f) * (t - 2f) + 2f)
            }
        }
        
        val Anticipate = Easing { f ->
            val tension = 2f
            (f + 1f) * (f + 1f) * ((tension + 1f) * f - tension) / (tension * tension)
        }
        
        val Overshoot = Easing { f ->
            val tension = 2f
            (f - 1f) * (f - 1f) * ((tension + 1f) * (f - 1f) + tension) + 1f
        }
    }
    
    object Springs {
        val Standard = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        val Bouncy = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
        
        val Stiff = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
        
        val Soft = spring<Float>(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessVeryLow
        )
        
        val Responsive = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
        
        val Gentle = spring<Float>(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow
        )
        
        val Snappy = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    }
    
    // ── 统一按压反馈规范 ──
    object Press {
        /** 轻按 — Chip、小按钮、标签 */
        const val LightScale = 0.96f
        /** 标准 — 卡片、列表项 */
        const val StandardScale = 0.97f
        /** 强按 — 主操作按钮、FAB */
        const val HeavyScale = 0.94f

        fun <T> light(): AnimationSpec<T> = spring(
            dampingRatio = 0.75f,
            stiffness = 500f
        )
        fun <T> standard(): AnimationSpec<T> = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
        fun <T> heavy(): AnimationSpec<T> = spring(
            dampingRatio = 0.65f,
            stiffness = 350f
        )
    }
    
    object Specs {
        fun <T> fast(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.Standard
        )
        
        fun <T> normal(): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            easing = Easings.Standard
        )
        
        fun <T> slow(): AnimationSpec<T> = tween(
            durationMillis = Duration.Slow,
            easing = Easings.Standard
        )
        
        fun <T> staggered(index: Int, duration: Int = Duration.Normal): AnimationSpec<T> = tween(
            durationMillis = duration,
            delayMillis = index * Duration.StaggerDelay,
            easing = Easings.Emphasized
        )
        
        fun <T> fadeIn(): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            easing = Easings.EmphasizedDecelerate
        )
        
        fun <T> fadeOut(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.EmphasizedAccelerate
        )
        
        fun <T> scale(): AnimationSpec<T> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> press(): AnimationSpec<T> = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
        
        fun <T> slideIn(): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            easing = Easings.EmphasizedDecelerate
        )
        
        fun <T> slideOut(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.EmphasizedAccelerate
        )
        
        fun <T> expand(): AnimationSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> collapse(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.Standard
        )
    }
    
    object Repeating {
        fun breathing(duration: Int = 2500) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = Easings.Standard),
            repeatMode = RepeatMode.Reverse
        )
        
        fun pulse(duration: Int = 1500) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = Easings.Standard),
            repeatMode = RepeatMode.Restart
        )
        
        fun rotate(duration: Int = 8000) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
        
        fun glow(duration: Int = 2000) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = Easings.Standard),
            repeatMode = RepeatMode.Reverse
        )
        
        fun shimmer(duration: Int = 3000) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
        
        fun float(duration: Int = 3500) = infiniteRepeatable<Float>(
            animation = tween(durationMillis = duration, easing = Easings.Standard),
            repeatMode = RepeatMode.Reverse
        )
    }
    
    object Interaction {
        const val PressScale = 0.97f
        const val HoverScale = 1.02f
        const val FocusScale = 1.01f
        
        const val MinAlpha = 0.3f
        const val DisabledAlpha = 0.38f
        const val HoverAlpha = 0.08f
        const val FocusAlpha = 0.12f
        const val PressAlpha = 0.12f
    }
    
    object Card {
        fun <T> enter(index: Int): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            delayMillis = index * Duration.StaggerDelay,
            easing = Easings.Emphasized
        )
        
        fun <T> press(): AnimationSpec<T> = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
        
        fun <T> hover(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.Standard
        )
    }
    
    object Page {
        fun <T> transition(): AnimationSpec<T> = tween(
            durationMillis = Duration.PageTransition,
            easing = Easings.Standard
        )
        
        fun <T> enter(): AnimationSpec<T> = tween(
            durationMillis = Duration.PageTransition,
            easing = Easings.EmphasizedDecelerate
        )
        
        fun <T> exit(): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            easing = Easings.EmphasizedAccelerate
        )
    }
    
    object Effect {
        const val ShimmerDuration = 3000
        const val GlowDuration = 2500
        const val ParticleDuration = 1500
        const val BadgeRotationDuration = 8000
        const val RippleDuration = 400
        const val TooltipDuration = 200
    }
    
    object Component {
        fun <T> button(): AnimationSpec<T> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> chip(): AnimationSpec<T> = tween(
            durationMillis = Duration.Fast,
            easing = Easings.Standard
        )
        
        fun <T> dialog(): AnimationSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> bottomSheet(): AnimationSpec<T> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> snackbar(): AnimationSpec<T> = tween(
            durationMillis = Duration.Normal,
            easing = Easings.EmphasizedDecelerate
        )
    }
}

/**
 * 系统减动效检测 — 开发者选项"动画时长缩放"为 0（或无障碍"移除动画"）时返回 true。
 * V2 规范：装饰性动效（stagger 入场、脉冲、流光）在减动效模式下应跳过或退化为同时入场。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}
