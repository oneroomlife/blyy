package com.example.blyy.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FloatAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

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
        
        val Emphasized = Easing { f ->
            val x = f * 1.1f
            x * x * (3.5f - 2f * x)
        }
        
        val EmphasizedDecelerate = Easing { f ->
            1f - (1f - f) * (1f - f) * (1f - f)
        }
        
        val EmphasizedAccelerate = Easing { f ->
            f * f * f
        }
        
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
        
        fun <T> press(): AnimationSpec<T> = tween(
            durationMillis = Duration.Instant,
            easing = Easings.Standard
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
            dampingRatio = 0.85f,
            stiffness = 300f
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
