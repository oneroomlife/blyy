package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.unity.BundleImage
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityFsBundle
import java.io.Closeable
import java.io.File

/**
 * 立绘解析测试的公共夹具。
 *
 * `resource/` 与 `.work_tmp/` 都不入库，缺失时相关用例自动跳过（与
 * [GameBundleProbeTest] 的处理方式一致），不会让 CI 挂在一个不存在的路径上。
 */
internal object PaintingTestSupport {

    val testRoot: File? = sequenceOf(
        File("../resource/test"),
        File("resource/test"),
        File("../../resource/test")
    ).firstOrNull { it.isDirectory }

    /**
     * 立绘布局/合成的专用样本目录。
     *
     * 单独放一份而不是塞进 `resource/test`：那里的包数量被
     * [GameBundleSweepTest] 当作快照断言，往里加文件会让别人的机器上跑红。
     */
    val layoutRoot: File? = sequenceOf(
        File("../resource/work/painting_layout"),
        File("resource/work/painting_layout"),
        File("../../resource/work/painting_layout")
    ).firstOrNull { it.isDirectory }

    /** Python 侧导出的图集 PNG 与期望值（仅本地验证用） */
    val fixtureRoot: File? = sequenceOf(
        File("../.work_tmp/fixtures"),
        File(".work_tmp/fixtures"),
        File("../../.work_tmp/fixtures")
    ).firstOrNull { it.isDirectory }

    /** 还原结果落盘目录，方便人眼复核 */
    val outputRoot: File by lazy {
        val dir = sequenceOf(
            File("../.work_tmp/kotlin_out"),
            File(".work_tmp/kotlin_out"),
            File("../../.work_tmp/kotlin_out")
        ).firstOrNull { it.parentFile?.exists() == true } ?: File("build/tmp/kotlin_out")
        dir.apply { mkdirs() }
    }

    fun bundle(relative: String): File? {
        for (root in listOfNotNull(layoutRoot, testRoot)) {
            val file = File(root, relative)
            if (file.isFile) return file
        }
        return null
    }

    fun fixture(name: String): File? {
        val root = fixtureRoot ?: return null
        return File(root, name).takeIf { it.isFile }
    }

    class Opened(val image: BundleImage, val serialized: SerializedFile) : Closeable {
        override fun close() {
            runCatching { image.close() }
        }
    }

    /** 解包到临时镜像并打开序列化文件；调用方负责 close */
    fun open(file: File): Opened {
        val bundle = UnityFsBundle.open(file)
        val imageFile = File.createTempFile("blyy_painting", ".img").apply { deleteOnExit() }
        bundle.decompressTo(imageFile)
        val image = BundleImage(imageFile, bundle.nodes)
        return try {
            Opened(image, SerializedFile.open(image, bundle.serializedNode()!!))
        } catch (t: Throwable) {
            runCatching { image.close() }
            throw t
        }
    }
}
