package com.azurlane.blyy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

/**
 * [BirthdayCalendar] 纯逻辑单测：格式解析、跨月归属、2/29 平年钳制、跨年倒计时。
 */
class BirthdayCalendarTest {

    // ── parse ──

    @Test
    fun `parse accepts zero padded MM-DD`() {
        assertEquals(MonthDay.of(7, 2), BirthdayCalendar.parse("07-02"))
        assertEquals(MonthDay.of(12, 31), BirthdayCalendar.parse("12-31"))
    }

    @Test
    fun `parse rejects malformed input`() {
        assertNull(BirthdayCalendar.parse(""))
        assertNull(BirthdayCalendar.parse("7-2"))
        assertNull(BirthdayCalendar.parse("07/02"))
        assertNull(BirthdayCalendar.parse("13-01"))   // 月越界
        assertNull(BirthdayCalendar.parse("02-30"))   // 日越界（2 月无 30 日）
        assertNull(BirthdayCalendar.parse("xx-yy"))
    }

    // ── dayInMonth ──

    @Test
    fun `dayInMonth returns null for other months`() {
        val bd = MonthDay.of(3, 17)
        assertNull(BirthdayCalendar.dayInMonth(bd, YearMonth.of(2026, 2)))
        assertNull(BirthdayCalendar.dayInMonth(bd, YearMonth.of(2026, 4)))
    }

    @Test
    fun `dayInMonth maps birthday to its day`() {
        val bd = MonthDay.of(3, 17)
        assertEquals(17, BirthdayCalendar.dayInMonth(bd, YearMonth.of(2026, 3)))
    }

    @Test
    fun `leap day clamps to feb28 in common years and stays 29 in leap years`() {
        val bd = MonthDay.of(2, 29)
        assertEquals(28, BirthdayCalendar.dayInMonth(bd, YearMonth.of(2027, 2)))
        assertEquals(29, BirthdayCalendar.dayInMonth(bd, YearMonth.of(2028, 2)))
    }

    // ── nextOccurrence / daysUntil ──

    @Test
    fun `next occurrence is today when birthday is today`() {
        val today = LocalDate.of(2026, 10, 30)
        assertEquals(today, BirthdayCalendar.nextOccurrence(MonthDay.of(10, 30), today))
        assertEquals(0, BirthdayCalendar.daysUntil(MonthDay.of(10, 30), today))
    }

    @Test
    fun `next occurrence stays within this year when not passed`() {
        val today = LocalDate.of(2026, 10, 26)
        assertEquals(LocalDate.of(2026, 12, 7), BirthdayCalendar.nextOccurrence(MonthDay.of(12, 7), today))
        assertEquals(42, BirthdayCalendar.daysUntil(MonthDay.of(12, 7), today))
    }

    @Test
    fun `next occurrence rolls to next year when passed`() {
        val today = LocalDate.of(2026, 10, 26)
        assertEquals(LocalDate.of(2027, 2, 14), BirthdayCalendar.nextOccurrence(MonthDay.of(2, 14), today))
    }

    @Test
    fun `leap day birthday counts as feb28 in common year and feb29 in leap year`() {
        val today = LocalDate.of(2026, 10, 26)
        // 平年：今年的 2/29 → 2/28 已过 → 明年（2027，平年）2/28
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayCalendar.nextOccurrence(MonthDay.of(2, 29), today))
        // 闰年当年未过：取 2028-02-29 本尊
        val in2027 = LocalDate.of(2027, 10, 26)
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayCalendar.nextOccurrence(MonthDay.of(2, 29), in2027))
    }

    @Test
    fun `december birthday counts across year boundary`() {
        val today = LocalDate.of(2026, 12, 25)
        assertEquals(4, BirthdayCalendar.daysUntil(MonthDay.of(12, 29), today))
    }
}
