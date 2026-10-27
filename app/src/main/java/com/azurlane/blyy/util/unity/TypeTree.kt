package com.azurlane.blyy.util.unity

import java.io.IOException

/**
 * Unity 类型树（TypeTree）节点。
 *
 * 注意：Unity 从 2019.2 起不保证资源包内携带类型树，但碧蓝航线 2022.3 构建的包
 * 依然 `enableTypeTree = 1`，因此可以直接按字段名读取 MonoBehaviour 的 `_bytes`。
 */
internal class TtNode(
    val version: Int,
    val level: Int,
    val typeFlags: Int,
    val type: String,
    val name: String,
    val byteSize: Int,
    val index: Int,
    val metaFlag: Int,
    val refTypeHash: Long
) {
    /** metaFlag & 0x4000 (kAlignBytes) → 读取本字段后需要 4 字节对齐 */
    val align: Boolean get() = (metaFlag and 0x4000) != 0
}

/** CommonString 表：类型树里的 type/name 是「相对偏移」，高位为 1 时表示走公共字符串表 */
internal object CommonStrings {
    val table: Map<Long, String> = mapOf(
        0L to "AABB",
        5L to "AnimationClip",
        19L to "AnimationCurve",
        34L to "AnimationState",
        49L to "Array",
        55L to "Base",
        60L to "BitField",
        69L to "bitset",
        76L to "bool",
        81L to "char",
        86L to "ColorRGBA",
        96L to "Component",
        106L to "data",
        111L to "deque",
        117L to "double",
        124L to "dynamic_array",
        138L to "FastPropertyName",
        155L to "first",
        161L to "float",
        167L to "Font",
        172L to "GameObject",
        183L to "Generic Mono",
        196L to "GradientNEW",
        208L to "GUID",
        213L to "GUIStyle",
        222L to "int",
        226L to "list",
        231L to "long long",
        241L to "map",
        245L to "Matrix4x4f",
        256L to "MdFour",
        263L to "MonoBehaviour",
        277L to "MonoScript",
        288L to "m_ByteSize",
        299L to "m_Curve",
        307L to "m_EditorClassIdentifier",
        331L to "m_EditorHideFlags",
        349L to "m_Enabled",
        359L to "m_ExtensionPtr",
        374L to "m_GameObject",
        387L to "m_Index",
        395L to "m_IsArray",
        405L to "m_IsStatic",
        416L to "m_MetaFlag",
        427L to "m_Name",
        434L to "m_ObjectHideFlags",
        452L to "m_PrefabInternal",
        469L to "m_PrefabParentObject",
        490L to "m_Script",
        499L to "m_StaticEditorFlags",
        519L to "m_Type",
        526L to "m_Version",
        536L to "Object",
        543L to "pair",
        548L to "PPtr<Component>",
        564L to "PPtr<GameObject>",
        581L to "PPtr<Material>",
        596L to "PPtr<MonoBehaviour>",
        616L to "PPtr<MonoScript>",
        633L to "PPtr<Object>",
        646L to "PPtr<Prefab>",
        659L to "PPtr<Sprite>",
        672L to "PPtr<TextAsset>",
        688L to "PPtr<Texture>",
        702L to "PPtr<Texture2D>",
        718L to "PPtr<Transform>",
        734L to "Prefab",
        741L to "Quaternionf",
        753L to "Rectf",
        759L to "RectInt",
        767L to "RectOffset",
        778L to "second",
        785L to "set",
        789L to "short",
        795L to "size",
        800L to "SInt16",
        807L to "SInt32",
        814L to "SInt64",
        821L to "SInt8",
        827L to "staticvector",
        840L to "string",
        847L to "TextAsset",
        857L to "TextMesh",
        866L to "Texture",
        874L to "Texture2D",
        884L to "Transform",
        894L to "TypelessData",
        907L to "UInt16",
        914L to "UInt32",
        921L to "UInt64",
        928L to "UInt8",
        934L to "unsigned int",
        947L to "unsigned long long",
        966L to "unsigned short",
        981L to "vector",
        988L to "Vector2f",
        997L to "Vector3f",
        1006L to "Vector4f",
        1015L to "m_ScriptingClassIdentifier",
        1042L to "Gradient",
        1051L to "Type*",
        1057L to "int2_storage",
        1070L to "int3_storage",
        1083L to "BoundsInt",
        1093L to "m_CorrespondingSourceObject",
        1121L to "m_PrefabInstance",
        1138L to "m_PrefabAsset",
        1152L to "FileSize",
        1161L to "Hash128",
    )

    fun resolve(raw: Long): String? = table[raw and 0x7FFFFFFFL]
}

internal object TypeTreeStrings {
    fun decode(stringBuffer: ByteArray, value: Long): String {
        if (value and 0x80000000L != 0L) {
            return CommonStrings.resolve(value) ?: "#common:${value and 0x7FFFFFFFL}"
        }
        val start = value.toInt()
        if (start < 0 || start >= stringBuffer.size) return ""
        var end = start
        while (end < stringBuffer.size && stringBuffer[end] != 0.toByte()) end++
        return String(stringBuffer, start, end - start, Charsets.UTF_8)
    }
}

/**
 * 通用类型树字段读取器（AssetStudio `TypeTreeHelper.ReadStringValue` 的 Kotlin 移植）。
 * 递归读取一个对象，返回「字段名 → 值」的 Map。
 */
