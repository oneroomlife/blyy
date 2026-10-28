package com.azurlane.blyy.util.unity

import android.util.Log

/**
 * Unity `Mesh` 对象（立绘包里的 `Xxx-mesh`）。
 *
 * 【为什么需要它】
 * 碧蓝航线的静态立绘并不是「一张完整图」，而是**运行时按网格拼装的图集**：
 * `_tex` 包里的贴图把人物、舰装、背景切成几十上百个碎片，紧密打包成一张
 * 1024×556 之类的横图；真正的画面由同名 `Mesh` 的顶点（画面坐标）与 UV（图集坐标）
 * 一一对应还原出来。直接展示图集本身，看到的就是「碎片乱飞、错位」的拼贴画。
 *
 * 本类只负责把 `Mesh` 里的三条数据流读出来：
 * - 顶点位置（`m_VertexData` 的 Position 通道，Unity 网格空间，y 轴向上）
 * - 顶点 UV（UV0 通道，0..1，v 轴向上，与 `.atlas` 一致的纹理约定）
 * - 三角索引（`m_IndexBuffer`，UInt16 / UInt32，按子网格顺序拼接）
 *
 * 顶点数据的**行字节序**与序列化文件一致（Android 为小端），这里按
 * [SerializedFile.littleEndian] 解释，避免把位置读成天文数字。
 */
