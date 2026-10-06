package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音表 ↔ 皮肤对齐规则回归测试。
 *
 * 用例全部来自真实 wiki 页面的实测表序（2026-10 抓取）：
 *  - Z23：默认装扮拆成两张未命名表 + 首表无 data-title（复利错位回归用例）
 *  - 埃尔德里奇：首表带正式皮肤名（圣夜的拥抱即默认装扮）
 *  - 阿达尔伯特亲王：首表即"默认装扮"，其后为实名换装表
 *  - 誓约/改造表穿插在页序中间时不占用换装序号
 */
class SkinVoiceIndexTest {

    @Test
    fun `Z23 - 双未命名表都归默认装扮，换装1 不再重复默认装扮`() {
        val titles = listOf(
            "默认装扮", "默认装扮", "哲学讲师", "Z23.改", "标准笑容？", "宴会上的优等生",
            "正经偶像·经纪人担当？", "书架边的“风景”？", "新设基地·茶店体验", "非正式沙滩排球赛",
            "【誓约】黑曜的嫁衣", "秘密的起居室", "薯条，以及笑容！", "强化失败？！",
            "舞会与黑蔷薇", "案上墨戏"
        )
        val tables = SkinVoiceIndex.classify(titles)
        assertEquals(listOf("哲学讲师", "标准笑容？", "宴会上的优等生"), tables.skinSequence.take(3))
        assertEquals(12, tables.skinSequence.size)
        assertEquals("案上墨戏", tables.skinSequence.last())
        assertTrue("默认装扮" in tables.defaultNames)
        assertTrue(tables.remodelNames == listOf("Z23.改"))
        assertTrue(tables.oathNames == listOf("【誓约】黑曜的嫁衣"))
    }

    @Test
    fun `埃尔德里奇 - 未命名表归默认装扮，圣夜的拥抱是换装1`() {
        val titles = listOf(
            "默认装扮", "默认装扮", "圣夜的拥抱", "空教室的不可思议", "正月的牵手", "喵喵偶像团？",
            "太空中秋节", "美好的放学时刻", "【誓约】相约于林荫暖阳", "埃尔德里奇.改", "金月桂香"
        )
        val tables = SkinVoiceIndex.classify(titles)
        // 换装序列从圣夜的拥抱（换装1）开始：资源 aierdeliqi_4 = 换装3 = 正月的牵手
        assertEquals("圣夜的拥抱", tables.skinSequence[0])
        assertEquals("正月的牵手", tables.skinSequence[2])
        assertEquals("金月桂香", tables.skinSequence.last())
        assertEquals(setOf("默认装扮"), tables.defaultNames)
        assertTrue(tables.remodelNames == listOf("埃尔德里奇.改"))
        assertTrue(tables.oathNames == listOf("【誓约】相约于林荫暖阳"))
    }

    @Test
    fun `阿达尔伯特亲王 - 常规页结构`() {
        val titles = listOf("默认装扮", "闭店后的特别时光", "浴室中的小小意外", "黑与白的魔术师")
        val tables = SkinVoiceIndex.classify(titles)
        assertEquals(listOf("闭店后的特别时光", "浴室中的小小意外", "黑与白的魔术师"), tables.skinSequence)
        assertTrue("默认装扮" in tables.defaultNames)
    }

    @Test
    fun `空表序列返回空分类`() {
        val tables = SkinVoiceIndex.classify(emptyList())
        assertTrue(tables.defaultNames.isEmpty())
        assertTrue(tables.skinSequence.isEmpty())
    }

    @Test
    fun `逐行台词输入 - 连续同名折叠为单表，埃尔德里奇换装4命中正月的牵手`() {
        // 模拟 Live2dViewerViewModel 传入的"每行台词 skinName"序列（多条/表）
        val lineSkinNames = listOf(
            "圣夜的拥抱", "圣夜的拥抱", "圣夜的拥抱",
            "空教室的不可思议", "空教室的不可思议",
            "正月的牵手", "正月的牵手", "正月的牵手",
            "喵喵偶像团？",
            "太空中秋节", "太空中秋节",
            "【誓约】相约于林荫暖阳", "【誓约】相约于林荫暖阳",
            "埃尔德里奇.改",
            "金月桂香", "金月桂香", "金月桂香", "金月桂香"
        )
        val tables = SkinVoiceIndex.classify(lineSkinNames)
        assertEquals("正月的牵手", tables.skinSequence[2])
        assertEquals(6, tables.skinSequence.size)
        assertEquals("圣夜的拥抱", tables.skinSequence[0])
        assertEquals("金月桂香", tables.skinSequence.last())
        assertEquals(setOf("默认装扮"), tables.defaultNames)
    }
}
