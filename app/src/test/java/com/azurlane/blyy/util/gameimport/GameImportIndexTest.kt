package com.azurlane.blyy.util.gameimport

import android.content.Context
import com.azurlane.blyy.util.unity.AlLive2dExtractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * [GameImportIndex] 持久化行为单测：upsert 去重、replaceAll 清理、按资产移除、
 * 原子写（无 tmp 残留）、损坏索引自愈、旧索引迁移。
 *
 * Context 仅用到 `filesDir`，mock 到临时目录即可走真实文件 IO。
 */
class GameImportIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newIndex(): GameImportIndex {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tmp.root)
        return GameImportIndex(context)
    }

    private fun record(
        path: String,
        assetId: String = "id_$path",
        kind: GameAssetKind = GameAssetKind.SD_CHAR,
        status: String = GameImportRecord.STATUS_IMPORTED
    ) = GameImportRecord(
        path = path,
        kind = kind.key,
        size = 1024L,
        modified = 100L,
        assetId = assetId,
        status = status
    )

    @Test
    fun `upsert 按 path 去重并持久化到磁盘`() = runTest {
        val index = newIndex()
        index.upsert(record("p1"))
        index.upsert(record("p1", assetId = "id_new"))
        index.upsert(record("p2"))

        // 新实例 = 丢弃内存缓存，验证真的落了盘
        val loaded = newIndex().load()
        assertEquals(2, loaded.entries.size)
        assertEquals("id_new", loaded.entries.first { it.path == "p1" }.assetId)
    }

    @Test
    fun `replaceAll 只保留 keepPaths 内的记录并合入新记录`() = runTest {
        val index = newIndex()
        index.upsert(record("a"))
        index.upsert(record("b"))
        index.replaceAll(records = listOf(record("c")), keepPaths = setOf("a", "c"))

        val loaded = newIndex().load()
        assertEquals(setOf("a", "c"), loaded.entries.map { it.path }.toSet())
    }

    @Test
    fun `removeByAsset 清掉指向该资源的全部记录`() = runTest {
        val index = newIndex()
        index.upsert(record("a", assetId = "x"))
        index.upsert(record("b", assetId = "x"))
        index.upsert(record("c", assetId = "y", kind = GameAssetKind.PAINTING))

        index.removeByAsset(GameAssetKind.SD_CHAR, "x")

        val loaded = newIndex().load()
        assertEquals(listOf("c"), loaded.entries.map { it.path })
    }

    @Test
    fun `写入后目标文件可读且无 tmp 残留`() = runTest {
        val index = newIndex()
        index.upsert(record("a"))
        index.upsert(record("b"))

        val file = File(tmp.root, "game_import_index.json")
        assertTrue(file.isFile)
        assertFalse(File(tmp.root, "game_import_index.json.tmp").exists())
        assertTrue(file.readText().contains("\"path\""))
    }

    @Test
    fun `损坏索引按空重建不崩溃`() = runTest {
        File(tmp.root, "game_import_index.json").writeText("{ not json")
        val loaded = newIndex().load()
        assertTrue(loaded.entries.isEmpty())
    }

    @Test
    fun `renameAsset 同步全部指向旧 id 的记录`() = runTest {
        val index = newIndex()
        index.upsert(record("a", assetId = "old"))
        index.upsert(record("b", assetId = "old"))
        index.upsert(record("c", assetId = "other", kind = GameAssetKind.PAINTING))

        index.renameAsset(GameAssetKind.SD_CHAR, "old", "new")

        val loaded = newIndex().load()
        assertEquals(
            mapOf("a" to "new", "b" to "new", "c" to "other"),
            loaded.entries.associate { it.path to it.assetId }
        )
    }

    @Test
    fun `旧 live2d 索引自动迁移且状态保留`() = runTest {
        File(tmp.root, "live2d_bundle_index.json").writeText(
            """
            {"version":1,"roots":["/sdcard/AssetBundles/live2d"],
             "entries":[{"path":"/sdcard/AssetBundles/live2d/a.ys","modelId":"a",
                         "status":"imported","extractorVersion":${AlLive2dExtractor.EXTRACTOR_VERSION}}]}
            """.trimIndent()
        )

        val loaded = newIndex().load()
        assertEquals(1, loaded.entries.size)
        val entry = loaded.entries.single()
        assertEquals("a", entry.assetId)
        assertEquals(GameAssetKind.LIVE2D.key, entry.kind)
        assertTrue(entry.isImported)
        assertEquals(GamePipelineVersions.LIVE2D, entry.pipelineVersion)
    }
}
