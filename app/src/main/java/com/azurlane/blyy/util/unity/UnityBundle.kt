package com.azurlane.blyy.util.unity

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * LZ4 块解码器（纯 Kotlin 实现，无 native 依赖）。
 *
 * UnityFS 的块只使用 **LZ4 块格式**（无帧头），LZ4HC 与 LZ4 的块格式完全一致，
 * 只是压缩端搜索策略不同，因此同一个解码器可同时处理 blockFlags 的 2(LZ4) 与 3(LZ4HC)。
 * 参考实现：https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md
 */
internal object Lz4BlockDecoder {

    /**
     * 解码一个 LZ4 块。
     * @return 实际写入 [dst] 的字节数
     */
    fun decode(
        src: ByteArray,
        srcOffset: Int,
        srcLength: Int,
        dst: ByteArray,
        dstOffset: Int,
        dstLength: Int
    ): Int {
        var sp = srcOffset
        var dp = dstOffset
        val srcEnd = srcOffset + srcLength
        val dstEnd = dstOffset + dstLength

        while (sp < srcEnd) {
            val token = src[sp++].toInt() and 0xFF

            // ---- 字面量 ----
            var literalLength = token ushr 4
            if (literalLength == 15) {
                while (true) {
                    if (sp >= srcEnd) throw IOException("LZ4 块损坏：字面量长度溢出")
                    val b = src[sp++].toInt() and 0xFF
                    literalLength += b
                    if (b != 0xFF) break
                }
            }
            if (literalLength > 0) {
                if (dp + literalLength > dstEnd || sp + literalLength > srcEnd) {
                    throw IOException("LZ4 块损坏：字面量越界")
                }
                System.arraycopy(src, sp, dst, dp, literalLength)
                sp += literalLength
                dp += literalLength
            }
            if (sp >= srcEnd) break

            // ---- 匹配 ----
            if (sp + 2 > srcEnd) throw IOException("LZ4 块损坏：缺少匹配偏移")
            val offset = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
            sp += 2
            if (offset == 0) throw IOException("LZ4 块损坏：匹配偏移为 0")

            var matchLength = token and 0x0F
            if (matchLength == 15) {
                while (true) {
                    if (sp >= srcEnd) throw IOException("LZ4 块损坏：匹配长度溢出")
                    val b = src[sp++].toInt() and 0xFF
                    matchLength += b
                    if (b != 0xFF) break
                }
            }
            matchLength += 4

            val ref = dp - offset
            if (ref < dstOffset || dp + matchLength > dstEnd) {
                throw IOException("LZ4 块损坏：匹配越界 ref=$ref dp=$dp len=$matchLength")
            }
            // 逐字节复制以正确处理重叠匹配（RLE 场景）
            for (i in 0 until matchLength) {
                dst[dp + i] = dst[ref + i]
            }
            dp += matchLength
        }
        return dp - dstOffset
    }

    /** 解码到新数组（长度由 [dstSize] 指定，必须与实际解压长度一致） */
    fun decode(src: ByteArray, srcLength: Int, dstSize: Int): ByteArray {
        val out = ByteArray(dstSize)
        val written = decode(src, 0, srcLength, out, 0, dstSize)
        if (written != dstSize) throw IOException("LZ4 解压长度不符：期望 $dstSize，实际 $written")
        return out
    }
}

/**
 * UnityFS 资源包（`*.unity3d` / 碧蓝航线 `.ys`）读取器。
 *
 * 碧蓝航线的 Live2D 皮肤包就是标准 UnityFS，未加密：
 * ```
 * "UnityFS\0" | u32 version | cstr unityVersion | cstr unityRevision
 * i64 size | u32 compressedBlocksInfoSize | u32 uncompressedBlocksInfoSize | u32 flags
 * [对齐 16] 压缩的 blocksInfo | [对齐 16] 块数据（每块 LZ4/LZ4HC/none）
 * ```
 * blocksInfo 解压后：16 字节数据哈希 → u32 块数 → 每块 {u32 uncompressedSize, u32 compressedSize, u16 flags}
 * → u32 节点数 → 每节点 {i64 offset, i64 size, u32 flags, cstr path}。
 *
 * 块数据解压后按顺序拼接即得「虚拟镜像」，节点的 offset/size 都指向这个镜像。
 */
