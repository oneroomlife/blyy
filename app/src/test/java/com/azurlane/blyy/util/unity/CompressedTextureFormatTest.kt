package com.azurlane.blyy.util.unity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unity 纹理格式映射回归。
 *
 * 碧蓝不同资源目录用的压缩格式不一样，映射错/漏一个值，整个资源包就解不出来：
 * - `live2d` / `painting` / `spinepainting` → ASTC 4x4（48）
 * - `char`（SD 小人） → ASTC 6x6（**50**），此前没有映射，SD 资源必然导入失败
 */
class CompressedTextureFormatTest {

    @Test
    fun `ASTC 各块尺寸映射到对应 GL 常量`() {
        assertEquals(0x93B0, CompressedTextureDecoder.glFormatOf(48)) // ASTC_4x4
        assertEquals(0x93B1, CompressedTextureDecoder.glFormatOf(49)) // ASTC_5x5
        assertEquals(0x93B2, CompressedTextureDecoder.glFormatOf(50)) // ASTC_6x6
        assertEquals(0x93B3, CompressedTextureDecoder.glFormatOf(51)) // ASTC_8x8
        assertEquals(0x93B4, CompressedTextureDecoder.glFormatOf(52)) // ASTC_10x10
        assertEquals(0x93B5, CompressedTextureDecoder.glFormatOf(53)) // ASTC_12x12
    }

    @Test
    fun `ASTC RGBA 变体与 RGB 变体共用枚举`() {
        for (offset in 0..5) {
            assertEquals(
                CompressedTextureDecoder.glFormatOf(48 + offset),
                CompressedTextureDecoder.glFormatOf(54 + offset)
            )
        }
    }

    @Test
    fun `ETC 家族映射`() {
        assertEquals(0x9278, CompressedTextureDecoder.glFormatOf(46))
        assertEquals(0x9278, CompressedTextureDecoder.glFormatOf(47))
        assertEquals(0x9273, CompressedTextureDecoder.glFormatOf(45))
        assertEquals(0x8D64, CompressedTextureDecoder.glFormatOf(34))
    }

    @Test
    fun `未知格式返回 null`() {
        assertNull(CompressedTextureDecoder.glFormatOf(4)) // RGBA32 走独立通路
        assertNull(CompressedTextureDecoder.glFormatOf(-1))
    }
}
