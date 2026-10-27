package com.azurlane.blyy.util.unity

import java.io.IOException

/**
 * Unity 序列化文件（SerializedFile，即 UnityFS 内的 `CAB-xxxxx` 节点）解析器。
 *
 * 布局（版本 22，碧蓝航线 2022.3.62f3 构建）：
 * ```
 * [文件头 48B，**大端**]
 *   u32 metadataSize(旧) u32 fileSize(旧) u32 version u32 dataOffset(旧)
 *   u8 endianess + 3B 保留
 *   u32 metadataSize  i64 fileSize  i64 dataOffset  i64 unknown
 * [metadata，长度 metadataSize，**按 endianess 字节决定大小端**]
 *   cstr unityVersion | i32 targetPlatform | u8 enableTypeTree | i32 typeCount
 *   每类型：i32 classID | u8 isStripped | i16 scriptTypeIndex
 *           [classID==114 时 16B scriptID] | 16B oldTypeHash
 *           [enableTypeTree 时 TypeTree 节点表 + 字符串表]
 *           i32 依赖数 + 32B*deps
 *   i32 objectCount
 *   每对象：align4 | i64 pathID | i64 byteStart | u32 byteSize | i32 typeID
 * ```
 * 对象数据位于 `byteStart + dataOffset`（相对本文件起点）。
 */
internal class SerializedFile private constructor(
    val image: BundleImage,
    val node: UnityFsBundle.Node,
    val version: Int,
    val unityVersion: String,
    val targetPlatform: Int,
    val littleEndian: Boolean,
    val types: List<TypeDef>,
    val objects: List<ObjectDef>
) {

    internal class TypeDef(
        val classId: Int,
        val name: String,
        val nodes: List<TtNode>,
        val subTreeEnd: IntArray
    )

    internal class ObjectDef(
        val pathID: Long,
        val byteStart: Long,
        val byteSize: Int,
        val typeId: Int
    ) {
        var classId: Int = 0
    }

    fun objectsOfClass(classId: Int): List<ObjectDef> =
        objects.filter { types.getOrNull(it.typeId)?.classId == classId }

    fun objectBytes(def: ObjectDef): ByteArray = image.read(node, def.byteStart, def.byteSize)

    fun readValues(def: ObjectDef): MutableMap<String, Any?> {
        val type = types.getOrNull(def.typeId) ?: throw IOException("对象类型越界：typeId=${def.typeId}")
        if (type.nodes.isEmpty()) throw IOException("该资源包未携带类型树，无法解析字段")
        val payload = objectBytes(def)
        return TypeTreeValueReader(type.nodes, type.subTreeEnd, payload, littleEndian).read()
    }

    /** 只读取前 [length] 字节，用于快速判断魔数（如 MOC3） */
    fun peek(def: ObjectDef, length: Int): ByteArray =
        image.read(node, def.byteStart, minOf(length, def.byteSize))

    companion object {
        const val CLASS_TEXT_ASSET = 49
        const val CLASS_TEXTURE2D = 28
        const val CLASS_MONO_BEHAVIOUR = 114
        const val CLASS_ANIMATION_CLIP = 74
        const val CLASS_ASSET_BUNDLE = 142

        fun open(image: BundleImage, node: UnityFsBundle.Node): SerializedFile {
            val header = image.read(node, 0, 48)
            if (header.size < 48) throw IOException("序列化文件头不完整")

            fun beU32(at: Int): Long = ((header[at].toLong() and 0xFF) shl 24) or
                ((header[at + 1].toLong() and 0xFF) shl 16) or
                ((header[at + 2].toLong() and 0xFF) shl 8) or
                (header[at + 3].toLong() and 0xFF)

            fun beI64(at: Int): Long {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (header[at + i].toLong() and 0xFF)
                return v
            }

            val version = beU32(8).toInt()
            if (version < 9 || version > 99) throw IOException("不支持的序列化文件版本：$version")

            val metadataSize: Long
            val dataOffset: Long
            val metadataStart: Long
            if (version >= 22) {
                metadataSize = beU32(20)
                dataOffset = beI64(32)
                metadataStart = 48
            } else {
                metadataSize = beU32(0)
                dataOffset = beU32(12)
                metadataStart = 20
            }
            if (metadataSize <= 0 || metadataSize > 128L * 1024 * 1024) {
                throw IOException("metadata 长度异常：$metadataSize")
            }
            if (metadataStart + metadataSize > node.size) throw IOException("metadata 超出节点范围")

            val endianByte = if (version >= 9) header[16].toInt() and 0xFF else 0
            val little = endianByte == 0

            val meta = image.read(node, metadataStart, metadataSize.toInt())
            val r = MetaReader(meta, little)

            val unityVersion = r.cstr()
            val targetPlatform = r.i32()
            val enableTypeTree = if (version >= 13) r.u8() != 0 else true
            val typeCount = r.i32()
            if (typeCount < 0 || typeCount > 100_000) throw IOException("类型数量异常：$typeCount")

            val types = ArrayList<TypeDef>(typeCount)
            repeat(typeCount) {
                val classId = r.i32()
                r.u8() // isStripped
                r.i16() // scriptTypeIndex
                if (classId == CLASS_MONO_BEHAVIOUR) r.skip(16) // scriptID
                r.skip(16) // oldTypeHash

                var nodes: List<TtNode> = emptyList()
                if (enableTypeTree) {
                    val nodeCount = r.i32()
                    val stringBufferSize = r.i32()
                    if (nodeCount < 0 || nodeCount > 100_000) throw IOException("类型树节点数量异常：$nodeCount")
                    if (stringBufferSize < 0 || stringBufferSize > 16 * 1024 * 1024) {
                        throw IOException("类型树字符串表长度异常：$stringBufferSize")
                    }
                    val raw = ArrayList<TtNode>(nodeCount)
                    val pendingOffsets = ArrayList<Pair<Long, Long>>(nodeCount)
                    repeat(nodeCount) {
                        val v = r.u16()
                        val level = r.u8()
                        val typeFlags = r.u8()
                        val typeStrOffset = r.u32()
                        val nameStrOffset = r.u32()
                        val byteSize = r.i32()
                        val index = r.i32()
                        val metaFlag = r.i32()
                        val refTypeHash = if (version >= 19) r.i64() else 0L
                        raw += TtNode(
                            version = v,
                            level = level,
                            typeFlags = typeFlags,
                            type = "",
                            name = "",
                            byteSize = byteSize,
                            index = index,
                            metaFlag = metaFlag,
                            refTypeHash = refTypeHash
                        )
                        // type/name 需要等字符串表读完才能解引用
                        pendingOffsets += typeStrOffset to nameStrOffset
                    }
                    val stringBuffer = r.bytes(stringBufferSize)
                    nodes = raw.mapIndexed { idx, n ->
                        val (typeOff, nameOff) = pendingOffsets[idx]
                        TtNode(
                            version = n.version,
                            level = n.level,
                            typeFlags = n.typeFlags,
                            type = TypeTreeStrings.decode(stringBuffer, typeOff),
                            name = TypeTreeStrings.decode(stringBuffer, nameOff),
                            byteSize = n.byteSize,
                            index = n.index,
                            metaFlag = n.metaFlag,
                            refTypeHash = n.refTypeHash
                        )
                    }
                    pendingOffsets.clear()
                }
                if (version >= 21) {
                    val depCount = r.i32()
                    if (depCount < 0 || depCount > 100_000) throw IOException("依赖数量异常：$depCount")
                    r.skip(32 * depCount)
                }

                val subEnd = IntArray(nodes.size) { idx ->
                    val level = nodes[idx].level
                    var j = idx + 1
                    while (j < nodes.size && nodes[j].level > level) j++
                    j
                }
                types += TypeDef(
                    classId = classId,
                    name = nodes.firstOrNull()?.type ?: "",
                    nodes = nodes,
                    subTreeEnd = subEnd
                )
            }

            val objectCount = r.i32()
            if (objectCount < 0 || objectCount > 2_000_000) throw IOException("对象数量异常：$objectCount")
            val objects = ArrayList<ObjectDef>(minOf(objectCount, 4096))
            repeat(objectCount) {
                r.align()
                val pathID = r.i64()
                val byteStart = r.i64() + dataOffset
                val byteSize = r.u32().toInt()
                val typeId = r.i32()
                if (byteSize < 0) throw IOException("对象长度异常：$byteSize")
                if (typeId < 0 || typeId >= types.size) throw IOException("对象类型越界：$typeId")
                objects += ObjectDef(pathID, byteStart, byteSize, typeId).apply {
                    classId = types[typeId].classId
                }
            }

            return SerializedFile(
                image = image,
                node = node,
                version = version,
                unityVersion = unityVersion,
                targetPlatform = targetPlatform,
                littleEndian = little,
                types = types,
                objects = objects
            )
        }

    }
}