class UnityFsBundle private constructor(
    val version: Int,
    val unityVersion: String,
    val unityRevision: String,
    val flags: Int,
    val blocks: List<Block>,
    val nodes: List<Node>,
    private val blockDataOffset: Long
) {

    data class Block(val uncompressedSize: Long, val compressedSize: Long, val flags: Int) {
        /** 低 6 位是压缩类型：0=none 1=LZMA 2=LZ4 3=LZ4HC 4=LZHAM */
        val compressionType: Int get() = flags and 0x3F
    }

    data class Node(val path: String, val offset: Long, val size: Long, val flags: Int) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** 序列化文件节点（CAB-xxxx），通常一个包只有一个 */
    fun serializedNode(): Node? = nodes.firstOrNull { !it.path.endsWith(".resS") && !it.path.endsWith(".resource") }

    /** 流式贴图数据节点（.resS） */
    fun resourceNode(): Node? = nodes.firstOrNull { it.path.endsWith(".resS") || it.path.endsWith(".resource") }

    /**
     * 把全部块解压并按顺序写入 [output]，得到可供随机访问的镜像文件。
     * 采用流式写盘而非整块驻留内存：单个包解压后可达 60MB 以上，
     * 在低端机上整块驻留极易 OOM。
     */
    fun decompressTo(output: File, onProgress: ((done: Long, total: Long) -> Unit)? = null) {
        val totalCompressed = blocks.sumOf { it.compressedSize }.coerceAtLeast(1L)
        var progressed = 0L
        RandomAccessFile(inputFile, "r").use { src ->
            BufferedOutputStream(FileOutputStream(output, false), 1 shl 16).use { out ->
                var readPos = blockDataOffset
                var total = 0L
                for (block in blocks) {
                    src.seek(readPos)
                    val compressed = ByteArray(block.compressedSize.toInt())
                    src.readFully(compressed)
                    readPos += block.compressedSize

                    val plain: ByteArray = when (block.compressionType) {
                        0 -> compressed
                        2, 3 -> Lz4BlockDecoder.decode(compressed, compressed.size, block.uncompressedSize.toInt())
                        1 -> throw IOException("该资源包使用 LZMA 压缩，暂不支持")
                        4 -> throw IOException("该资源包使用 LZHAM 压缩，暂不支持")
                        else -> throw IOException("未知的 UnityFS 压缩类型：${block.compressionType}")
                    }
                    out.write(plain)
                    total += plain.size
                    progressed += block.compressedSize
                    onProgress?.invoke(progressed, totalCompressed)
                }
                out.flush()

                val expected = nodes.sumOf { it.size }
                if (expected > 0 && total != expected) {
                    throw IOException("镜像长度异常：解压 $total 字节，节点表期望 $expected 字节")
                }
            }
        }
    }

    private lateinit var inputFile: File

    companion object {
        private const val TAG = "UnityFsBundle"
        private val SIGNATURE = byteArrayOf(0x55, 0x6E, 0x69, 0x74, 0x79, 0x46, 0x53, 0x00)

        /** 快速探测：文件头是否为 UnityFS */
        fun detect(file: File): Boolean {
            if (!file.isFile || file.length() < 32L) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val head = ByteArray(8)
                    raf.readFully(head)
                    head.contentEquals(SIGNATURE)
                }
            } catch (e: IOException) {
                false
            }
        }

        fun open(file: File): UnityFsBundle {
            RandomAccessFile(file, "r").use { raf ->
                val cursor = Cursor(raf)
                val sig = cursor.bytes(8)
                if (!sig.contentEquals(SIGNATURE)) throw IOException("不是 UnityFS 资源包")

                val version = cursor.u32be().toInt()
                val unityVersion = cursor.cstr()
                val unityRevision = cursor.cstr()
                val size = cursor.i64be()
                val compressedBlocksInfoSize = cursor.u32be()
                val uncompressedBlocksInfoSize = cursor.u32be()
                val flags = cursor.u32be().toInt()
                if (version < 6 || version > 99) throw IOException("不支持的 UnityFS 版本：$version")
                if (compressedBlocksInfoSize <= 0 || compressedBlocksInfoSize > 64L * 1024 * 1024) {
                    throw IOException("blocksInfo 长度异常：$compressedBlocksInfoSize")
                }
                if (uncompressedBlocksInfoSize <= 0 || uncompressedBlocksInfoSize > 128L * 1024 * 1024) {
                    throw IOException("blocksInfo 解压长度异常：$uncompressedBlocksInfoSize")
                }

                if (version >= 7) cursor.align(16)

                // 部分包把 blocksInfo 放在文件末尾（flags & 0x80）
                val infoOffset = if (flags and 0x80 != 0) size - compressedBlocksInfoSize else cursor.pos
                cursor.seek(infoOffset)
                val compressedInfo = cursor.bytes(compressedBlocksInfoSize.toInt())

                val compressionType = flags and 0x3F
                val infoBytes = when (compressionType) {
                    0 -> compressedInfo
                    2, 3 -> Lz4BlockDecoder.decode(
                        compressedInfo,
                        compressedInfo.size,
                        uncompressedBlocksInfoSize.toInt()
                    )
                    1 -> throw IOException("blocksInfo 使用 LZMA 压缩，暂不支持")
                    else -> throw IOException("未知的 blocksInfo 压缩类型：$compressionType")
                }

                // blocksInfo 解压后前 16 字节为数据哈希（Unity 5.6+/version>=7）
                val parsed = parseBlocksInfo(infoBytes, skipHash = version >= 7)
                val blocks = parsed.first
                val nodes = parsed.second

                // 块数据起点：blocksInfo 之后按 16 字节对齐（flags & 0x200）
                var dataOffset = infoOffset + compressedBlocksInfoSize
                if (flags and 0x200 != 0) dataOffset = (dataOffset + 15) / 16 * 16

                val bundle = UnityFsBundle(
                    version = version,
                    unityVersion = unityVersion,
                    unityRevision = unityRevision,
                    flags = flags,
                    blocks = blocks,
                    nodes = nodes,
                    blockDataOffset = dataOffset
                )
                bundle.inputFile = file
                return bundle
            }
        }

        private fun parseBlocksInfo(bytes: ByteArray, skipHash: Boolean): Pair<List<Block>, List<Node>> {
            var start = if (skipHash) 16 else 0
            // 容错：两种起点都试，取结构自洽的那个
            for (attempt in 0..1) {
                val off = if (attempt == 0) start else 0
                val result = runCatching { readBlocksInfo(bytes, off) }.getOrNull()
                if (result != null) return result
            }
            // 退化为不跳过哈希
            start = 0
            return readBlocksInfo(bytes, start)
        }

        private fun readBlocksInfo(bytes: ByteArray, offset: Int): Pair<List<Block>, List<Node>> {
            var pos = offset
            fun need(n: Int) {
                if (pos + n > bytes.size) throw IOException("blocksInfo 越界：需要 $n 字节，剩余 ${bytes.size - pos}")
            }
            fun i32(): Int {
                need(4)
                val v = ((bytes[pos].toInt() and 0xFF) shl 24) or
                    ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 3].toInt() and 0xFF)
                pos += 4
                return v
            }
            fun u16(): Int {
                need(2)
                val v = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                pos += 2
                return v
            }
            fun i64(): Long {
                need(8)
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
                pos += 8
                return v
            }
            fun cstr(): String {
                val end = run {
                    var i = pos
                    while (i < bytes.size && bytes[i] != 0.toByte()) i++
                    i
                }
                if (end >= bytes.size) throw IOException("blocksInfo 字符串未终止")
                val s = String(bytes, pos, end - pos, Charsets.UTF_8)
                pos = end + 1
                return s
            }

            val blockCount = i32()
            if (blockCount < 0 || blockCount > 2_000_000) throw IOException("块数量异常：$blockCount")
            val blocks = ArrayList<Block>(blockCount)
            repeat(blockCount) {
                val uncompressed = i32().toLong() and 0xFFFFFFFFL
                val compressed = i32().toLong() and 0xFFFFFFFFL
                val flags = u16()
                if (compressed <= 0 || uncompressed <= 0) throw IOException("块长度异常：$uncompressed/$compressed")
                blocks += Block(uncompressed, compressed, flags)
            }

            val nodeCount = i32()
            if (nodeCount < 0 || nodeCount > 100_000) throw IOException("节点数量异常：$nodeCount")
            val nodes = ArrayList<Node>(nodeCount)
            repeat(nodeCount) {
                val offset = i64()
                val size = i64()
                val flags = i32()
                val path = cstr()
                nodes += Node(path, offset, size, flags)
            }
            if (nodes.isEmpty()) throw IOException("资源包内没有节点")
            return blocks to nodes
        }
    }
}

