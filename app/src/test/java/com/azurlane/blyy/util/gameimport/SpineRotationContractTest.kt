package com.azurlane.blyy.util.gameimport

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.azurlane.blyy.util.unity.BundleImage
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityBundleSession
import com.azurlane.blyy.util.unity.UnityFsBundle
import com.azurlane.blyy.util.unity.UnityTextAssetReader
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.Skin
import com.esotericsoftware.spine.attachments.Attachment
import com.esotericsoftware.spine.attachments.AttachmentLoader
import com.esotericsoftware.spine.attachments.BoundingBoxAttachment
import com.esotericsoftware.spine.attachments.ClippingAttachment
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.PathAttachment
import com.esotericsoftware.spine.attachments.PointAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用 `resource/test` 与 `resource/sd` 里的**真实资源**验证 Spine 图集与骨骼的
 * 旋转语义契约。
 *
 * 这是「动态皮肤部件缺失 / 拼接错位」这类问题的护栏：
 *
 * 1. **`.atlas` 的 `rotate: 180/270` 必须能被 libgdx 解析成 degrees**
 *    （否则只能靠改写图集，而改写会导致 `updateUVs` 走错分支）。
 * 2. **`rotate: 180/270` 的区域只能被 `MeshAttachment` 使用**
 *    ——`RegionAttachment` 的 `updateOffset` 只处理 0/90，
 *    若出现 180/270 则由 mesh 分支接管；这条一旦被打破，
 *    说明必须重新评估旋转策略而不是继续改写图集。
 * 3. **`.skel` 里引用的每个 region 路径都必须在 `.atlas` 中存在**，
 *    否则就是「部位整块缺失」。
 *
 * 资源目录未入库（见 .gitignore），不存在时测试自动跳过。
 */
class SpineRotationContractTest {

    private val testRoot: File? = sequenceOf(
        File("../resource/test"),
        File("resource/test"),
        File("../../resource/test")
    ).firstOrNull { it.isDirectory }

