package com.azurlane.blyy.domain

import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 生日日历纯计算核心（无 Android 依赖，可 JVM 单测覆盖）。
 *
 * 日期约定：
 *  - Room ships.birthday 存 "MM-DD" 零填充格式（静态名单 blhx_birthdays.json 同源）
 *  - 2 月 29 日生日在平年钳制到 2 月 28 日展示与计算（"月末庆祝"惯例）
 */
object BirthdayCalendar {

    /** 解析 "MM-DD" 生日；空白/格式错误/越界日期返回 null（调用方按无生日处理） */
    fun parse(raw: String): MonthDay? {
        val trimmed = raw.trim()
        if (trimmed.length != 5 || trimmed[2] != '-') return null
        val month = trimmed.substring(0, 2).toIntOrNull() ?: return null
        val day = trimmed.substring(3, 5).toIntOrNull() ?: return null
        return runCatching { MonthDay.of(month, day) }.getOrNull()
    }

    /**
     * 生日在 [month] 中的"展示日"：生日不属于该月返回 null；
     * 2/29 生日在平年返回 28（钳制到月末）。
     */
    fun dayInMonth(birthday: MonthDay, month: YearMonth): Int? {
        if (birthday.monthValue != month.monthValue) return null
        return minOf(birthday.dayOfMonth, month.lengthOfMonth())
    }

    /** 下一个生日日期（>= [today]）；2/29 生日平年取 2/28 */
    fun nextOccurrence(birthday: MonthDay, today: LocalDate): LocalDate {
        val thisYear = clamped(birthday, today.year)
        if (!thisYear.isBefore(today)) return thisYear
        return clamped(birthday, today.year + 1)
    }

    /** 距下一个生日的天数（0 = 今天） */
    fun daysUntil(birthday: MonthDay, today: LocalDate): Int =
        ChronoUnit.DAYS.between(today, nextOccurrence(birthday, today)).toInt()

    private fun clamped(birthday: MonthDay, year: Int): LocalDate {
        val len = YearMonth.of(year, birthday.monthValue).lengthOfMonth()
        return LocalDate.of(year, birthday.monthValue, minOf(birthday.dayOfMonth, len))
    }
}
