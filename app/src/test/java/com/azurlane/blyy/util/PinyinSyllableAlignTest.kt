package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音音节边界对齐闸门测试。
 *
 * 背景：模糊子串匹配此前只做 `pinyin.contains(candidate)` + 长度阈值，
 * 跨字拼音拼接会偶然构成候选串——典型案例 "水星号"(shui|xing|hao)
 * 在字符层面包含 "彗星"(huixing)，导致船坞立绘错误加载成彗星。
 * isSyllableAligned 要求候选出现位置落在逐字拼音的音节边界上。
 */
class PinyinSyllableAlignTest {

    @Test
    fun `水星号 - huixing 是跨字拼接,不对齐`() {
        val pinyin = PinyinHelper.toPinyin("水星号")
        assertEquals("shuixinghao", pinyin)
        assertFalse(PinyinHelper.isSyllableAligned("水星号", pinyin, "huixing"))
    }

    @Test
    fun `水星号 - shuixing 全字对齐,放行`() {
        val pinyin = PinyinHelper.toPinyin("水星号")
        assertTrue(PinyinHelper.isSyllableAligned("水星号", pinyin, "shuixing"))
        assertTrue(PinyinHelper.isSyllableAligned("水星号", pinyin, "shui"))
        assertTrue(PinyinHelper.isSyllableAligned("水星号", pinyin, "shuixinghao"))
    }

    @Test
    fun `五河琴里 - qinli 是尾部两字对齐子串,放行`() {
        // 联动舰娘"五河琴里"资源目录 qinli,是逐字分段(wuhe|qin|li)的后两段拼接
        val pinyin = PinyinHelper.toPinyin("五河琴里")
        assertEquals("wuheqinli", pinyin)
        assertTrue(PinyinHelper.isSyllableAligned("五河琴里", pinyin, "qinli"))
    }

    @Test
    fun `朱利奥凯撒 - kaisa 是对齐子串,放行`() {
        // META 资源 kaisa_alter ← "朱利奥·凯撒"(zhuliao|kaisa),后两字对齐
        val pinyin = PinyinHelper.toPinyin("朱利奥·凯撒")
        assertEquals("zhuliaokaisa", pinyin)
        assertTrue(PinyinHelper.isSyllableAligned("朱利奥·凯撒", pinyin, "kaisa"))
    }

    @Test
    fun `候选等于全拼或空串 - 直接放行`() {
        val pinyin = PinyinHelper.toPinyin("博格")
        assertTrue(PinyinHelper.isSyllableAligned("博格", pinyin, "boge"))
        assertFalse(PinyinHelper.isSyllableAligned("博格", pinyin, ""))
        // 候选长于全拼:不算对齐
        assertFalse(PinyinHelper.isSyllableAligned("博格", pinyin, "bogexx"))
    }

    @Test
    fun `同音节多次出现 - 任意一次对齐即放行`() {
        // "lalala" 式重复:la|la|la 中 "la" 在多个边界出现
        val pinyin = PinyinHelper.toPinyin("啦啦啦")
        assertEquals("lalala", pinyin)
        assertTrue(PinyinHelper.isSyllableAligned("啦啦啦", pinyin, "la"))
        assertTrue(PinyinHelper.isSyllableAligned("啦啦啦", pinyin, "lalala"))
    }

    @Test
    fun `前缀终点边界 - 整字缩写对齐,音节中间截断拒绝`() {
        // "阿布鲁齐公爵"(a|bu|lu|qi|gong|jue) 边界偏移:0,1,3,5,7,11,14
        // abuluqi(偏移7)= 阿+布+鲁+齐 整字缩写,对齐;
        // abuluq(偏移6) 截断在 "齐"(qi) 的中间,不是整字缩写,拒绝
        val pinyin = PinyinHelper.toPinyin("阿布鲁齐公爵")
        assertEquals("abuluqigongjue", pinyin)
        assertTrue(PinyinHelper.isSyllableBoundaryAt("阿布鲁齐公爵", pinyin, 7))
        assertFalse(PinyinHelper.isSyllableBoundaryAt("阿布鲁齐公爵", pinyin, 6))
        assertFalse(PinyinHelper.isSyllableBoundaryAt("阿布鲁齐公爵", pinyin, 4))
        // 边界偏移:0 与全长天然放行
        assertTrue(PinyinHelper.isSyllableBoundaryAt("阿布鲁齐公爵", pinyin, 0))
        assertTrue(PinyinHelper.isSyllableBoundaryAt("阿布鲁齐公爵", pinyin, pinyin.length))
    }
}