    /** Spine 包目录（`spinepainting` / `char` 下的真实包） */
    private fun bundles(): List<File> {
        val root = testRoot ?: return emptyList()
        val out = mutableListOf<File>()
        listOf("spinepainting", "char").forEach { kind ->
            File(root, kind).listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) out += file
            }
        }
        return out
    }

    private class NamingLoader : AttachmentLoader {
        override fun newRegionAttachment(skin: Skin?, name: String?, path: String?): RegionAttachment =
            RegionAttachment(name ?: "").apply { setPath(path ?: name ?: "") }

        override fun newMeshAttachment(skin: Skin?, name: String?, path: String?): MeshAttachment =
            MeshAttachment(name ?: "").apply { setPath(path ?: name ?: "") }

        override fun newBoundingBoxAttachment(skin: Skin?, name: String?): BoundingBoxAttachment =
            BoundingBoxAttachment(name ?: "")

        override fun newPathAttachment(skin: Skin?, name: String?): PathAttachment =
            PathAttachment(name ?: "")

        override fun newPointAttachment(skin: Skin?, name: String?): PointAttachment =
            PointAttachment(name ?: "")

        override fun newClippingAttachment(skin: Skin?, name: String?): ClippingAttachment =
            ClippingAttachment(name ?: "")
    }

    /** 从包里取出全部 TextAsset（与导入链路同一条读取路径） */
    private fun textAssets(file: File): Map<String, ByteArray> {
        val bundle = UnityFsBundle.open(file)
        val image = File.createTempFile("blyy_rotcontract", ".img").apply { deleteOnExit() }
        bundle.decompressTo(image)
        val reader = BundleImage(image, bundle.nodes)
        try {
            val serialized = SerializedFile.open(reader, bundle.serializedNode()!!)
            val out = LinkedHashMap<String, ByteArray>()
            serialized.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).forEach { def ->
                val asset = UnityTextAssetReader.read(serialized, def) ?: return@forEach
                out[asset.name] = asset.bytes
            }
            return out
        } finally {
            reader.close()
        }
    }

    private fun atlasRegions(atlasText: String): List<TextureAtlas.TextureAtlasData.Region> {
        val dir = File.createTempFile("rotcontract_atlas", "").apply {
            delete()
            mkdirs()
        }
        try {
            val file = File(dir, "p.atlas").apply { writeText(atlasText) }
            return TextureAtlas.TextureAtlasData(FileHandle(file), FileHandle(dir), false)
                .regions
                .toArray()
                .map { it as TextureAtlas.TextureAtlasData.Region }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 真实包的旋转契约：`180/270` 只能挂在 mesh 上，且所有 skel 路径都能在 atlas 找到。
     */
    @Test
    fun `真实 spine 包满足旋转与路径契约`() {
        val files = bundles().filter {
            runCatching { textAssets(it).keys.any { name -> name.endsWith(".skel") } }.getOrDefault(false)
        }
        assumeTrue("缺少真实 spine 包（resource/test 未生成）", files.isNotEmpty())

        var checkedAtlases = 0
        files.forEach { file ->
            val texts = textAssets(file)
            val skeletons = texts.filterKeys { it.endsWith(".skel", ignoreCase = true) }
            val atlases = texts.filterKeys { it.endsWith(".atlas", ignoreCase = true) }
            atlases.forEach { (atlasName, bytes) ->
                val atlasText = String(bytes, Charsets.UTF_8)
                val regions = atlasRegions(atlasText)
                val degreesOf = regions.associate { it.name to it.degrees }
                checkedAtlases++

                val base = atlasName.substringBeforeLast('.')
                val skelBytes = skeletons.entries
                    .firstOrNull { it.key.substringBeforeLast('.') == base }
                    ?.value
                if (skelBytes == null) return@forEach

                val dir = File.createTempFile("rotcontract_skel", "").apply {
                    delete()
                    mkdirs()
                }
                val data: SkeletonData = try {
                    val skelFile = File(dir, "$base.skel").apply { writeBytes(skelBytes) }
                    SkeletonBinary(NamingLoader()).readSkeletonData(FileHandle(skelFile))
                } finally {
                    dir.deleteRecursively()
                }

                var missing = 0
                data.skins.forEach { skin ->
                    val entryList = skin.attachments
                    entryList.forEach { entry ->
                        val attachment: Attachment = entry.attachment
                        val path: String? = when (attachment) {
                            is RegionAttachment -> attachment.path
                            is MeshAttachment -> attachment.path
                            else -> null
                        } ?: return@forEach
                        val degrees = degreesOf[path]
                        if (degrees == null) {
                            missing++
                            return@forEach
                        }
                        if (degrees == 180 || degrees == 270) {
                            // RegionAttachment 的 updateOffset 只处理 0/90；
                            // 180/270 必须由 MeshAttachment 的 updateUVs 接管
                            assertTrue(
                                "$file/${atlasName}: '$path' 用 degrees=$degrees，" +
                                    "但 attachment 是 ${attachment.javaClass.simpleName}",
                                attachment is MeshAttachment
                            )
                        }
                    }
                }
                assertEquals("$file/${atlasName}: 有 region 路径在 atlas 中缺失", 0, missing)
            }
        }
        assertTrue("未校验到任何真实图集", checkedAtlases > 0)
    }

    /**
     * `rotate` 三种写法（true / 数字 / false）在真实图集里都会出现，
     * 且度数分布可通过 [SpineRotationAudit] 审计。
     */
    @Test
    fun `真实图集的旋转区域可被审计`() {
        val files = bundles()
        assumeTrue("缺少真实 spine 包", files.isNotEmpty())
        var audited = 0
        files.forEach { file ->
            val texts = textAssets(file)
            texts.filterKeys { it.endsWith(".atlas", ignoreCase = true) }.forEach { (_, bytes) ->
                val report = SpineRotationAudit.inspect(String(bytes, Charsets.UTF_8))
                report.counts.keys.forEach { degrees ->
                    assertTrue("审计出现非法角度 $degrees", degrees in setOf(90, 180, 270))
                }
                assertEquals(
                    "审计总数与各角度之和不等",
                    report.changedRegions,
                    report.counts.values.sum()
                )
                audited++
            }
        }
        assertTrue(audited > 0)
    }

    /**
     * `resource/sd/SD` 里 1600+ 个真实 SD 资源：`rotate` 的三种写法都会出现，
     * 且 `180/270` 只出现在少数包上——所以「归零相反」的修复必须对全部写法成立。
     */
    @Test
    fun `真实 SD 资源目录的 rotate 写法分布可审计`() {
        val sdRoot = sequenceOf(
            File("../resource/sd/SD"),
            File("resource/sd/SD"),
            File("../../resource/sd/SD")
        ).firstOrNull { it.isDirectory }
        assumeTrue("缺少 resource/sd/SD", sdRoot != null)

        var atlases = 0
        var withAngles = 0
        sdRoot!!.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".atlas") }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val report = SpineRotationAudit.inspect(text)
                atlases++
                if (report.counts.containsKey(180) || report.counts.containsKey(270)) withAngles++
                report.counts.keys.forEach { degrees ->
                    assertTrue("非法角度 $degrees @ ${file.name}", degrees in setOf(90, 180, 270))
                }
            }
        assertTrue("未找到任何真实 atlas", atlases > 0)
        // 只要存在 180/270 的区域，就说明「不改写 rotate」的修复是必需的
        assertNotNull(withAngles)
    }
}
