package com.azurlane.blyy.util.unity

/**
 * TextAsset 的原始内容（**字节保真**，不做任何文本解码）。
 *
 * 碧蓝的 Spine `.skel` 是二进制，Unity 的 TypeTree 却把 `m_Script` 声明成
 * `string`。若按字符串读（UTF-8 解码再编码），所有非 UTF-8 字节都会变成
 * `U+FFFD`，骨骼文件被毁——表现为运行时报 `EOFException` / `String.isEmpty()`
 * 空指针，模型直接加载失败。
 */
internal class UnityTextAsset(val name: String, val bytes: ByteArray)

/**
 * 直接按 Unity 的序列化布局读取 TextAsset：`m_Name`（对齐字符串）+ `m_Script`（对齐字节块）。
 *
 * 两个字段的布局都是「4 字节长度 + 原始数据」，因此 `string` 与
 * `vector<UInt8>`（TypelessData）两种声明可以走同一段解析，避开 TypeTree 的
 * UTF-8 解码；长度字段按序列化文件的端序（[SerializedFile.littleEndian]）解释。
 */
internal object UnityTextAssetReader {

    fun read(sf: SerializedFile, def: SerializedFile.ObjectDef): UnityTextAsset? {
        val payload = runCatching { sf.objectBytes(def) }.getOrNull() ?: return null
        if (payload.size < 8) return null
        val cursor = LayoutCursor(payload, sf.littleEndian)
        val name = cursor.alignedString() ?: return null
        val script = cursor.alignedBytes() ?: return null
        return UnityTextAsset(name, script)
    }

    private class LayoutCursor(private val data: ByteArray, private val littleEndian: Boolean) {
        private var pos = 0

        private fun align4() {
            pos = (pos + 3) and 3.inv()
        }

        private fun i32(): Int? {
            if (pos + 4 > data.size) return null
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

        fun alignedString(): String? {
            val bytes = alignedBytes() ?: return null
            return String(bytes, Charsets.UTF_8)
        }

        fun alignedBytes(): ByteArray? {
            align4()
            val length = i32() ?: return null
            if (length < 0 || length > 1 shl 28 || pos + length > data.size) return null
            val out = data.copyOfRange(pos, pos + length)
            pos += length
            return out
        }
    }
}
