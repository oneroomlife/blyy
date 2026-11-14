package com.azurlane.blyy.util

/**
 * SD 小人渲染帧率的统一取值与「该帧要不要渲染」的纯逻辑。
 *
 * 为什么需要它：SD 小人由 [com.azurlane.blyy.ui.components.SpineSdView] 里的
 * GLSurfaceView 渲染，历史上用 `RENDERMODE_CONTINUOUSLY` 无条件跟随屏幕刷新率出帧。
 * 悬浮窗常驻桌面时这是纯粹的耗电（手机 120Hz 面板即 120 次/秒的骨骼 + 蒙皮计算），
 * 而 SD 待机动画本身只有 24~30fps 的表现力，超出部分完全看不出来。
 *
 * 抽成 object + 无副作用函数，是为了能在 JVM 单测里覆盖阈值判定
 * （见 `ChibiFrameRateTest`），不必起 GL 线程或模拟器。
 */
object ChibiFrameRate {

    /** 省电档：30fps */
    const val FPS_LOW = 30

    /** 默认档：跟随屏幕刷新率（60fps 屏即 60，120Hz 屏也按 60 出帧） */
    const val FPS_DEFAULT = 60

    /** 设置页可选的档位（顺序即展示顺序） */
    val SUPPORTED: List<Int> = listOf(FPS_DEFAULT, FPS_LOW)

    /**
     * 归一化帧率取值：只接受 [SUPPORTED] 里的档位，其它值（含历史脏数据 0 / 负数 / 144）
     * 一律回落到 [FPS_DEFAULT]，避免持久化数据异常把渲染循环卡死。
     */
    fun normalize(rate: Int): Int = if (rate == FPS_LOW) FPS_LOW else FPS_DEFAULT

    /** 目标帧间隔（纳秒）；[normalize] 保证分母非 0 */
    fun intervalNanos(rate: Int): Long = 1_000_000_000L / normalize(rate)

    /**
     * 判断当前 vsync 时刻是否应该出帧。
     *
     * 不能直接写 `elapsed >= interval`：vsync 周期（60Hz = 16.67ms）与 30fps 的目标间隔
     * （33.33ms）不是整数倍关系，严格比较会退化成「隔两帧渲染一次再跳过」的抖动，
     * 表现为 20fps 的顿感。这里留 25% 提前量，让「第 2 个 vsync」稳定命中：
     * 60Hz 下 30fps 每 2 个 vsync 出 1 帧，保持匀速 30fps。
     *
     * @param nowNanos 当前 vsync 时间戳（`Choreographer.FrameCallback#doFrame` 入参）
     * @param lastRenderNanos 上一次出帧的 vsync 时间戳；<=0 表示首帧，必须出
     * @param intervalNanos 目标帧间隔，见 [intervalNanos]
     */
    fun shouldRenderFrame(nowNanos: Long, lastRenderNanos: Long, intervalNanos: Long): Boolean {
        if (intervalNanos <= 0L) return true
        // 首帧（或时间基刚重置）：没有上帧可比，直接出
        if (lastRenderNanos <= 0L) return true
        val elapsed = nowNanos - lastRenderNanos
        // 时钟回跳（极少数机型熄屏/唤醒时 nanoTime 基准变化）：不要卡死渲染
        if (elapsed < 0L) return true
        return elapsed >= intervalNanos - intervalNanos / 4
    }
}