/** metadata 读取器（字节数组 + 大小端） */
internal class MetaReader(private val data: ByteArray, private val little: Boolean) {
    var pos: Int = 0
        private set

    fun skip(n: Int) {
        if (n < 0 || pos + n > data.size) throw IOException("metadata 越界：skip $n (pos=$pos size=${data.size})")
        pos += n
    }

    fun align(n: Int = 4) {
        pos = (pos + n - 1) / n * n
    }

    fun u8(): Int {
        if (pos >= data.size) throw IOException("metadata 越界")
        return data[pos++].toInt() and 0xFF
    }

    fun i16(): Int {
        val b0 = u8()
        val b1 = u8()
        return if (little) (b1 shl 8) or b0 else (b0 shl 8) or b1
    }

    fun u16(): Int = i16()

    fun i32(): Int {
        val b0 = u8()
        val b1 = u8()
        val b2 = u8()
        val b3 = u8()
        return if (little) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun i64(): Long {
        var v = 0L
        if (little) {
            val bytes = ByteArray(8) { u8().toByte() }
            for (i in 7 downTo 0) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        } else {
            repeat(8) { v = (v shl 8) or u8().toLong() }
        }
        return v
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > data.size) throw IOException("metadata 越界：bytes $n")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun cstr(): String {
        var end = pos
        while (end < data.size && data[end] != 0.toByte()) end++
        if (end >= data.size) throw IOException("metadata 字符串未终止")
        val s = String(data, pos, end - pos, Charsets.UTF_8)
        pos = end + 1
        return s
    }
}
