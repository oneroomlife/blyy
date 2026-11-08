package com.azurlane.blyy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取景框选择器的纯逻辑测试。
 *
 * 数值取自真机日志里的 `char/2b_2` stand 帧：身体/武器集中在
 * `(-224,-73)-(220,297)`，而 `fx_*` 特效飞出这个范围好几百单位。
 */
class FrameBoxSelectorTest {

    private fun part(name: String, box: ContentBox, slot: Int = 0) =
        WeightedPart(box = box, weight = box.width * box.height, slot = slot)

    private fun names(vararg pairs: Pair<Int, String>): (Int) -> PartNames {
        val map = pairs.toMap()
        return { index -> PartNames(map[index], map[index]) }
    }

    @Test
    fun `部件名分类按特效道具武器身体的优先级`() {
        assertEquals(PartKind.EFFECT, SpinePartNaming.kindOf(PartNames("fx_halo", null)))
        assertEquals(PartKind.EFFECT, SpinePartNaming.kindOf(PartNames("slash1", null)))
        assertEquals(PartKind.PROP, SpinePartNaming.kindOf(PartNames("canyin1", null)))
        assertEquals(PartKind.WEAPON, SpinePartNaming.kindOf(PartNames("wep_sword", null)))
        assertEquals(PartKind.BODY, SpinePartNaming.kindOf(PartNames("hair_B", null)))
        assertEquals(PartKind.BODY, SpinePartNaming.kindOf(PartNames("face_1", null)))
        assertEquals(PartKind.OTHER, SpinePartNaming.kindOf(PartNames("slot12", null)))
        // slot 名泛化时用 attachment 名兜底
        assertEquals(PartKind.BODY, SpinePartNaming.kindOf(PartNames("slot3", "face/skin")))
    }

    @Test
    fun `离体特效与道具不参与取景`() {
        val parts = listOf(
            part("head", ContentBox(-50f, 500f, 120f, 700f), 0),
            part("face_1", ContentBox(-40f, 480f, 110f, 640f), 1),
            part("body", ContentBox(-80f, 100f, 150f, 520f), 2),
            part("hair_B", ContentBox(-70f, 460f, 130f, 720f), 3),
            // 这三件是 2b_2 里真实存在的离体件
            part("fx_circle", ContentBox(569f, -158f, 1164f, 440f), 4),
            part("fx_slash1", ContentBox(-77f, -111f, 1057f, 1104f), 5),
            part("canyin1", ContentBox(900f, 800f, 1300f, 1100f), 6)
        )

        val box = FrameBoxSelector.select(
            parts,
            names(
                0 to "head", 1 to "face_1", 2 to "body", 3 to "hair_B",
                4 to "fx_circle", 5 to "fx_slash1", 6 to "canyin1"
            )
        )

        assertNotNull(box)
        // 只框角色本体：右边界被 hair/body 的 150 附近封顶，绝不可能到 1000+
        assertTrue("right=${box!!.right}", box.right <= 200f)
        assertTrue("left=${box.left}", box.left >= -120f)
        assertTrue("top=${box.top}", box.top <= 760f)
        assertTrue("bottom=${box.bottom}", box.bottom >= 80f)
    }

    @Test
    fun `贴身武器算进造型范围`() {
        val parts = listOf(
            part("head", ContentBox(10f, 150f, 90f, 200f), 0),
            part("face_1", ContentBox(15f, 140f, 85f, 190f), 1),
            part("body", ContentBox(0f, 0f, 100f, 150f), 2),
            part("wep_sword", ContentBox(90f, 20f, 110f, 190f), 3)  // 武器：略超右边界
        )

        val box = FrameBoxSelector.select(
            parts,
            names(0 to "head", 1 to "face_1", 2 to "body", 3 to "wep_sword")
        )

        assertNotNull(box)
        // 武器是造型的一部分，必须算进来（旧逻辑会把它裁掉）
        assertEquals(110f, box!!.right, 0.01f)
    }

    @Test
    fun `甩出画面的巨大武器块按尺寸离群丢弃`() {
        val parts = listOf(
            part("head", ContentBox(10f, 150f, 90f, 200f), 0),
            part("face_1", ContentBox(15f, 140f, 85f, 190f), 1),
            part("body", ContentBox(0f, 0f, 100f, 150f), 2),
            part("wep_sword", ContentBox(90f, 20f, 110f, 190f), 3),
            part("wep_manjuuhead2", ContentBox(-35f, -55f, 1475f, 966f), 4)  // 1509×1020
        )

        val box = FrameBoxSelector.select(
            parts,
            names(0 to "head", 1 to "face_1", 2 to "body", 3 to "wep_sword", 4 to "wep_manjuuhead2")
        )

        assertNotNull(box)
        assertTrue("right=${box!!.right}", box.right < 500f)
        assertTrue("top=${box.top}", box.top < 500f)
    }

    @Test
    fun `件数不足时返回 null`() {
        val parts = listOf(part("head", ContentBox(0f, 0f, 10f, 10f), 0))
        assertNull(FrameBoxSelector.select(parts, names(0 to "head")))
    }

    @Test
    fun `只剩特效与道具时返回 null`() {
        val parts = listOf(
            part("fx_a", ContentBox(0f, 0f, 10f, 10f), 0),
            part("fx_b", ContentBox(0f, 0f, 10f, 10f), 1),
            part("canyin1", ContentBox(0f, 0f, 10f, 10f), 2)
        )
        assertNull(FrameBoxSelector.select(parts, names(0 to "fx_a", 1 to "fx_b", 2 to "canyin1")))
    }
}