internal class TypeTreeValueReader(
    private val nodes: List<TtNode>,
    private val subTreeEnd: IntArray,
    private val data: ByteArray,
    private val littleEndian: Boolean
) {
    private var pos = 0

    fun read(): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var i = 1
        while (i < nodes.size) {
            i = readValue(i, out)
        }
        return out
    }

    private fun alignTo4() {
        pos = (pos + 3) / 4 * 4
    }

    private fun need(n: Int) {
        if (pos + n > data.size) throw IOException("类型树读取越界：pos=$pos need=$n size=${data.size}")
    }

    private fun i8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    private fun i16(): Int {
        need(2)
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        pos += 2
        return if (littleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
    }

    private fun i32raw(): Int {
        need(4)
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF
        val b3 = data[pos + 3].toInt() and 0xFF
        pos += 4
        return if (littleEndian) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    private fun i64raw(): Long {
        need(8)
        var v = 0L
        if (littleEndian) {
            for (k in 7 downTo 0) v = (v shl 8) or (data[pos + k].toLong() and 0xFF)
        } else {
            for (k in 0 until 8) v = (v shl 8) or (data[pos + k].toLong() and 0xFF)
        }
        pos += 8
        return v
    }

    private fun readBytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    private fun readAlignedString(): String {
        alignTo4()
        val len = i32raw()
        if (len < 0 || len > 1 shl 26) throw IOException("字符串长度异常：$len")
        val bytes = readBytes(len)
        alignTo4()
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(i: Int, out: MutableMap<String, Any?>): Int {
        val node = nodes[i]
        val type = node.type
        val name = node.name
        var align = node.align

        when (type) {
            "SInt8", "UInt8", "bool" -> {
                val v = i8()
                out[name] = if (type == "bool") v != 0 else if (type == "SInt8") v.toByte() else v
                if (align) alignTo4()
                return i + 1
            }
            "char", "short", "SInt16", "UInt16", "unsigned short" -> {
                val v = i16()
                out[name] = if (type == "short" || type == "SInt16") v.toShort() else v
                if (align) alignTo4()
                return i + 1
            }
            "int", "SInt32", "UInt32", "unsigned int", "Type*" -> {
                val v = i32raw()
                out[name] = if (type == "UInt32" || type == "unsigned int" || type == "Type*") {
                    v.toLong() and 0xFFFFFFFFL
                } else {
                    v
                }
                if (align) alignTo4()
                return i + 1
            }
            "long long", "SInt64", "UInt64", "unsigned long long", "FileSize" -> {
                out[name] = i64raw()
                if (align) alignTo4()
                return i + 1
            }
            "float" -> {
                out[name] = Float.fromBits(i32raw())
                if (align) alignTo4()
                return i + 1
            }
            "double" -> {
                out[name] = Double.fromBits(i64raw())
                if (align) alignTo4()
                return i + 1
            }
            "string" -> {
                // 字符串自身内部已对齐，跳出整棵子树
                out[name] = readAlignedString()
                return subTreeEnd[i]
            }
            "TypelessData" -> {
                val len = i32raw()
                if (len < 0 || len > 1 shl 28) throw IOException("TypelessData 长度异常：$len")
                out[name] = readBytes(len)
                if (align) alignTo4()
                return subTreeEnd[i]
            }
            "map" -> {
                val end = subTreeEnd[i]
                if (i + 1 < nodes.size && nodes[i + 1].align) align = true
                val count = i32raw()
                if (count < 0 || count > 1 shl 22) throw IOException("map 元素数量异常：$count")
                val list = ArrayList<MutableMap<String, Any?>>(count)
                repeat(count) {
                    var j = i + 2
                    val pair = LinkedHashMap<String, Any?>()
                    while (j < end && nodes[j].level == node.level + 2) {
                        j = readValue(j, pair)
                    }
                    list += pair
                }
                out[name] = list
                if (align) alignTo4()
                return end
            }
            else -> {
                if (i + 1 < nodes.size && nodes[i + 1].type == "Array") {
                    if (nodes[i + 1].align) align = true
                    val count = i32raw()
                    if (count < 0 || count > 1 shl 24) throw IOException("数组元素数量异常：$count")
                    val end = subTreeEnd[i]
                    val elem = i + 3
                    // 字节数组快路径（TextAsset.m_Script、MonoBehaviour._bytes 动辄十几 MB）：
                    // 逐个装箱成 Int 会直接把堆打爆，这里按原始字节读。
                    val elemType = nodes.getOrNull(elem)?.type
                    if (elemType == "UInt8" || elemType == "SInt8") {
                        out[name] = readBytes(count)
                        if (align) alignTo4()
                        return end
                    }
                    val list = ArrayList<Any?>(if (count > 4096) 4096 else count)
                    repeat(count) {
                        val item = LinkedHashMap<String, Any?>()
                        readValue(elem, item)
                        list += if (item.size == 1) item.values.first() else item
                    }
                    out[name] = list
                    if (align) alignTo4()
                    return end
                }
                // 普通结构体
                val end = subTreeEnd[i]
                val map = LinkedHashMap<String, Any?>()
                var j = i + 1
                while (j < end) {
                    j = readValue(j, map)
                }
                out[name] = map
                return end
            }
        }
    }
}