/** 解压后的镜像：随机访问节点数据 */
class BundleImage(val file: File, val nodes: List<UnityFsBundle.Node>) : Closeable {

    private val raf = RandomAccessFile(file, "r")

    fun nodeByPath(path: String): UnityFsBundle.Node? = nodes.firstOrNull { it.path == path }

    fun nodeByName(name: String): UnityFsBundle.Node? = nodes.firstOrNull { it.name == name }

    fun nodeBySuffix(suffix: String): UnityFsBundle.Node? = nodes.firstOrNull { it.path.endsWith(suffix) }

    /** 节点内相对偏移读取 */
    fun read(node: UnityFsBundle.Node, offsetInNode: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (offsetInNode < 0 || offsetInNode + length > node.size) {
            throw IOException("节点读取越界：${node.name} +$offsetInNode/$length (size=${node.size})")
        }
        val out = ByteArray(length)
        synchronized(raf) {
            raf.seek(node.offset + offsetInNode)
            raf.readFully(out)
        }
        return out
    }

    fun readFully(node: UnityFsBundle.Node, offsetInNode: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        if (length <= 0) return
        if (offsetInNode < 0 || offsetInNode + length > node.size) throw IOException("节点读取越界")
        synchronized(raf) {
            raf.seek(node.offset + offsetInNode)
            raf.readFully(dst, dstOffset, length)
        }
    }

    fun nodeBytes(node: UnityFsBundle.Node): ByteArray = read(node, 0, node.size.toInt())

    override fun close() {
        runCatching { raf.close() }
    }
}

/** 大端游标（UnityFS 头部与 blocksInfo 均为大端） */
internal class Cursor(private val raf: RandomAccessFile) {
    var pos: Long = 0
        private set

    fun seek(p: Long) {
        pos = p
    }

    fun align(n: Int) {
        pos = (pos + n - 1) / n * n
    }

    fun u8(): Int {
        raf.seek(pos)
        val v = raf.read()
        if (v < 0) throw EOFException()
        pos++
        return v
    }

    fun u16be(): Int {
        val b = bytes(2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    fun u32be(): Long {
        val b = bytes(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun i64be(): Long {
        val b = bytes(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        raf.seek(pos)
        raf.readFully(out)
        pos += n
        return out
    }

    fun cstr(): String {
        val sb = StringBuilder()
        while (true) {
            val c = u8()
            if (c == 0) break
            sb.append(c.toChar())
            if (sb.length > 4096) throw IOException("字符串过长")
        }
        return sb.toString()
    }
}
