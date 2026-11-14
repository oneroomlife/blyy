package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SD 小人渲染帧率纯逻辑测试（[ChibiFrameRate]，无 Android 依赖）。
 *
 * 覆盖三件事：
 * 1. [ChibiFrameRate.normalize] — 只认 30/60，脏数据回落到默认档
 * 2. [ChibiFrameRate.intervalNanos] — 档位到帧间隔的换算
 * 3. [ChibiFrameRate.shouldRenderFrame] — vsync 节拍判定（含首帧、时钟回跳、30fps 每 2 个 vsync）
 */
class ChibiFrameRateTest {

    // ── normalize ─────────────────────────────────────────────────────────────

    @Test
    fun `30 与 60 原样保留`() {
        assertEquals(30, ChibiFrameRate.normalize(30))
        assertEquals(60, ChibiFrameRate.normalize(60))
    }

    @Test
    fun `非法帧率回落到默认档`() {
        // 历史脏数据 / 手改 DataStore / 未来新增档位都走到这里
        assertEquals(ChibiFrameRate.FPS_DEFAULT, ChibiFrameRate.normalize(0))
        assertEquals(ChibiFrameRate.FPS_DEFAULT, ChibiFrameRate.normalize(-1))
        assertEquals(ChibiFrameRate.FPS_DEFAULT, ChibiFrameRate.normalize(144))
        assertEquals(ChibiFrameRate.FPS_DEFAULT, ChibiFrameRate.normalize(Int.MIN_VALUE))
    }

    // ── intervalNanos ─────────────────────────────────────────────────────────

    @Test
    fun `帧间隔换算`() {
        // 60fps → 16_666_666ns，30fps → 33_333_333ns（整数除法，误差 1ns 内）
        assertEquals(16_666_666L, ChibiFrameRate.intervalNanos(60))
        assertEquals(33_333_333L, ChibiFrameRate.intervalNanos(30))
    }

    @Test
    fun `非法帧率的间隔等同默认档`() {
        assertEquals(ChibiFrameRate.intervalNanos(60), ChibiFrameRate.intervalNanos(999))
    }

    // ── shouldRenderFrame ─────────────────────────────────────────────────────

    @Test
    fun `首帧必须渲染`() {
        // lastRenderNanos = 0 表示时间基重置（刚恢复出帧），不能因为"间隔不足"而跳过
        assertTrue(ChibiFrameRate.shouldRenderFrame(1_000_000_000L, 0L, 16_666_666L))
    }

    @Test
    fun `30fps 档在 60Hz 屏上每两个 vsync 出一帧`() {
        val interval = ChibiFrameRate.intervalNanos(30)
        val vsync = 16_666_666L
        val first = 1_000_000_000L
        // 相邻一个 vsync（16.67ms）不足 33.33ms，跳过
        assertFalse(ChibiFrameRate.shouldRenderFrame(first + vsync, first, interval))
        // 两个 vsync（33.33ms）达标 —— 25% 提前量保证它稳定命中，不会退化成隔三帧
        assertTrue(ChibiFrameRate.shouldRenderFrame(first + vsync * 2, first, interval))
    }

    @Test
    fun `60fps 档在 60Hz 屏上每个 vsync 都出帧`() {
        val interval = ChibiFrameRate.intervalNanos(60)
        val vsync = 16_666_666L
        val first = 1_000_000_000L
        assertTrue(ChibiFrameRate.shouldRenderFrame(first + vsync, first, interval))
    }

    @Test
    fun `提前量不会让 30fps 变成 60fps`() {
        // 阈值是 interval - interval/4 = 25ms，半个 vsync（8.3ms）必须仍然跳过
        val interval = ChibiFrameRate.intervalNanos(30)
        val first = 1_000_000_000L
        assertFalse(ChibiFrameRate.shouldRenderFrame(first + 8_333_333L, first, interval))
    }

    @Test
    fun `时钟回跳时立即出帧而不是卡死`() {
        // 熄屏/唤醒时 nanoTime 基准可能变化，elapsed 为负不能导致永久不渲染
        assertTrue(ChibiFrameRate.shouldRenderFrame(500L, 1_000_000_000L, 16_666_666L))
    }

    @Test
    fun `非法间隔按必须渲染处理`() {
        assertTrue(ChibiFrameRate.shouldRenderFrame(2_000L, 1_000L, 0L))
        assertTrue(ChibiFrameRate.shouldRenderFrame(2_000L, 1_000L, -1L))
    }

    @Test
    fun `支持档位只包含 30 与 60`() {
        assertEquals(listOf(60, 30), ChibiFrameRate.SUPPORTED)
    }
}
