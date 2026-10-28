package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 统一匹配引擎 [ShipNameMatcher] 的针对性回归。
 *
 * 用例全部来自真实资源：船坞头像（`blhx_avatar`）、SD 目录（`blhx_sd`）、
 * 游戏资源导入 id（`2b_2_hx` 这类带分层/和谐后缀的命名）。
 * 与 `AvatarMatcherTest`（1004 舰全量语料）互补：这里锁定**单点语义**，
 * 改坏任何一条都能立刻知道是哪个策略退化。
 */
class ShipNameMatcherTest {

    private val avatarIndex = mapOf(
        "boge" to "boge.webp",
        "z23" to "Z23.webp",
        "z23_h" to "Z23_h.webp",
        "changchun" to "changchun.webp",
        "changchun_g" to "changchun_g.webp",
        "dafeng" to "dafeng.webp",
        "dafeng_alter" to "dafeng_alter.webp",
        "chicheng" to "chicheng.webp",
        "chicheng_idol" to "chicheng_idol.webp",
        "xiaochicheng" to "xiaochicheng.webp",
        "xia" to "xia.webp",
        "xia_doa" to "xia_doa.webp",
        "tiancheng" to "tiancheng.webp",
        "tiancheng_cv" to "tiancheng_cv.webp",
        "dahuangfeng" to "dahuangfeng.webp",
        "dahuangfengii" to "dahuangfengII.webp",
        "wuerlixi" to "wuerlixi.webp",
        "abuluqi" to "abuluqi.webp",
        "qinli" to "qinli.webp",
        "huixing" to "huixing.webp",
        "juejiang" to "juejiang.webp",
        "hdn401" to "HDN401.webp",
        "peineiluopo" to "peineiluopo.webp",
        "hdn101" to "HDN101.webp",
        "lala_tolove" to "lala_tolove.webp",
        "mengmeng_tolove" to "mengmeng_tolove.webp",
        "midchicheng_alter" to "midchicheng_alter.webp"
    )

    private fun match(name: String): String? = ShipNameMatcher.match(name, avatarIndex.keys)?.key

    @Test
    fun `精确与全拼`() {
        assertEquals("boge", match("博格"))
        assertEquals("boge", match("boge"))
        assertEquals("z23", match("Z23"))
        assertEquals("dahuangfengii", match("大黄蜂II"))
    }

    @Test
    fun `别名表带大小写的条目按小写命中`() {
        // 贝露 = 海王星联动 HDN401（不是皇家舰"佩内洛珀"）
        assertEquals("hdn401", match("贝露"))
        // 绀紫之心 → HDN102_1，不能再撞 HDN101（涅普顿）
        assertEquals("hdn102_1", ShipNameMatcher.match("绀紫之心", setOf("hdn101", "hdn102_1"))?.key)
    }

    @Test
    fun `变体优先且不回退`() {
        // 改造：有 _g 用 _g，没有必须失配（绝不显示基础形态）
        assertEquals("changchun_g", match("长春.改"))
        assertNull(ShipNameMatcher.match("拉菲.改", setOf("lafei"))?.key)
        // META：有 _alter 用 _alter；缺失时拒绝回退
        assertEquals("dafeng_alter", match("大凤·META"))
        assertNull(ShipNameMatcher.match("萨拉托加·META", setOf("salatuojia")))
        // μ兵装
        assertEquals("chicheng_idol", match("赤城(μ兵装)"))
        // 幼女：优先 _younv，缺失时回退原名拼音（小天鹅本身是舰名）
        assertEquals("xiaochicheng", match("小赤城"))
        assertEquals("xiaotiane", ShipNameMatcher.match("小天鹅", setOf("xiaotiane"))?.key)
        // "小天鹅" 的全拼 xiaotiane 音节对齐包含 tiane（天鹅）——历史行为保留：
        // 没有小天鹅专属资源时允许落到"天鹅"，由调用方决定是否可接受
    }

    @Test
    fun `尾部联动标记`() {
        assertEquals("xia_doa", match("霞(DOA)"))
        assertEquals("xia_doa", match("霞DOA"))
        assertEquals("tiancheng_cv", match("天城CV"))
        // 默认形态不吃联动/皮肤文件
        assertEquals("xia", match("霞"))
    }

    @Test
    fun `前缀与子串必须音节对齐`() {
        // 缩写命名：目录名是全拼前缀
        assertEquals("wuerlixi", match("乌尔里希·冯·胡滕"))
        assertEquals("abuluqi", match("阿布鲁齐公爵"))
        // 联动舰：目录名是全拼的音节对齐子串
        assertEquals("qinli", match("五河琴里"))
        // 跨字拼接误配闸门："水星号" ≠ "彗星"
        assertNull(ShipNameMatcher.match("水星号", setOf("huixing")))
    }

    @Test
    fun `拼写容错`() {
        assertEquals("juejiang", match("倔强"))
        // pinyin4j 若把"倔强"转成 jueqiang，容错也要兜住
        assertEquals(
            "juejiang",
            ShipNameMatcher.match("倔强", setOf("juejiang"), ShipNameMatcher.Options(allowTypo = true))?.key
        )
    }

    @Test
    fun `换装序号剥到基名`() {
        val parts = ShipNameMatcher.keyParts("2b_2_hx")
        assertEquals("2b", parts.core)
        assertEquals("hx", parts.suffixTokens.last())
        val withSkin = ShipNameMatcher.keyParts("lafei_12")
        assertEquals("lafei", withSkin.core)
    }

    @Test
    fun `查不到返回 null`() {
        assertNull(match("不存在的舰娘"))
        assertNull(match(""))
    }

    @Test
    fun `誓约匹配`() {
        assertEquals("Z23_h.webp", ShipNameMatcher.matchOath("Z23", avatarIndex.keys)?.let { avatarIndex[it] })
        // 改造舰誓约仍显示改造头像
        assertEquals(
            "changchun_g.webp",
            ShipNameMatcher.matchOath("长春.改", avatarIndex.keys)?.let { avatarIndex[it] }
        )
        // META 无婚皮，保持 META
        assertEquals(
            "dafeng_alter.webp",
            ShipNameMatcher.matchOath("大凤·META", avatarIndex.keys)?.let { avatarIndex[it] }
        )
    }

    @Test
    fun `SD 专用别名不污染头像域`() {
        val sdKeys = setOf("molisen", "boge")
        // 头像域：哈里森不能撞莫里森
        assertNull(ShipNameMatcher.match("哈里森", avatarIndex.keys))
        // SD 域：哈里森/博格 都指向 molisen/boge 目录
        assertEquals(
            "molisen",
            ShipNameMatcher.match("哈里森", sdKeys, ShipNameMatcher.Options(aliases = ShipNameMatcher.SD_EXTRA_ALIASES))?.key
        )
        assertEquals(
            "boge",
            ShipNameMatcher.match("博格", sdKeys, ShipNameMatcher.Options(aliases = ShipNameMatcher.SD_EXTRA_ALIASES))?.key
        )
    }
}