internal class UnityMesh(
    val name: String,
    /** x0,y0,x1,y1… 网格单位（通常是 UI 像素） */
    val positions: FloatArray,
    /** u0,v0,u1,v1… 0..1 */
    val uvs: FloatArray,
    val indices: IntArray,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {

    val vertexCount: Int get() = positions.size / 2
    val triangleCount: Int get() = indices.size / 3
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY

    /** 顶点/UV 结构是否足以还原像素（缺 UV 或没有三角形时由调用方退回整张图集） */
    val usable: Boolean
        get() = vertexCount >= 3 && uvs.size == positions.size && triangleCount >= 1 &&
            width > 0f && height > 0f

    /** 三角索引是否全部落在顶点范围内（Unity 偶尔会带退化索引） */
    fun hasValidIndices(): Boolean {
        if (indices.isEmpty()) return false
        for (value in indices) if (value < 0 || value >= vertexCount) return false
        return true
    }

    override fun toString(): String =
        "UnityMesh($name ${vertexCount}vt ${triangleCount}tri ${width.toInt()}x${height.toInt()})"

    private class Channel(val offset: Int, val format: Int, val dimension: Int) {
        val bytes: Int get() = dimension * componentBytes(format)
    }

    companion object {
        private const val TAG = "UnityMesh"

        /** 顶点属性通道下标（Unity `VertexAttribute` 枚举） */
        private const val CHANNEL_POSITION = 0
        private const val CHANNEL_UV0 = 4

        /** `VertexAttributeFormat` */
        private const val FORMAT_FLOAT32 = 0
        private const val FORMAT_FLOAT16 = 1
        private const val FORMAT_UNORM16 = 4
        private const val FORMAT_SNORM16 = 5
        private const val FORMAT_UINT16 = 8
        private const val FORMAT_SINT16 = 9

        private fun componentBytes(format: Int): Int = when (format) {
            FORMAT_FLOAT32 -> 4
            FORMAT_FLOAT16, FORMAT_UNORM16, FORMAT_SNORM16,
            FORMAT_UINT16, FORMAT_SINT16 -> 2
            else -> 4
        }

        /** 解析失败返回 null（调用方退回整张图集，不阻断导入） */
        fun parse(sf: SerializedFile, def: SerializedFile.ObjectDef): UnityMesh? = runCatching {
            parseOrThrow(sf, def)
        }.onFailure {
            Log.w(TAG, "网格解析失败：pathID=${def.pathID}", it)
        }.getOrNull()

        private fun parseOrThrow(sf: SerializedFile, def: SerializedFile.ObjectDef): UnityMesh? {
            val values = sf.readValues(def)
            val name = values["m_Name"] as? String ?: ""
            val vertexData = values["m_VertexData"] as? Map<*, *> ?: return null
            val vertexCount = UnityValues.int(vertexData["m_VertexCount"]) ?: 0
            if (vertexCount <= 0) return null

            val data = UnityValues.toBytes(vertexData["m_DataSize"]) ?: return null
            if (data.isEmpty()) return null

            val channels = (vertexData["m_Channels"] as? List<*>).orEmpty()
            val position = channel(channels, CHANNEL_POSITION) ?: return null
            val uv = channel(channels, CHANNEL_UV0) ?: return null

            // 步长：优先按通道声明推导（Unity 会补齐 4/16 字节对齐，dataSize/vertexCount 同样可靠）
            var stride = 0
            channels.filterIsInstance<Map<*, *>>().forEach { raw ->
                val offset = UnityValues.int(raw["offset"]) ?: 0
                val dimension = UnityValues.int(raw["dimension"]) ?: 0
                if (dimension <= 0) return@forEach
                val format = UnityValues.int(raw["format"]) ?: FORMAT_FLOAT32
                stride = maxOf(stride, offset + dimension * componentBytes(format))
            }
            if (stride <= 0) stride = data.size / vertexCount
            if (stride <= 0 || stride.toLong() * vertexCount > data.size) return null

            val little = sf.littleEndian
            val positions = FloatArray(vertexCount * 2)
            val uvs = FloatArray(vertexCount * 2)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until vertexCount) {
                val base = i * stride
                val px = component(data, base + position.offset, position.format, little)
                val py = component(data, base + position.offset + componentBytes(position.format), position.format, little)
                if (!px.isFinite() || !py.isFinite()) return null
                positions[i * 2] = px
                positions[i * 2 + 1] = py
                uvs[i * 2] = component(data, base + uv.offset, uv.format, little)
                uvs[i * 2 + 1] = component(data, base + uv.offset + componentBytes(uv.format), uv.format, little)
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
            }

            val indices = readIndices(values, little) ?: return null
            val mesh = UnityMesh(name, positions, uvs, indices, minX, minY, maxX, maxY)
            if (!mesh.hasValidIndices()) return null
            Log.i(TAG, "网格就绪：$mesh")
            return mesh
        }

        private fun channel(channels: List<*>, index: Int): Channel? {
            val raw = channels.getOrNull(index) as? Map<*, *> ?: return null
            val dimension = UnityValues.int(raw["dimension"]) ?: 0
            if (dimension <= 0) return null
            return Channel(
                offset = UnityValues.int(raw["offset"]) ?: 0,
                format = UnityValues.int(raw["format"]) ?: FORMAT_FLOAT32,
                dimension = dimension
            )
        }

        /**
         * 读取三角索引。
         *
         * `m_IndexFormat`：0 = UInt16，1 = UInt32。子网格的 `firstByte` 是
         * **字节偏移**（不是下标）——漏了这层换算会把第一个子网格之后的索引整段读错，
         * 表现为「只有一块区域拼对，其余散架」。
         */
        private fun readIndices(values: Map<String, Any?>, little: Boolean): IntArray? {
            val buffer = UnityValues.toBytes(values["m_IndexBuffer"]) ?: return null
            if (buffer.isEmpty()) return null
            val wide = (UnityValues.int(values["m_IndexFormat"]) ?: 0) == 1
            val step = if (wide) 4 else 2

            val subMeshes = (values["m_SubMeshes"] as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
            if (subMeshes.isEmpty()) return null

            var total = 0
            subMeshes.forEach { total += UnityValues.int(it["indexCount"]) ?: 0 }
            if (total <= 0 || total > 8_000_000) return null

            val out = IntArray(total)
            var cursor = 0
            for (sub in subMeshes) {
                val count = UnityValues.int(sub["indexCount"]) ?: 0
                val firstByte = UnityValues.int(sub["firstByte"]) ?: 0
                if (count <= 0) continue
                if (firstByte < 0 || firstByte + count * step > buffer.size) continue
                for (i in 0 until count) {
                    val at = firstByte + i * step
                    out[cursor++] = if (wide) {
                        readInt32(buffer, at, little)
                    } else {
                        readUInt16(buffer, at, little)
                    }
                }
            }
            if (cursor == 0) return null
            return if (cursor == out.size) out else out.copyOf(cursor)
        }

        private fun readUInt16(data: ByteArray, at: Int, little: Boolean): Int {
            val b0 = data[at].toInt() and 0xFF
            val b1 = data[at + 1].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        private fun readInt32(data: ByteArray, at: Int, little: Boolean): Int {
            val b0 = data[at].toInt() and 0xFF
            val b1 = data[at + 1].toInt() and 0xFF
            val b2 = data[at + 2].toInt() and 0xFF
            val b3 = data[at + 3].toInt() and 0xFF
            return if (little) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        /** 按 `VertexAttributeFormat` 解一个通道分量 */
        private fun component(data: ByteArray, at: Int, format: Int, little: Boolean): Float {
            if (at < 0 || at + componentBytes(format) > data.size) return 0f
            return when (format) {
                FORMAT_FLOAT32 -> Float.fromBits(readInt32(data, at, little))
                FORMAT_FLOAT16 -> halfToFloat(readUInt16(data, at, little))
                FORMAT_UNORM16 -> readUInt16(data, at, little) / 65535f
                FORMAT_SNORM16 -> (readUInt16(data, at, little).toShort().toInt() / 32767f)
                FORMAT_UINT16 -> readUInt16(data, at, little).toFloat()
                FORMAT_SINT16 -> readUInt16(data, at, little).toShort().toFloat()
                else -> 0f
            }
        }

        /** IEEE 754 半精度 → 单精度 */
        private fun halfToFloat(bits: Int): Float {
            val sign = (bits ushr 15) and 0x1
            val exponent = (bits ushr 10) and 0x1F
            val mantissa = bits and 0x3FF
            val value = when {
                exponent == 0 -> mantissa * 5.9604645e-8f
                exponent == 31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (mantissa + 1024) * Math.pow(2.0, (exponent - 25).toDouble()).toFloat()
            }
            return if (sign == 1) -value else value
        }
    }
}
