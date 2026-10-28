package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导出命名 / 目录规范（[GameAssetExporter] 的纯函数部分）。
 *
 * 约定：`Downloads/BLYY/<子目录>/<显示名>_<资源id>.png`。
 * 显示名方便人找，id 保证不同皮肤不重名；非法字符会被替换，避免落盘失败。
 */
class GameAssetExporterTest {

    private fun asset(kind: GameAssetKind, id: String) = GameAssetInfo(
        kind = kind,
        id = id,
        dir = File("/tmp/$id"),
        sizeBytes = 1,
        fileCount = 1,
        imageWidth = 2048,
        imageHeight = 1206,
        previewFile = File("/tmp/$id/painting.png")
    )

    @Test
    fun `图片型资源按类型分目录`() {
        assertEquals("BLYY/Painting", GameAssetExporter.subPathOf(GameAssetKind.PAINTING))
        assertEquals("BLYY/LoadingBg", GameAssetExporter.subPathOf(GameAssetKind.LOADING_BG))
    }

    @Test
    fun `文件名带可读名与稳定 id`() {
        val painting = asset(GameAssetKind.PAINTING, "2b_2")
        assertEquals("2B · 须臾的休憩_2b_2.png", GameAssetExporter.fileNameOf(painting, "2B · 须臾的休憩"))
        // 显示名缺失时退回 id
        assertEquals("2b_2.png", GameAssetExporter.fileNameOf(painting, null))
    }

    @Test
    fun `非法字符被替换`() {
        val bg = asset(GameAssetKind.LOADING_BG, "bg_1")
        assertEquals("a_b_c_bg_1.png", GameAssetExporter.fileNameOf(bg, "a/b:c"))
    }

    @Test
    fun `只有图片型资源可导出`() {
        val image = File.createTempFile("blyy_export", ".png").apply { deleteOnExit() }
        fun withPreview(kind: GameAssetKind, id: String) = GameAssetInfo(
            kind = kind,
            id = id,
            dir = image.parentFile ?: File("/tmp"),
            sizeBytes = 1,
            fileCount = 1,
            previewFile = image
        )

        assertTrue(GameAssetExporter.canExport(withPreview(GameAssetKind.PAINTING, "x")))
        assertTrue(GameAssetExporter.canExport(withPreview(GameAssetKind.LOADING_BG, "bg_1")))
        // SD 小人不是单张图，导出按钮不出现
        assertFalse(GameAssetExporter.canExport(withPreview(GameAssetKind.SD_CHAR, "boge")))
        // 图片文件缺失同样不可导出
        assertFalse(
            GameAssetExporter.canExport(
                withPreview(GameAssetKind.PAINTING, "y").copy(previewFile = File("/tmp/not-exists.png"))
            )
        )
    }
}
