package com.azurlane.blyy.util.unity

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 资源包字段读取的通用小工具。
 *
 * Unity 的 TypeTree 读出来的值形态不稳定：TextAsset 的 `m_Script` 可能是
 * `ByteArray`、`List<Number>`，也可能被声明成 `String`（UTF-8）；数值字段可能是
 * `Int` / `Long` / `Float`。所有提取器共用这里的转换，避免每处各写一份。
 */
internal object UnityValues {

    /** 把 `m_Script` 之类的字节字段统一成 ByteArray；无法识别返回 null */
    fun toBytes(raw: Any?): ByteArray? = when (raw) {
        is ByteArray -> raw
        is String -> raw.toByteArray(Charsets.UTF_8)
        is List<*> -> {
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i] as? Number)?.toByte() ?: 0
            out
        }
        else -> null
    }

    fun number(raw: Any?): Long? = (raw as? Number)?.toLong()

    fun int(raw: Any?): Int? = (raw as? Number)?.toInt()

    /**
     * 浮点字段（`Vector2/3` 的分量、`mRawSpriteSize` 等）。
     *
     * 不能用 [number]：它走 `toLong()`，会把 `0.5` 读成 `0`、`43.3` 读成 `43`——
     * 立绘布局的锚点/轴心全是 0..1 之间的小数，被截断后整张图会错位。
     */
    fun float(raw: Any?): Float? = (raw as? Number)?.toFloat()
}

/**
 * 一次性的资源包读取会话：解开 UnityFS → 定位序列化文件 → 提供流式贴图数据读取。
 *
 * 与 [AlLive2dExtractor] 共用同一份解压镜像缓存（`cacheDir/al_live2d/<name>.img`，
 * 命名规则 = 文件名-长度-修改时间），因此「先探测再提取」不会把 30MB+ 的包
 * 解压两遍：探测阶段写好的镜像，Live2D 提取器会直接命中。
 *
 * 镜像文件**不在 close 时删除**：Live2D 提取器依赖它做同一次导入内的复用。
 * 它的生命周期由调用方（导入管线）负责：`GameBundleImporter.importLocal` 结束时
 * 统一删除本次包的镜像——探测与提取共用同一份，结束后立即释放，
 * 否则每个包都会在 cacheDir 留下几十 MB 的永久残留。
 */
internal class UnityBundleSession private constructor(
    val bundleFile: File,
    val bundle: UnityFsBundle,
    val imageFile: File,
    val image: BundleImage,
    val serialized: SerializedFile
) : Closeable {

    val resourceNode: UnityFsBundle.Node? get() = bundle.resourceNode()

    /**
     * 读取 `.resS` 流式区段，返回**本地字节序**的 direct buffer（供
     * `glCompressedTexImage2D` 直接消费，避免再拷一次到堆内）。
     */
    fun readResource(offset: Long, size: Long): ByteBuffer {
        val node = resourceNode ?: throw IOException("资源包缺少 .resS 流式数据")
        return readResource(node, offset, size)
    }

    fun readResource(node: UnityFsBundle.Node, offset: Long, size: Long): ByteBuffer {
        if (offset < 0 || size <= 0 || offset + size > node.size) {
            throw IOException("贴图数据越界：offset=$offset size=$size node=${node.size}")
        }
        val buffer = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        val chunk = ByteArray(minOf(size, COPY_CHUNK.toLong()).toInt())
        var done = 0L
        while (done < size) {
            val n = minOf(chunk.size.toLong(), size - done).toInt()
            image.readFully(node, offset + done, chunk, 0, n)
            buffer.put(chunk, 0, n)
            done += n
        }
        buffer.flip()
        return buffer
    }

    /** 读取节点全部字节（序列化文件通常只有几十 KB 到几十 MB） */
    fun readNode(node: UnityFsBundle.Node): ByteArray = image.nodeBytes(node)

    override fun close() {
        runCatching { image.close() }
    }

    companion object {
        private const val COPY_CHUNK = 1 shl 20

        /** 解压镜像缓存目录（与 [AlLive2dExtractor] 保持一致，便于跨步骤复用） */
        fun imageCacheDir(context: android.content.Context): File =
            File(context.cacheDir, "al_live2d").apply { mkdirs() }

        /** 缓存文件名：同一路径下内容变化（大小/时间）会自动换新名，天然失效 */
        fun imageCacheFile(context: android.content.Context, bundleFile: File): File = File(
            imageCacheDir(context),
            "${bundleFile.nameWithoutExtension}-${bundleFile.length()}-${bundleFile.lastModified()}.img"
        )

        /**
         * 打开资源包。
         *
         * @param onProgress 进度回调（0f..1f + 文字），IO 线程调用
         */
        fun open(
            context: android.content.Context,
            bundleFile: File,
            onProgress: (Float, String) -> Unit = { _, _ -> }
        ): UnityBundleSession {
            if (!bundleFile.isFile) throw IOException("资源包不存在：${bundleFile.name}")
            if (!UnityFsBundle.detect(bundleFile)) throw IOException("不是 UnityFS 资源包：${bundleFile.name}")

            onProgress(0.02f, "解析资源包头")
            val bundle = UnityFsBundle.open(bundleFile)
            val serializedNode = bundle.serializedNode()
                ?: throw IOException("资源包内没有序列化文件（CAB）")
            val expectedTotal = bundle.nodes.sumOf { it.size }
            if (expectedTotal <= 0L) throw IOException("资源包节点表为空")

            val imageFile = imageCacheFile(context, bundleFile)
            if (!imageFile.isFile || imageFile.length() != expectedTotal) {
                onProgress(0.05f, "解压资源包（${bundle.blocks.size} 个数据块）")
                runCatching { imageFile.delete() }
                bundle.decompressTo(imageFile) { done, total ->
                    val fraction = if (total <= 0L) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
                    onProgress(0.05f + fraction * 0.55f, "解压资源包 ${(fraction * 100).toInt()}%")
                }
            }
            val image = BundleImage(imageFile, bundle.nodes)
            try {
                val serialized = SerializedFile.open(image, serializedNode)
                return UnityBundleSession(bundleFile, bundle, imageFile, image, serialized)
            } catch (e: Throwable) {
                runCatching { image.close() }
                throw e
            }
        }
    }
}
