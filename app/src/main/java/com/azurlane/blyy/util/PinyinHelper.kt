package com.azurlane.blyy.util

import net.sourceforge.pinyin4j.PinyinHelper as P4jHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination

/**
 * 中文→无声调拼音转换工具。
 *
 * 用于将舰娘中文名（如"博格"）转换为无声调全拼（如"boge"），
 * 与用户导入的 SD 资源目录名（拼音命名）匹配。
 *
 * 基于 pinyin4j（纯 Java 实现，无 native 依赖，~1.9MB）。
 * 多音字取首个读音，非中文字符保留原样并转小写。
 */
object PinyinHelper {

    private val format = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /**
     * 将中文文本转换为无声调全拼小写字符串。
     *
     * 例：
     *   "博格" → "boge"
     *   "拉菲" → "lafei"
     *   "Z23" → "z23"
     *   "U-556·META" → "u556meta"
     *   "标枪" → "biaoqiang"
     *
     * 非中文字符（字母/数字）转小写保留，特殊符号（·★-()等）直接丢弃。
     */
    fun toPinyin(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fff' -> {
                    try {
                        val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                        if (!pinyin.isNullOrEmpty()) {
                            sb.append(pinyin[0])
                        }
                    } catch (_: BadHanyuPinyinOutputFormatCombination) {
                        // 多音字转换异常，跳过该字符
                    }
                }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                // 特殊符号（·★-()等）丢弃
            }
        }
        return sb.toString()
    }

    /**
     * 将中文文本转换为拼音首字母缩写小写字符串。
     *
     * 中文字符取拼音首字母，英文字母/数字保留原样转小写，特殊符号丢弃。
     * 例：
     *   "乌尔里希冯胡滕" → "welxfht"
     *   "博格" → "bg"
     *   "Z23" → "z23"
     *
     * 用于 SD 资源匹配的备用策略，覆盖资源目录使用缩写命名的情况。
     */
    fun toPinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fff' -> {
                    try {
                        val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                        if (!pinyin.isNullOrEmpty()) {
                            sb.append(pinyin[0].first())
                        }
                    } catch (_: BadHanyuPinyinOutputFormatCombination) {
                        // 多音字转换异常，跳过该字符
                    }
                }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                // 特殊符号（·★-()等）丢弃
            }
        }
        return sb.toString()
    }

    /**
     * 将中文文本转换为逐字拼音列表。
     *
     * 每个中文字符输出对应的完整拼音，非中文字符（字母/数字）原样转小写输出。
     * 用于模糊匹配：资源目录名可能是舰娘全拼的前缀（如 "wuerlixi" 是 "wuerlixifenghutemg" 的前缀），
     * 逐字拼音列表支持按字符分组做前缀匹配。
     *
     * 例：
     *   "乌尔里希冯胡滕" → ["wuer", "li", "xi", "feng", "hu", "ten"]
     *   "Z23" → ["z23"]
     */
    fun toPinyinSegments(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val segments = ArrayList<String>(text.length)
        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fff' -> {
                    try {
                        val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                        if (!pinyin.isNullOrEmpty()) {
                            segments.add(pinyin[0])
                        }
                    } catch (_: BadHanyuPinyinOutputFormatCombination) {
                        // 多音字转换异常，跳过该字符
                    }
                }
                ch.isLetterOrDigit() -> segments.add(ch.lowercaseChar().toString())
                // 特殊符号（·★-()等）丢弃
            }
        }
        return segments
    }
}
