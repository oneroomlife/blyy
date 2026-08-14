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
 *
 * 多音字覆盖：pinyin4j 对部分多音字取"最常见读音"，但舰娘名中
 * 应读另一读音（如"阿"在"阿尔萨斯"中读 a 而非 e）。
 * [PINYIN_OVERRIDES] 优先级高于 pinyin4j 默认读音。
 */
object PinyinHelper {

    private val format = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /**
     * 舰娘名专用多音字覆盖表（参考 win 项目 pinyin-overrides.js）。
     *
     * pinyin4j 通用表对多音字取"最常见读音"，但部分多音字在舰娘名中
     * 应读另一读音，导致生成的拼音与游戏资源目录名不一致。
     *
     * 典型案例：
     *   "阿" 通用表可能取 "e"（东阿），但 "阿尔萨斯"/"阿贾克斯"/"阿瑞托莎"
     *      等舰娘名中应读 "a" → 目录名 aersasi / ajiakeesi / aruituisha
     *
     * 维护：发现新的多音字误读时，在此追加即可。
     * 键为单个汉字，值为小写无声调拼音。
     */
    private val PINYIN_OVERRIDES: Map<Char, String> = mapOf(
        // 阿: 舰娘名中绝大多数读 "a"（阿尔萨斯/阿贝克隆比/阿瑞托莎/阿贾克斯/阿武隈/阿贺野）
        '阿' to "a",
        // 重: pinyin4j 默认取 "zhong"，但"重庆"舰娘应读 "chong"
        '重' to "chong",
        // 亲: pinyin4j 可能取 "qing"，但"亲潮"舰娘应读 "qin"
        '亲' to "qin",
        // 塞: pinyin4j 可能取 "se"，但"塞德利茨"舰娘应读 "sai"
        '塞' to "sai",
        // 长: pinyin4j 默认取 "zhang"，但"长门/长岛/长春/长波/长良/长月/长风"等舰娘应读 "chang"
        '长' to "chang",
        // 女: pinyin4j 输出 "nu:"（带冒号），舰娘名"女将/命运女神/圣女贞德/司战女神"等应读 "nv"
        '女' to "nv",
        // 吕/律/绿: pinyin4j 输出 "lu:"（带冒号），舰娘名"吕佐夫/布吕歇尔/卡律布狄斯/翡绿之心/秋月律子"等应读 "lv"
        '吕' to "lv",
        '律' to "lv",
        '绿' to "lv",
        // 那: pinyin4j 默认取 "nei"，但"亚利桑那/那不勒斯/那珂/那智"等舰娘应读 "na"
        '那' to "na",
        // 什: pinyin4j 默认取 "shen"，但"塔什干/什罗普郡"等舰娘应读 "shi"
        '什' to "shi",
        // 的: pinyin4j 默认取 "de"，但"巴尔的摩"舰娘应读 "di"
        '的' to "di",
        // 贲: pinyin4j 默认取 "bi"，但"虎贲"舰娘应读 "ben"
        '贲' to "ben",
        // 汀: pinyin4j 可能取 "ting"，但"埃米尔·贝尔汀"舰娘应读 "ding"（资源文件 aimierbeierding）
        '汀' to "ding",
        // 杓: pinyin4j 默认取 "shao"，但"杓鹬"舰娘应读 "biao"（资源文件 biaoyu）
        '杓' to "biao",
        // 糸: pinyin4j 可能取 "mi"，但"四糸乃"舰娘应读 "si"（资源文件 sisinai）
        '糸' to "si",
        // 朝: pinyin4j 默认取 "chao"，但"朝潮/朝凪"等日文舰名应读 "zhao"（资源文件 zhaochao/zhaozhi）
        '朝' to "zhao"
    )

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
                    // 多音字覆盖表优先（舰娘名场景专用读音）
                    val override = PINYIN_OVERRIDES[ch]
                    if (override != null) {
                        sb.append(override)
                    } else {
                        try {
                            val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                            if (!pinyin.isNullOrEmpty()) {
                                sb.append(pinyin[0])
                            }
                        } catch (_: BadHanyuPinyinOutputFormatCombination) {
                            // 多音字转换异常，跳过该字符
                        }
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
                    // 多音字覆盖表优先
                    val override = PINYIN_OVERRIDES[ch]
                    if (override != null) {
                        sb.append(override.first())
                    } else {
                        try {
                            val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                            if (!pinyin.isNullOrEmpty()) {
                                sb.append(pinyin[0].first())
                            }
                        } catch (_: BadHanyuPinyinOutputFormatCombination) {
                            // 多音字转换异常，跳过该字符
                        }
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
                    // 多音字覆盖表优先
                    val override = PINYIN_OVERRIDES[ch]
                    if (override != null) {
                        segments.add(override)
                    } else {
                        try {
                            val pinyin = P4jHelper.toHanyuPinyinStringArray(ch, format)
                            if (!pinyin.isNullOrEmpty()) {
                                segments.add(pinyin[0])
                            }
                        } catch (_: BadHanyuPinyinOutputFormatCombination) {
                            // 多音字转换异常，跳过该字符
                        }
                    }
                }
                ch.isLetterOrDigit() -> segments.add(ch.lowercaseChar().toString())
                // 特殊符号（·★-()等）丢弃
            }
        }
        return segments
    }
}
