package com.azurlane.blyy.util.unity

/**
 * 纯 Kotlin 的 ASTC（LDR / HDR）软件解码器。
 *
 * 为什么需要它：碧蓝航线 `char`（SD 小人）与 `loadingbg`（过场图）的贴图是
 * **ASTC 6x6**，其余目录是 ASTC 4x4。解码走的是「离屏 EGL + `glCompressedTexImage2D`
 * + `glReadPixels`」，但很多设备/环境并不真的接受 6x6：
 * - 模拟器（SwiftShader / 部分 Goldfish）扩展串里有 `astc_ldr`，实测只认 4x4；
 * - 个别低端 GPU 只实现 4x4 / 8x8 子集；
 * - 后台线程偶发拿不到可用的 GLES3 上下文。
 *
 * 表现就是「SD 小人与过场图导入后黑屏 / 导入失败」，而其他资源正常。
 * 这里把 ASTC 解码搬到 CPU 上，**不依赖任何 GL 能力**，作为 GL 路径的兜底，
 * 保证这两类资源在任何设备上都能出图。
 *
 * 实现来源：Texture2DDecoder (MIT) `astc.cpp`（与 UnityPy / AssetStudio 同源的
 * 移植实现），逐函数对译，包含：
 * - 2D 全部块尺寸 4x4 / 5x5 / 6x6 / 8x5 / 8x8 / 10x5 / 10x6 / 10x8 / 10x10 / 12x10 / 12x12；
 * - 单/双平面（dual plane）权重、1~4 个分区（partition）；
 * - 16 种 CEM（颜色端点模式，含 7/11/15 号 HDR 模式与 14 号 HDR+alpha）；
 * - `void-extent` 纯色块与保留块（品红）。
 *
 * 与参考实现的两点差异（都不影响参考实现能解出的块）：
 * 1. 所有按位读取都在 16 字节块内做边界收敛，越界位按 0 处理（参考实现是读越界内存，
 *    结果未定义）；因此 `decodeBlock` 对**损坏块**永远返回确定值，不会抛异常。
 * 2. 权重/端点数组预分配并复用，避免每块分配（一张 1920x1080 的 6x6 贴图有 57600 块）。
 *
 * 正确性由 `AstcDecoderFixtureTest` 用 1952 条块级黄金样本（sha256 对拍）与 6 张整图
 * 样本保证；样本由参考实现生成，见 `app/src/test/resources/astc/`。
 */
internal object AstcDecoder {

    /** ASTC 的块尺寸上限（12x12） */
    const val MAX_BLOCK_SIDE = 12

    /** 单块字节数 */
    const val BLOCK_BYTES = 16

    // ---------------------------------------------------------------- 参数表

    private val WEIGHT_PREC_A = intArrayOf(0, 0, 0, 3, 0, 5, 3, 0, 0, 0, 5, 3, 0, 5, 3, 0)
    private val WEIGHT_PREC_B = intArrayOf(0, 0, 1, 0, 2, 0, 1, 3, 0, 0, 1, 2, 4, 2, 3, 5)
    private val CEM_A = intArrayOf(0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 0, 0)
    private val CEM_B = intArrayOf(8, 6, 5, 7, 5, 4, 6, 4, 3, 5, 3, 2, 4, 2, 1, 3, 1, 2, 1)

    /** 端点反量化用的 trits / quints 基数（索引 = [CEM_B]） */
    private val ENDPOINT_TRITS = intArrayOf(0, 204, 93, 44, 22, 11, 5)
    private val ENDPOINT_QUINTS = intArrayOf(0, 113, 54, 26, 13, 6)

    /** 反量化 trits 时各元素在块内的位偏移 */
    private val MT = intArrayOf(0, 2, 4, 5, 7)

    /** 反量化 quints 时各元素在块内的位偏移 */
    private val MQ = intArrayOf(0, 3, 5)

    /** `FuncTableC`：RGB 用；true = HDR 端点插值 */
    private val CEM_HDR_RGB = booleanArrayOf(
        false, false, true, true, false, false,
        false, true, false, false, false, true,
        false, false, true, true
    )

    /** `FuncTableA`：Alpha 用；true = HDR 端点插值 */
    private val CEM_HDR_ALPHA = booleanArrayOf(
        false, false, true, true, false, false,
        false, true, false, false, false, true,
        false, false, false, true
    )

    // ---------------------------------------------------------------- 公共 API

    /**
     * 解码一整张贴图。
     *
     * @param data   压缩数据（自 `offset` 起，长度需 ≥ 块数 × 16）
     * @param offset 数据起点（`ByteBuffer` 已切片的场景传 0）
     * @param width  贴图宽（像素）
     * @param height 贴图高（像素）
     * @param bw     块宽（4/5/6/8/10/12）
     * @param bh     块高（4/5/6/8/10/12）
     * @return `outWidth × outHeight` 的 ARGB_8888 像素
     */
    fun decode(
        data: ByteArray,
        offset: Int = 0,
        width: Int,
        height: Int,
        bw: Int,
        bh: Int,
        outWidth: Int = width,
        outHeight: Int = height,
        flipVertical: Boolean = false
    ): IntArray {
        require(width > 0 && height > 0) { "ASTC 尺寸非法：${width}x$height" }
        require(outWidth > 0 && outHeight > 0) { "输出尺寸非法：${outWidth}x$outHeight" }
        require(bw in 4..MAX_BLOCK_SIDE && bh in 4..MAX_BLOCK_SIDE) { "ASTC 块尺寸非法：${bw}x$bh" }

        val blocksX = (width + bw - 1) / bw
        val blocksY = (height + bh - 1) / bh
        val need = blocksX * blocksY * BLOCK_BYTES
        if (data.size - offset < need) {
            throw IllegalArgumentException("ASTC 数据不足：需要 $need 字节，实际 ${data.size - offset}（${width}x$height）")
        }

        val out = IntArray(outWidth * outHeight)
        val block = IntArray(bw * bh)
        val state = BlockState()
        val scaled = outWidth != width || outHeight != height
        var src = offset
        for (by in 0 until blocksY) {
            val y0 = by * bh
            for (bx in 0 until blocksX) {
                decodeBlockInto(data, src, bw, bh, block, state)
                src += BLOCK_BYTES
                val x0 = bx * bw
                if (!scaled) {
                    val copyW = minOf(bw, width - x0)
                    var row = 0
                    var blockIdx = 0
                    while (row < bh && y0 + row < height) {
                        // 源数据自下而上：翻转时第 srcRow 行要写到 height-1-srcRow
                        val destRow = if (flipVertical) height - 1 - (y0 + row) else y0 + row
                        System.arraycopy(block, blockIdx, out, destRow * width + x0, copyW)
                        row++
                        blockIdx += bw
                    }
                } else {
                    // 边解边降采样：超大贴图（如 4096² 的立绘）在弱 GPU 上走 CPU 解码时，
                    // 先解成全尺寸再缩放会同时占用「全尺寸 int[] + 位图」两份内存，
                    // 这里直接按目标像素取最近源像素写入，峰值只剩目标尺寸。
                    val sxEnd = minOf(x0 + bw, width)
                    val syEnd = minOf(y0 + bh, height)
                    val dxStart = ceilDiv(x0 * outWidth, width)
                    val dxEnd = ceilDiv(sxEnd * outWidth, width)
                    val dyStart = ceilDiv(y0 * outHeight, height)
                    val dyEnd = ceilDiv(syEnd * outHeight, height)
                    for (dy in dyStart until dyEnd) {
                        val sy = dy * height / outHeight
                        val localRow = sy - y0
                        if (localRow < 0 || localRow >= bh) continue
                        val rowBase = localRow * bw
                        var dx = dxStart
                        val destRow = if (flipVertical) outHeight - 1 - dy else dy
                        val destBase = destRow * outWidth
                        while (dx < dxEnd) {
                            val sx = dx * width / outWidth
                            val localCol = sx - x0
                            if (localCol in 0 until bw) {
                                out[destBase + dx] = block[rowBase + localCol]
                            }
                            dx++
                        }
                    }
                }
            }
        }
        return out
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /**
     * 解码单个块（16 字节），返回 `bw × bh` 的 ARGB 像素（行优先）。
     *
     * 单测用它逐块对拍黄金样本；生产代码走 [decode]。
     */
    fun decodeBlock(block: ByteArray, offset: Int = 0, bw: Int, bh: Int): IntArray {
        val out = IntArray(bw * bh)
        decodeBlockInto(block, offset, bw, bh, out, BlockState())
        return out
    }


    /** 该块尺寸在当前实现下是否可解（全部 2D 块尺寸都支持，保留给调用方做能力声明） */
    fun supports(bw: Int, bh: Int): Boolean = bw in 4..MAX_BLOCK_SIDE && bh in 4..MAX_BLOCK_SIDE

    // ---------------------------------------------------------------- 块解码

    /**
     * 每块都要用到的临时数组。
     *
     * 一张 6x6 的 1080p 贴图有 57600 块，若每块都 new 一遍（权重表 288 个 int、
     * 分区表 144 个）会产生上百 MB 垃圾，GC 直接卡住导入进度。
     */
    private class BlockState {
        var width = 0
        var height = 0
        var partNum = 1
        var dualPlane = false
        var planeSelector = 0
        var weightRange = 0
        var weightNum = 0
        var cemRange = 0
        var endpointValueNum = 0
        val cem = IntArray(4)
        val endpoints = Array(4) { IntArray(8) }
        /** 每块像素的 1~2 个平面权重（已插值到像素） */
        val weights = Array(MAX_BLOCK_SIDE * MAX_BLOCK_SIDE) { IntArray(2) }
        val partition = IntArray(MAX_BLOCK_SIDE * MAX_BLOCK_SIDE)
        /** `decode_intseq` 的输出：量化位 / 非量化部分（权重网格最大 12x12x2） */
        val seqBits = IntArray(MAX_BLOCK_SIDE * MAX_BLOCK_SIDE * 2)
        val seqNon = IntArray(MAX_BLOCK_SIDE * MAX_BLOCK_SIDE * 2)
        /** 端点值（最多 32 个） */
        val ev = IntArray(40)
        /** 反量化后的权重值（最大 288 个） */
        val wv = IntArray(MAX_BLOCK_SIDE * MAX_BLOCK_SIDE * 2)
    }

    private fun decodeBlockInto(
        block: ByteArray,
        off: Int,
        bw: Int,
        bh: Int,
        out: IntArray,
        state: BlockState
    ) {
        val b0 = block[off].toInt() and 0xFF
        val b1 = block[off + 1].toInt() and 0xFF
        val pixels = bw * bh

        // void-extent：整块一个颜色（legal 的纯色块，游戏里大量使用）
        if (b0 == 0xFC && (b1 and 1) == 1) {
            val c = if (b1 and 2 != 0) {
                color(
                    halfToByte(block, off + 8),
                    halfToByte(block, off + 10),
                    halfToByte(block, off + 12),
                    halfToByte(block, off + 14)
                )
            } else {
                color(
                    block[off + 9].toInt() and 0xFF,
                    block[off + 11].toInt() and 0xFF,
                    block[off + 13].toInt() and 0xFF,
                    block[off + 15].toInt() and 0xFF
                )
            }
            java.util.Arrays.fill(out, 0, pixels, c)
            return
        }
        // 保留块：规范要求解成「品红」，与 GL 驱动行为一致
        if (((b0 and 0xC3) == 0xC0 && (b1 and 1) == 1) || (b0 and 0x0F) == 0) {
            java.util.Arrays.fill(out, 0, pixels, color(255, 0, 255, 255))
            return
        }

        val lo = readLongLe(block, off)
        val hi = readLongLe(block, off + 8)
        decodeBlockParams(b0, b1, block, off, state)
        decodeEndpoints(lo, hi, state)
        decodeWeights(lo, hi, bw, bh, state)
        if (state.partNum > 1) selectPartition(lo, hi, bw, bh, state)
        applyColor(bw, bh, out, state)
    }

    /** 块头解析：块尺寸 / 权重网格 / 分区数 / CEM / 端点位数 */
    private fun decodeBlockParams(b0: Int, b1: Int, block: ByteArray, off: Int, s: BlockState) {
        s.dualPlane = (b1 and 4) != 0
        s.weightRange = ((b0 ushr 4) and 1) or ((b1 shl 2) and 8)

        val u16 = b0 or (b1 shl 8)
        if (b0 and 3 != 0) {
            s.weightRange = s.weightRange or ((b0 shl 1) and 6)
            when (b0 and 0xC) {
                0 -> {
                    s.width = ((u16 ushr 7) and 3) + 4
                    s.height = ((b0 ushr 5) and 3) + 2
                }
                4 -> {
                    s.width = ((u16 ushr 7) and 3) + 8
                    s.height = ((b0 ushr 5) and 3) + 2
                }
                8 -> {
                    s.width = ((b0 ushr 5) and 3) + 2
                    s.height = ((u16 ushr 7) and 3) + 8
                }
                else -> {
                    if (b1 and 1 != 0) {
                        s.width = ((b0 ushr 7) and 1) + 2
                        s.height = ((b0 ushr 5) and 3) + 2
                    } else {
                        s.width = ((b0 ushr 5) and 3) + 2
                        s.height = ((b0 ushr 7) and 1) + 6
                    }
                }
            }
        } else {
            s.weightRange = s.weightRange or ((b0 ushr 1) and 6)
            when (u16 and 0x180) {
                0 -> {
                    s.width = 12
                    s.height = ((b0 ushr 5) and 3) + 2
                }
                0x80 -> {
                    s.width = ((b0 ushr 5) and 3) + 2
                    s.height = 12
                }
                0x100 -> {
                    s.width = ((b0 ushr 5) and 3) + 6
                    s.height = ((b1 ushr 1) and 3) + 6
                    s.dualPlane = false
                    s.weightRange = s.weightRange and 7
                }
                else -> {
                    s.width = if (b0 and 0x20 != 0) 10 else 6
                    s.height = if (b0 and 0x20 != 0) 6 else 10
                }
            }
        }

        s.partNum = ((b1 ushr 3) and 3) + 1
        s.weightNum = s.width * s.height * if (s.dualPlane) 2 else 1

        val weightBits = when (WEIGHT_PREC_A[s.weightRange]) {
            3 -> s.weightNum * WEIGHT_PREC_B[s.weightRange] + (s.weightNum * 8 + 4) / 5
            5 -> s.weightNum * WEIGHT_PREC_B[s.weightRange] + (s.weightNum * 7 + 2) / 3
            else -> s.weightNum * WEIGHT_PREC_B[s.weightRange]
        }

        var cemBase = 0
        var configBits: Int
        if (s.partNum == 1) {
            s.cem[0] = (u16Le(block, off + 1) ushr 5) and 0xF
            configBits = 17
        } else {
            cemBase = (u16Le(block, off + 2) ushr 7) and 3
            if (cemBase == 0) {
                val cem = (block[off + 3].toInt() ushr 1) and 0xF
                for (i in 0 until s.partNum) s.cem[i] = cem
                configBits = 29
            } else {
                for (i in 0 until s.partNum) {
                    s.cem[i] = ((((block[off + 3].toInt() ushr (i + 1)) and 1) + cemBase - 1) shl 2)
                }
                val wb = weightBits
                when (s.partNum) {
                    2 -> {
                        s.cem[0] = s.cem[0] or ((block[off + 3].toInt() ushr 3) and 3)
                        s.cem[1] = s.cem[1] or getBits32(block, off, 126 - wb, 2)
                    }
                    3 -> {
                        s.cem[0] = s.cem[0] or ((block[off + 3].toInt() ushr 4) and 1)
                        s.cem[0] = s.cem[0] or (getBits32(block, off, 122 - wb, 2) and 2)
                        s.cem[1] = s.cem[1] or getBits32(block, off, 124 - wb, 2)
                        s.cem[2] = s.cem[2] or getBits32(block, off, 126 - wb, 2)
                    }
                    else -> {
                        for (i in 0 until 4) {
                            s.cem[i] = s.cem[i] or getBits32(block, off, 120 + i * 2 - wb, 2)
                        }
                    }
                }
                configBits = 25 + s.partNum * 3
            }
        }

        if (s.dualPlane) {
            configBits += 2
            val bit = if (cemBase != 0) 130 - weightBits - s.partNum * 3 else 126 - weightBits
            s.planeSelector = getBits32(block, off, bit, 2)
        }

        val remainBits = 128 - configBits - weightBits
        s.endpointValueNum = 0
        for (i in 0 until s.partNum) {
            s.endpointValueNum += ((s.cem[i] ushr 1) and 6) + 2
        }

        s.cemRange = CEM_A.size - 1
        for (i in CEM_A.indices) {
            val endpointBits = when (CEM_A[i]) {
                3 -> s.endpointValueNum * CEM_B[i] + (s.endpointValueNum * 8 + 4) / 5
                5 -> s.endpointValueNum * CEM_B[i] + (s.endpointValueNum * 7 + 2) / 3
                else -> s.endpointValueNum * CEM_B[i]
            }
            if (endpointBits <= remainBits) {
                s.cemRange = i
                break
            }
        }
    }

    // ---------------------------------------------------------------- 端点

    private fun decodeEndpoints(lo: Long, hi: Long, s: BlockState) {
        val offset = if (s.partNum == 1) 17 else 29
        decodeIntSeq(
            lo, hi, offset,
            CEM_A[s.cemRange], CEM_B[s.cemRange],
            s.endpointValueNum, reverse = false,
            bits = s.seqBits, non = s.seqNon
        )

        val ev = s.ev
        when (CEM_A[s.cemRange]) {
            3 -> {
                val c = ENDPOINT_TRITS[CEM_B[s.cemRange]]
                val nb = CEM_B[s.cemRange]
                for (i in 0 until s.endpointValueNum) {
                    val a = (s.seqBits[i] and 1) * 0x1FF
                    val x = s.seqBits[i] ushr 1
                    val b = when (nb) {
                        1 -> 0
                        2 -> 0b100010110 * x
                        3 -> (x shl 7) or (x shl 2) or x
                        4 -> (x shl 6) or x
                        5 -> (x shl 5) or (x ushr 2)
                        else -> (x shl 4) or (x ushr 4)
                    }
                    ev[i] = (a and 0x80) or (((s.seqNon[i] * c + b) xor a) ushr 2)
                }
            }
            5 -> {
                val c = ENDPOINT_QUINTS[CEM_B[s.cemRange]]
                val nb = CEM_B[s.cemRange]
                for (i in 0 until s.endpointValueNum) {
                    val a = (s.seqBits[i] and 1) * 0x1FF
                    val x = s.seqBits[i] ushr 1
                    val b = when (nb) {
                        1 -> 0
                        2 -> 0b100001100 * x
                        3 -> (x shl 7) or (x shl 1) or (x ushr 1)
                        4 -> (x shl 6) or (x ushr 1)
                        else -> (x shl 5) or (x ushr 3)
                    }
                    ev[i] = (a and 0x80) or (((s.seqNon[i] * c + b) xor a) ushr 2)
                }
            }
            else -> {
                val bits = s.seqBits
                when (CEM_B[s.cemRange]) {
                    1 -> for (i in 0 until s.endpointValueNum) ev[i] = bits[i] * 0xFF
                    2 -> for (i in 0 until s.endpointValueNum) ev[i] = bits[i] * 0x55
                    3 -> for (i in 0 until s.endpointValueNum) {
                        ev[i] = (bits[i] shl 5) or (bits[i] shl 2) or (bits[i] ushr 1)
                    }
                    4 -> for (i in 0 until s.endpointValueNum) ev[i] = (bits[i] shl 4) or bits[i]
                    5 -> for (i in 0 until s.endpointValueNum) ev[i] = (bits[i] shl 3) or (bits[i] ushr 2)
                    6 -> for (i in 0 until s.endpointValueNum) ev[i] = (bits[i] shl 2) or (bits[i] ushr 4)
                    7 -> for (i in 0 until s.endpointValueNum) ev[i] = (bits[i] shl 1) or (bits[i] ushr 6)
                    8 -> for (i in 0 until s.endpointValueNum) ev[i] = bits[i]
                    else -> for (i in 0 until s.endpointValueNum) ev[i] = 0
                }
            }
        }

        // 逐分区把端点值解释成两个 RGBA 端点
        var v = 0
        for (partition in 0 until s.partNum) {
            val ep = s.endpoints[partition]
            when (s.cem[partition]) {
                0 -> setEndpoint(ep, ev[v], ev[v], ev[v], 255, ev[v + 1], ev[v + 1], ev[v + 1], 255)
                1 -> {
                    val l0 = (ev[v] ushr 2) or (ev[v + 1] and 0xC0)
                    val l1 = clamp8(l0 + (ev[v + 1] and 0x3F))
                    setEndpoint(ep, l0, l0, l0, 255, l1, l1, l1, 255)
                }
                2 -> {
                    val y0: Int
                    val y1: Int
                    if (ev[v] <= ev[v + 1]) {
                        y0 = ev[v] shl 4
                        y1 = ev[v + 1] shl 4
                    } else {
                        y0 = (ev[v + 1] shl 4) + 8
                        y1 = (ev[v] shl 4) - 8
                    }
                    setEndpointHdr(ep, y0, y0, y0, 0x780, y1, y1, y1, 0x780)
                }
                3 -> {
                    val y0: Int
                    val d: Int
                    if (ev[v] and 0x80 != 0) {
                        y0 = ((ev[v + 1] and 0xE0) shl 4) or ((ev[v] and 0x7F) shl 2)
                        d = (ev[v + 1] and 0x1F) shl 2
                    } else {
                        y0 = ((ev[v + 1] and 0xF0) shl 4) or ((ev[v] and 0x7F) shl 1)
                        d = (ev[v + 1] and 0x0F) shl 1
                    }
                    val y1 = clampHdr(y0 + d)
                    setEndpointHdr(ep, y0, y0, y0, 0x780, y1, y1, y1, 0x780)
                }
                4 -> setEndpoint(ep, ev[v], ev[v], ev[v], ev[v + 2], ev[v + 1], ev[v + 1], ev[v + 1], ev[v + 3])
                5 -> {
                    var a0 = ev[v]
                    var a1 = ev[v + 1]
                    var b0 = ev[v + 2]
                    var b1 = ev[v + 3]
                    val t1 = bitTransferSigned(a1, a0)
                    a0 = t1[1]
                    a1 = t1[0]
                    val t2 = bitTransferSigned(b1, b0)
                    b0 = t2[1]
                    b1 = t2[0]
                    a1 += a0
                    setEndpointClamp(ep, a0, a0, a0, b0, a1, a1, a1, b0 + b1)
                }
                6 -> setEndpoint(
                    ep,
                    (ev[v] * ev[v + 3]) shr 8, (ev[v + 1] * ev[v + 3]) shr 8, (ev[v + 2] * ev[v + 3]) shr 8, 255,
                    ev[v], ev[v + 1], ev[v + 2], 255
                )
                7 -> decodeEndpointsHdr7(ep, ev, v)
                8 -> {
                    if (ev[v] + ev[v + 2] + ev[v + 4] <= ev[v + 1] + ev[v + 3] + ev[v + 5]) {
                        setEndpoint(
                            ep,
                            ev[v], ev[v + 2], ev[v + 4], 255,
                            ev[v + 1], ev[v + 3], ev[v + 5], 255
                        )
                    } else {
                        setEndpointBlue(
                            ep,
                            ev[v + 1], ev[v + 3], ev[v + 5], 255,
                            ev[v], ev[v + 2], ev[v + 4], 255
                        )
                    }
                }
                9 -> {
                    var r0 = ev[v]
                    var r1 = ev[v + 1]
                    var g0 = ev[v + 2]
                    var g1 = ev[v + 3]
                    var b0 = ev[v + 4]
                    var b1 = ev[v + 5]
                    val t0 = bitTransferSigned(r1, r0)
                    r0 = t0[1]; r1 = t0[0]
                    val t1 = bitTransferSigned(g1, g0)
                    g0 = t1[1]; g1 = t1[0]
                    val t2 = bitTransferSigned(b1, b0)
                    b0 = t2[1]; b1 = t2[0]
                    if (r1 + g1 + b1 >= 0) {
                        setEndpointClamp(ep, r0, g0, b0, 255, r0 + r1, g0 + g1, b0 + b1, 255)
                    } else {
                        setEndpointBlueClamp(ep, r0 + r1, g0 + g1, b0 + b1, 255, r0, g0, b0, 255)
                    }
                }
                10 -> setEndpoint(
                    ep,
                    (ev[v] * ev[v + 3]) shr 8, (ev[v + 1] * ev[v + 3]) shr 8, (ev[v + 2] * ev[v + 3]) shr 8, ev[v + 4],
                    ev[v], ev[v + 1], ev[v + 2], ev[v + 5]
                )
                11 -> decodeEndpointsHdr11(ep, ev, v, 0x780, 0x780)
                12 -> {
                    if (ev[v] + ev[v + 2] + ev[v + 4] <= ev[v + 1] + ev[v + 3] + ev[v + 5]) {
                        setEndpoint(
                            ep,
                            ev[v], ev[v + 2], ev[v + 4], ev[v + 6],
                            ev[v + 1], ev[v + 3], ev[v + 5], ev[v + 7]
                        )
                    } else {
                        setEndpointBlue(
                            ep,
                            ev[v + 1], ev[v + 3], ev[v + 5], ev[v + 7],
                            ev[v], ev[v + 2], ev[v + 4], ev[v + 6]
                        )
                    }
                }
                13 -> {
                    var r0 = ev[v]
                    var r1 = ev[v + 1]
                    var g0 = ev[v + 2]
                    var g1 = ev[v + 3]
                    var b0 = ev[v + 4]
                    var b1 = ev[v + 5]
                    var a0 = ev[v + 6]
                    var a1 = ev[v + 7]
                    val t0 = bitTransferSigned(r1, r0)
                    r0 = t0[1]; r1 = t0[0]
                    val t1 = bitTransferSigned(g1, g0)
                    g0 = t1[1]; g1 = t1[0]
                    val t2 = bitTransferSigned(b1, b0)
                    b0 = t2[1]; b1 = t2[0]
                    val t3 = bitTransferSigned(a1, a0)
                    a0 = t3[1]; a1 = t3[0]
                    if (r1 + g1 + b1 >= 0) {
                        setEndpointClamp(
                            ep, r0, g0, b0, a0,
                            r0 + r1, g0 + g1, b0 + b1, a0 + a1
                        )
                    } else {
                        setEndpointBlueClamp(
                            ep, r0 + r1, g0 + g1, b0 + b1, a0 + a1,
                            r0, g0, b0, a0
                        )
                    }
                }
                14 -> decodeEndpointsHdr11(ep, ev, v, clampHdr(ev[v + 6]), clampHdr(ev[v + 7]))
                15 -> {
                    var alpha0 = ev[v + 6]
                    var alpha1 = ev[v + 7]
                    val mode = ((alpha0 ushr 7) and 1) or ((alpha1 ushr 6) and 2)
                    alpha0 = alpha0 and 0x7F
                    alpha1 = alpha1 and 0x7F
                    if (mode == 3) {
                        // 参考实现把掩码后的 v[6]/v[7] 留在块数据里，hdr11 会读到它们
                        ev[v + 6] = alpha0
                        ev[v + 7] = alpha1
                        decodeEndpointsHdr11(ep, ev, v, alpha0 shl 5, alpha1 shl 5)
                    } else {
                        alpha0 = alpha0 or ((alpha1 shl (mode + 1)) and 0x780)
                        alpha1 = ((alpha1 and (0x3F shr mode)) xor (0x20 shr mode)) - (0x20 shr mode)
                        alpha0 = alpha0 shl (4 - mode)
                        alpha1 = alpha1 shl (4 - mode)
                        ev[v + 6] = alpha0
                        ev[v + 7] = alpha1
                        decodeEndpointsHdr11(ep, ev, v, alpha0, clampHdr(alpha0 + alpha1))
                    }
                }
            }
            v += (s.cem[partition] / 4 + 1) * 2
        }
    }

    /** CEM 7：HDR 端点，分量分组编码 */
    private fun decodeEndpointsHdr7(ep: IntArray, v: IntArray, base: Int) {
        val modeVal = ((v[base + 2] ushr 4) and 0x8) or ((v[base + 1] ushr 5) and 0x4) or (v[base] ushr 6)
        val majorComponent: Int
        val mode: Int
        if ((modeVal and 0xC) != 0xC) {
            majorComponent = modeVal ushr 2
            mode = modeVal and 3
        } else if (modeVal != 0xF) {
            majorComponent = modeVal and 3
            mode = 4
        } else {
            majorComponent = 0
            mode = 5
        }
        val c = intArrayOf(
            v[base] and 0x3F,
            v[base + 1] and 0x1F,
            v[base + 2] and 0x1F,
            v[base + 3] and 0x1F
        )
        when (mode) {
            0 -> {
                c[3] = c[3] or (v[base + 3] and 0x60)
                c[0] = c[0] or ((v[base + 3] ushr 1) and 0x40)
                c[0] = c[0] or ((v[base + 2] shl 1) and 0x80)
                c[0] = c[0] or ((v[base + 1] shl 3) and 0x300)
                c[0] = c[0] or ((v[base + 2] shl 5) and 0x400)
                for (i in 0..3) c[i] = c[i] shl 1
            }
            1 -> {
                c[1] = c[1] or (v[base + 1] and 0x20)
                c[2] = c[2] or (v[base + 2] and 0x20)
                c[0] = c[0] or ((v[base + 3] ushr 1) and 0x40)
                c[0] = c[0] or ((v[base + 2] shl 1) and 0x80)
                c[0] = c[0] or ((v[base + 1] shl 2) and 0x100)
                c[0] = c[0] or ((v[base + 3] shl 4) and 0x600)
                for (i in 0..3) c[i] = c[i] shl 1
            }
            2 -> {
                c[3] = c[3] or (v[base + 3] and 0xE0)
                c[0] = c[0] or ((v[base + 2] shl 1) and 0xC0)
                c[0] = c[0] or ((v[base + 1] shl 3) and 0x300)
                for (i in 0..3) c[i] = c[i] shl 2
            }
            3 -> {
                c[1] = c[1] or (v[base + 1] and 0x20)
                c[2] = c[2] or (v[base + 2] and 0x20)
                c[3] = c[3] or (v[base + 3] and 0x60)
                c[0] = c[0] or ((v[base + 3] ushr 1) and 0x40)
                c[0] = c[0] or ((v[base + 2] shl 1) and 0x80)
                c[0] = c[0] or ((v[base + 1] shl 2) and 0x100)
                for (i in 0..3) c[i] = c[i] shl 3
            }
            4 -> {
                c[1] = c[1] or (v[base + 1] and 0x60)
                c[2] = c[2] or (v[base + 2] and 0x60)
                c[3] = c[3] or (v[base + 3] and 0x20)
                c[0] = c[0] or ((v[base + 3] ushr 1) and 0x40)
                c[0] = c[0] or ((v[base + 3] shl 1) and 0x80)
                for (i in 0..3) c[i] = c[i] shl 4
            }
            else -> {
                c[1] = c[1] or (v[base + 1] and 0x60)
                c[2] = c[2] or (v[base + 2] and 0x60)
                c[3] = c[3] or (v[base + 3] and 0x60)
                c[0] = c[0] or ((v[base + 3] ushr 1) and 0x40)
                for (i in 0..3) c[i] = c[i] shl 5
            }
        }
        if (mode != 5) {
            c[1] = c[0] - c[1]
            c[2] = c[0] - c[2]
        }
        when (majorComponent) {
            1 -> setEndpointHdrClamp(ep, c[1] - c[3], c[0] - c[3], c[2] - c[3], 0x780, c[1], c[0], c[2], 0x780)
            2 -> setEndpointHdrClamp(ep, c[2] - c[3], c[1] - c[3], c[0] - c[3], 0x780, c[2], c[1], c[0], 0x780)
            else -> setEndpointHdrClamp(ep, c[0] - c[3], c[1] - c[3], c[2] - c[3], 0x780, c[0], c[1], c[2], 0x780)
        }
    }

    /** CEM 11 / 14 / 15：HDR 端点，差值编码 */
    private fun decodeEndpointsHdr11(ep: IntArray, v: IntArray, base: Int, alpha1: Int, alpha2: Int) {
        val majorComponent = (v[base + 4] ushr 7) or ((v[base + 5] ushr 6) and 2)
        if (majorComponent == 3) {
            setEndpointHdr(
                ep,
                v[base] shl 4, v[base + 2] shl 4, (v[base + 4] shl 5) and 0xFE0, alpha1,
                v[base + 1] shl 4, v[base + 3] shl 4, (v[base + 5] shl 5) and 0xFE0, alpha2
            )
            return
        }
        val mode = (v[base + 1] ushr 7) or ((v[base + 2] ushr 6) and 2) or ((v[base + 3] ushr 5) and 4)
        var va = v[base] or ((v[base + 1] shl 2) and 0x100)
        var vb0 = v[base + 2] and 0x3F
        var vb1 = v[base + 3] and 0x3F
        var vc = v[base + 1] and 0x3F
        var vd0: Int
        var vd1: Int

        when (mode) {
            0, 2 -> {
                vd0 = v[base + 4] and 0x7F
                if (vd0 and 0x40 != 0) vd0 -= 0x80
                vd1 = v[base + 5] and 0x7F
                if (vd1 and 0x40 != 0) vd1 -= 0x80
            }
            1, 3, 5, 7 -> {
                vd0 = v[base + 4] and 0x3F
                if (vd0 and 0x20 != 0) vd0 -= 0x40
                vd1 = v[base + 5] and 0x3F
                if (vd1 and 0x20 != 0) vd1 -= 0x40
            }
            else -> {
                vd0 = v[base + 4] and 0x1F
                if (vd0 and 0x10 != 0) vd0 -= 0x20
                vd1 = v[base + 5] and 0x1F
                if (vd1 and 0x10 != 0) vd1 -= 0x20
            }
        }

        when (mode) {
            0 -> {
                vb0 = vb0 or (v[base + 2] and 0x40)
                vb1 = vb1 or (v[base + 3] and 0x40)
            }
            1 -> {
                vb0 = vb0 or (v[base + 2] and 0x40)
                vb1 = vb1 or (v[base + 3] and 0x40)
                vb0 = vb0 or ((v[base + 4] shl 1) and 0x80)
                vb1 = vb1 or ((v[base + 5] shl 1) and 0x80)
            }
            2 -> {
                va = va or ((v[base + 2] shl 3) and 0x200)
                vc = vc or (v[base + 3] and 0x40)
            }
            3 -> {
                va = va or ((v[base + 4] shl 3) and 0x200)
                vc = vc or (v[base + 5] and 0x40)
                vb0 = vb0 or (v[base + 2] and 0x40)
                vb1 = vb1 or (v[base + 3] and 0x40)
            }
            4 -> {
                va = va or ((v[base + 4] shl 4) and 0x200)
                va = va or ((v[base + 5] shl 5) and 0x400)
                vb0 = vb0 or (v[base + 2] and 0x40)
                vb1 = vb1 or (v[base + 3] and 0x40)
                vb0 = vb0 or ((v[base + 4] shl 1) and 0x80)
                vb1 = vb1 or ((v[base + 5] shl 1) and 0x80)
            }
            5 -> {
                va = va or ((v[base + 2] shl 3) and 0x200)
                va = va or ((v[base + 3] shl 4) and 0x400)
                vc = vc or (v[base + 5] and 0x40)
                vc = vc or ((v[base + 4] shl 1) and 0x80)
            }
            6 -> {
                va = va or ((v[base + 4] shl 4) and 0x200)
                va = va or ((v[base + 5] shl 5) and 0x400)
                va = va or ((v[base + 4] shl 5) and 0x800)
                vc = vc or (v[base + 5] and 0x40)
                vb0 = vb0 or (v[base + 2] and 0x40)
                vb1 = vb1 or (v[base + 3] and 0x40)
            }
            else -> {
                va = va or ((v[base + 2] shl 3) and 0x200)
                va = va or ((v[base + 3] shl 4) and 0x400)
                va = va or ((v[base + 4] shl 5) and 0x800)
                vc = vc or (v[base + 5] and 0x40)
            }
        }

        val shamt = (mode shr 1) xor 3
        va = va shl shamt
        vb0 = vb0 shl shamt
        vb1 = vb1 shl shamt
        vc = vc shl shamt
        val mult = 1 shl shamt
        vd0 *= mult
        vd1 *= mult

        when (majorComponent) {
            1 -> setEndpointHdrClamp(
                ep, va - vb0 - vc - vd0, va - vc, va - vb1 - vc - vd1, alpha1,
                va - vb0, va, va - vb1, alpha2
            )
            2 -> setEndpointHdrClamp(
                ep, va - vb1 - vc - vd1, va - vb0 - vc - vd0, va - vc, alpha1,
                va - vb1, va - vb0, va, alpha2
            )
            else -> setEndpointHdrClamp(
                ep, va - vc, va - vb0 - vc - vd0, va - vb1 - vc - vd1, alpha1,
                va, va - vb0, va - vb1, alpha2
            )
        }
    }

    // ---------------------------------------------------------------- 权重

    private fun decodeWeights(lo: Long, hi: Long, bw: Int, bh: Int, s: BlockState) {
        decodeIntSeq(
            lo, hi, 128,
            WEIGHT_PREC_A[s.weightRange], WEIGHT_PREC_B[s.weightRange],
            s.weightNum, reverse = true,
            bits = s.seqBits, non = s.seqNon
        )

        val wv = s.wv
        val n = s.weightNum
        if (n > wv.size) throw IllegalStateException("ASTC 权重数量异常：$n")
        if (WEIGHT_PREC_A[s.weightRange] == 0) {
            when (WEIGHT_PREC_B[s.weightRange]) {
                1 -> for (i in 0 until n) wv[i] = if (s.seqBits[i] != 0) 63 else 0
                2 -> for (i in 0 until n) {
                    wv[i] = (s.seqBits[i] shl 4) or (s.seqBits[i] shl 2) or s.seqBits[i]
                }
                3 -> for (i in 0 until n) wv[i] = (s.seqBits[i] shl 3) or s.seqBits[i]
                4 -> for (i in 0 until n) wv[i] = (s.seqBits[i] shl 2) or (s.seqBits[i] ushr 2)
                5 -> for (i in 0 until n) wv[i] = (s.seqBits[i] shl 1) or (s.seqBits[i] ushr 4)
                else -> for (i in 0 until n) wv[i] = 0
            }
            for (i in 0 until n) if (wv[i] > 32) wv[i]++
        } else if (WEIGHT_PREC_B[s.weightRange] == 0) {
            val step = if (WEIGHT_PREC_A[s.weightRange] == 3) 32 else 16
            for (i in 0 until n) wv[i] = s.seqNon[i] * step
        } else {
            val a = WEIGHT_PREC_A[s.weightRange]
            val b = WEIGHT_PREC_B[s.weightRange]
            if (a == 3) {
                when (b) {
                    1 -> for (i in 0 until n) wv[i] = s.seqNon[i] * 50
                    2 -> for (i in 0 until n) {
                        wv[i] = s.seqNon[i] * 23
                        if (s.seqBits[i] and 2 != 0) wv[i] += 0b1000101
                    }
                    3 -> for (i in 0 until n) {
                        wv[i] = s.seqNon[i] * 11 + (((s.seqBits[i] shl 4) or (s.seqBits[i] ushr 1)) and 0b1100011)
                    }
                }
            } else if (a == 5) {
                when (b) {
                    1 -> for (i in 0 until n) wv[i] = s.seqNon[i] * 28
                    2 -> for (i in 0 until n) {
                        wv[i] = s.seqNon[i] * 13
                        if (s.seqBits[i] and 2 != 0) wv[i] += 0b1000010
                    }
                }
            }
            for (i in 0 until n) {
                val a2 = (s.seqBits[i] and 1) * 0x7F
                wv[i] = (a2 and 0x20) or ((wv[i] xor a2) ushr 2)
                if (wv[i] > 32) wv[i]++
            }
        }

        // 双线性插值到块内每个像素（并处理 dual-plane 的两套权重）
        val ds = (1024 + bw / 2) / (bw - 1)
        val dt = (1024 + bh / 2) / (bh - 1)
        val planes = if (s.dualPlane) 2 else 1
        var i = 0
        for (t in 0 until bh) {
            for (sx in 0 until bw) {
                val gs = (ds * sx * (s.width - 1) + 32) shr 6
                val gt = (dt * t * (s.height - 1) + 32) shr 6
                val fs = gs and 0xF
                val ft = gt and 0xF
                val v = (gs shr 4) + (gt shr 4) * s.width
                val w11 = (fs * ft + 8) shr 4
                val w10 = ft - w11
                val w01 = fs - w11
                val w00 = 16 - fs - ft + w11
                for (plane in 0 until planes) {
                    s.weights[i][plane] = interpolateWeight(wv, n, planes, plane, v, s.width, w00, w01, w10, w11)
                }
                i++
            }
        }
    }

    /**
     * 权重双线性插值：取权重网格上 4 个邻点按 (w00, w01, w10, w11) 加权。
     *
     * 网格右/下边界的 +1 邻点会越界。这里按 0 处理（参考实现读的是零初始化的尾部，
     * 与 ASTC 规格要求的「索引夹紧」等价：能走到边界时 `fs`/`ft` 必为 0，
     * 越界邻点的系数本来就是 0，取值不影响结果——夹具已用夹紧版 oracle 验证过）。
     */
    private fun interpolateWeight(
        wv: IntArray,
        n: Int,
        planes: Int,
        plane: Int,
        grid: Int,
        width: Int,
        w00: Int,
        w01: Int,
        w10: Int,
        w11: Int
    ): Int {
        val i00 = grid * planes + plane
        val i01 = i00 + planes
        val i10 = (grid + width) * planes + plane
        val i11 = i10 + planes
        val p00 = if (i00 in 0 until n) wv[i00] else 0
        val p01 = if (i01 in 0 until n) wv[i01] else 0
        val p10 = if (i10 in 0 until n) wv[i10] else 0
        val p11 = if (i11 in 0 until n) wv[i11] else 0
        return (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) shr 4
    }

    // ---------------------------------------------------------------- 分区

    private fun selectPartition(lo: Long, hi: Long, bw: Int, bh: Int, s: BlockState) {
        val smallBlock = bw * bh < 31
        val seed = (((lo ushr 13).toInt()) and 0x3FF) or ((s.partNum - 1) shl 10)

        var rnum = seed
        rnum = rnum xor (rnum ushr 15)
        rnum -= rnum shl 17
        rnum += rnum shl 7
        rnum += rnum shl 4
        rnum = rnum xor (rnum ushr 5)
        rnum += rnum shl 16
        rnum = rnum xor (rnum ushr 7)
        rnum = rnum xor (rnum ushr 3)
        rnum = rnum xor (rnum shl 6)
        rnum = rnum xor (rnum ushr 17)

        val seeds = IntArray(8)
        for (i in 0 until 8) {
            val v = (rnum ushr (i * 4)) and 0xF
            seeds[i] = v * v
        }

        val sh0 = if (seed and 2 != 0) 4 else 5
        val sh1 = if (s.partNum == 3) 6 else 5
        if (seed and 1 != 0) {
            for (i in 0 until 8) seeds[i] = seeds[i] ushr (if (i % 2 == 0) sh0 else sh1)
        } else {
            for (i in 0 until 8) seeds[i] = seeds[i] ushr (if (i % 2 == 0) sh1 else sh0)
        }

        val r14 = rnum ushr 14
        val r10 = rnum ushr 10
        val r6 = rnum ushr 6
        val r2 = rnum ushr 2
        var i = 0
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val px = if (smallBlock) x shl 1 else x
                val py = if (smallBlock) y shl 1 else y
                val a = (seeds[0] * px + seeds[1] * py + r14) and 0x3F
                val b = (seeds[2] * px + seeds[3] * py + r10) and 0x3F
                val c = if (s.partNum < 3) 0 else (seeds[4] * px + seeds[5] * py + r6) and 0x3F
                val d = if (s.partNum < 4) 0 else (seeds[6] * px + seeds[7] * py + r2) and 0x3F
                s.partition[i] = when {
                    a >= b && a >= c && a >= d -> 0
                    b >= c && b >= d -> 1
                    c >= d -> 2
                    else -> 3
                }
                i++
            }
        }
    }

    // ---------------------------------------------------------------- 着色

    private fun applyColor(bw: Int, bh: Int, out: IntArray, s: BlockState) {
        val pixels = bw * bh
        if (s.dualPlane) {
            val ps = IntArray(4)
            ps[s.planeSelector and 3] = 1
            if (s.partNum > 1) {
                for (i in 0 until pixels) {
                    val p = s.partition[i]
                    val ep = s.endpoints[p]
                    val w = s.weights[i]
                    out[i] = color(
                        interpolate(s.cem[p], CEM_HDR_RGB, ep[0], ep[4], w[ps[0]]),
                        interpolate(s.cem[p], CEM_HDR_RGB, ep[1], ep[5], w[ps[1]]),
                        interpolate(s.cem[p], CEM_HDR_RGB, ep[2], ep[6], w[ps[2]]),
                        interpolate(s.cem[p], CEM_HDR_ALPHA, ep[3], ep[7], w[ps[3]])
                    )
                }
            } else {
                val ep = s.endpoints[0]
                for (i in 0 until pixels) {
                    val w = s.weights[i]
                    out[i] = color(
                        interpolate(s.cem[0], CEM_HDR_RGB, ep[0], ep[4], w[ps[0]]),
                        interpolate(s.cem[0], CEM_HDR_RGB, ep[1], ep[5], w[ps[1]]),
                        interpolate(s.cem[0], CEM_HDR_RGB, ep[2], ep[6], w[ps[2]]),
                        interpolate(s.cem[0], CEM_HDR_ALPHA, ep[3], ep[7], w[ps[3]])
                    )
                }
            }
        } else if (s.partNum > 1) {
            for (i in 0 until pixels) {
                val p = s.partition[i]
                val ep = s.endpoints[p]
                val w = s.weights[i][0]
                out[i] = color(
                    interpolate(s.cem[p], CEM_HDR_RGB, ep[0], ep[4], w),
                    interpolate(s.cem[p], CEM_HDR_RGB, ep[1], ep[5], w),
                    interpolate(s.cem[p], CEM_HDR_RGB, ep[2], ep[6], w),
                    interpolate(s.cem[p], CEM_HDR_ALPHA, ep[3], ep[7], w)
                )
            }
        } else {
            val ep = s.endpoints[0]
            for (i in 0 until pixels) {
                val w = s.weights[i][0]
                out[i] = color(
                    interpolate(s.cem[0], CEM_HDR_RGB, ep[0], ep[4], w),
                    interpolate(s.cem[0], CEM_HDR_RGB, ep[1], ep[5], w),
                    interpolate(s.cem[0], CEM_HDR_RGB, ep[2], ep[6], w),
                    interpolate(s.cem[0], CEM_HDR_ALPHA, ep[3], ep[7], w)
                )
            }
        }
    }

    private fun interpolate(cem: Int, hdrTable: BooleanArray, v0: Int, v1: Int, weight: Int): Int =
        if (cem in hdrTable.indices && hdrTable[cem]) selectColorHdr(v0, v1, weight)
        else selectColor(v0, v1, weight)

    /** LDR 端点插值（ASTC 规范 8 bit 量化公式） */
    private fun selectColor(v0: Int, v1: Int, weight: Int): Int {
        val w = weight
        return (((((v0 shl 8) or v0) * (64 - w)) + (((v1 shl 8) or v1) * w) + 32) shr 6) * 255 + 32768 shr 16
    }

    /** HDR 端点插值：先按 half 浮点还原，再量化到 8 bit */
    private fun selectColorHdr(v0: Int, v1: Int, weight: Int): Int {
        val w = weight
        val c = (((v0 shl 4) * (64 - w)) + ((v1 shl 4) * w) + 32) shr 6
        var m = c and 0x7FF
        m = when {
            m < 512 -> m * 3
            m < 1536 -> 4 * m - 512
            else -> 5 * m - 2048
        }
        val half = ((c shr 1) and 0x7C00) or (m shr 3)
        val f = halfToFloat(half)
        if (f.isNaN() || f.isInfinite()) return 255
        val scaled = Math.round(f * 255f)
        return when {
            scaled < 0 -> 0
            scaled > 255 -> 255
            else -> scaled.toInt()
        }
    }

    // ---------------------------------------------------------------- 位操作

    /** 小端 64 bit 读；越界字节按 0（参考实现是读越界内存，这里收敛到确定值） */
    private fun readLongLe(block: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            val idx = off + i
            if (idx < block.size) v = v or ((block[idx].toLong() and 0xFF) shl (i * 8))
        }
        return v
    }

    private fun u16Le(block: ByteArray, off: Int): Int {
        val lo = if (off < block.size) block[off].toInt() and 0xFF else 0
        val hi = if (off + 1 < block.size) block[off + 1].toInt() and 0xFF else 0
        return lo or (hi shl 8)
    }

    /** `getbits(buf, bit, len)`：块内任意位域（小端），越界按 0 */
    private fun getBits32(block: ByteArray, off: Int, bit: Int, len: Int): Int {
        if (len < 1 || bit < 0) return 0
        val mask = if (len >= 32) -1 else (1 shl len) - 1
        val lo = readLongLe(block, off)
        val hi = readLongLe(block, off + 8)
        return getBits64(lo, hi, bit, len).toInt() and mask
    }

    /** `getbits64(buf, bit, len)`：64 bit 位域（可能跨两个 64 bit 字，也可能 bit 为负） */
    private fun getBits64(lo: Long, hi: Long, bit: Int, len: Int): Long {
        if (len < 1) return 0
        val mask = if (len >= 64) -1L else (1L shl len) - 1
        val word: Long = when {
            bit >= 64 -> hi ushr (bit - 64)
            bit <= 0 -> lo shl (-bit)
            bit + len <= 64 -> lo ushr bit
            else -> (lo ushr bit) or (hi shl (64 - bit))
        }
        return word and mask
    }

    /** 8 bit 位反转后右移，保留 `bits` 位 */
    private fun bitReverse8(value: Int, bits: Int): Int =
        (AstcTables.BIT_REVERSE[value and 0xFF] ushr (8 - bits)) and ((1 shl bits) - 1)

    /** 64 bit 位反转后右移，保留 `bits` 位 */
    private fun bitReverse64(d: Long, bits: Int): Long {
        val t = AstcTables.BIT_REVERSE
        var ret = 0L
        ret = ret or (t[(d and 0xFF).toInt()].toLong() shl 56)
        ret = ret or (t[((d ushr 8) and 0xFF).toInt()].toLong() shl 48)
        ret = ret or (t[((d ushr 16) and 0xFF).toInt()].toLong() shl 40)
        ret = ret or (t[((d ushr 24) and 0xFF).toInt()].toLong() shl 32)
        ret = ret or (t[((d ushr 32) and 0xFF).toInt()].toLong() shl 24)
        ret = ret or (t[((d ushr 40) and 0xFF).toInt()].toLong() shl 16)
        ret = ret or (t[((d ushr 48) and 0xFF).toInt()].toLong() shl 8)
        ret = ret or t[((d ushr 56) and 0xFF).toInt()].toLong()
        return if (bits >= 64) ret else ret ushr (64 - bits)
    }

    /**
     * 反量化整型序列（ASTC 难点所在）。
     *
     * trits / quints 打包在同一段比特流里，取值需要「位 + 非位」两部分拼回。
     * 参考实现用指针游走 + 负下标实现反向读取，这里用 64 bit 字读 + 负 bit 处理等价表达。
     */
    private fun decodeIntSeq(
        lo: Long,
        hi: Long,
        offset: Int,
        a: Int,
        b: Int,
        count: Int,
        reverse: Boolean,
        bits: IntArray,
        non: IntArray
    ) {
        if (count <= 0) return
        var n = 0
        when (a) {
            3 -> {
                val mask = (1L shl b) - 1
                val blockCount = (count + 4) / 5
                val lastBlockCount = (count + 4) % 5 + 1
                val blockSize = 8 + 5 * b
                val lastBlockSize = (blockSize * lastBlockCount + 4) / 5
                var p = offset
                for (i in 0 until blockCount) {
                    if (n >= count) break
                    val size = if (i < blockCount - 1) blockSize else lastBlockSize
                    var d: Long
                    if (reverse) {
                        d = bitReverse64(getBits64(lo, hi, p - size, size), size)
                        p -= blockSize
                    } else {
                        d = getBits64(lo, hi, p, size)
                        p += blockSize
                    }
                    val x = ((d ushr b) and 3) or
                        ((d ushr (b * 2)) and 0xC) or
                        ((d ushr (b * 3)) and 0x10) or
                        ((d ushr (b * 4)) and 0x60) or
                        ((d ushr (b * 5)) and 0x80)
                    val xi = x.toInt() and 0xFF
                    var j = 0
                    while (j < 5 && n < count) {
                        bits[n] = ((d ushr (MT[j] + b * j)) and mask).toInt()
                        non[n] = AstcTables.TRITS[j][xi]
                        n++
                        j++
                    }
                }
            }
            5 -> {
                val mask = (1L shl b) - 1
                val blockCount = (count + 2) / 3
                val lastBlockCount = (count + 2) % 3 + 1
                val blockSize = 7 + 3 * b
                val lastBlockSize = (blockSize * lastBlockCount + 2) / 3
                var p = offset
                for (i in 0 until blockCount) {
                    if (n >= count) break
                    val size = if (i < blockCount - 1) blockSize else lastBlockSize
                    var d: Long
                    if (reverse) {
                        d = bitReverse64(getBits64(lo, hi, p - size, size), size)
                        p -= blockSize
                    } else {
                        d = getBits64(lo, hi, p, size)
                        p += blockSize
                    }
                    val x = ((d ushr b) and 7) or
                        ((d ushr (b * 2)) and 0x18) or
                        ((d ushr (b * 3)) and 0x60)
                    val xi = x.toInt() and 0xFF
                    var j = 0
                    while (j < 3 && n < count) {
                        bits[n] = ((d ushr (MQ[j] + b * j)) and mask).toInt()
                        non[n] = AstcTables.QUINTS[j][xi]
                        n++
                        j++
                    }
                }
            }
            else -> {
                var p = if (reverse) offset - b else offset
                while (n < count) {
                    val raw = if (reverse) bitReverse8(getBits64(lo, hi, p, b).toInt(), b)
                    else getBits64(lo, hi, p, b).toInt()
                    bits[n] = raw and ((1 shl b) - 1)
                    non[n] = 0
                    n++
                    p += if (reverse) -b else b
                }
            }
        }
    }

    // ---------------------------------------------------------------- 端点写入

    private fun setEndpoint(ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int) {
        ep[0] = r1; ep[1] = g1; ep[2] = b1; ep[3] = a1
        ep[4] = r2; ep[5] = g2; ep[6] = b2; ep[7] = a2
    }

    private fun setEndpointClamp(
        ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int
    ) = setEndpoint(ep, clamp8(r1), clamp8(g1), clamp8(b1), clamp8(a1), clamp8(r2), clamp8(g2), clamp8(b2), clamp8(a2))

    private fun setEndpointBlue(
        ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int
    ) = setEndpoint(ep, (r1 + b1) shr 1, (g1 + b1) shr 1, b1, a1, (r2 + b2) shr 1, (g2 + b2) shr 1, b2, a2)

    private fun setEndpointBlueClamp(
        ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int
    ) = setEndpoint(
        ep,
        clamp8((r1 + b1) shr 1), clamp8((g1 + b1) shr 1), clamp8(b1), clamp8(a1),
        clamp8((r2 + b2) shr 1), clamp8((g2 + b2) shr 1), clamp8(b2), clamp8(a2)
    )

    private fun setEndpointHdr(ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int) =
        setEndpoint(ep, r1, g1, b1, a1, r2, g2, b2, a2)

    private fun setEndpointHdrClamp(
        ep: IntArray, r1: Int, g1: Int, b1: Int, a1: Int, r2: Int, g2: Int, b2: Int, a2: Int
    ) = setEndpoint(
        ep,
        clampHdr(r1), clampHdr(g1), clampHdr(b1), clampHdr(a1),
        clampHdr(r2), clampHdr(g2), clampHdr(b2), clampHdr(a2)
    )

    /** `bit_transfer_signed`：把 (符号, 值) 形式还原成两个有符号增量；返回 [a, b] */
    private fun bitTransferSigned(a: Int, b: Int): IntArray {
        var nb = (b ushr 1) or (a and 0x80)
        var na = (a ushr 1) and 0x3F
        if (na and 0x20 != 0) na -= 0x40
        return intArrayOf(na, nb)
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun clampHdr(v: Int): Int = if (v < 0) 0 else if (v > 0xFFF) 0xFFF else v

    /** ARGB 打包（与 Bitmap.Config.ARGB_8888 的内存布局一致） */
    private fun color(r: Int, g: Int, b: Int, a: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** 块内 16 bit half → 0..255（void-extent 的 HDR 变体用） */
    private fun halfToByte(block: ByteArray, off: Int): Int {
        val f = halfToFloat(u16Le(block, off))
        if (f.isNaN() || f.isInfinite()) return 255
        val scaled = Math.round(f * 255f)
        return when {
            scaled < 0 -> 0
            scaled > 255 -> 255
            else -> scaled.toInt()
        }
    }

    /** IEEE 754 half → float（与 fp16 库逐位一致，含次正规数/Inf/NaN） */
    private fun halfToFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 -> if (mant == 0) {
                sign
            } else {
                // 次正规数：规格化
                var m = mant
                var e = -1
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e++
                }
                m = m and 0x3FF
                sign or ((127 - 15 - e) shl 23) or (m shl 13)
            }
            exp == 0x1F -> sign or 0x7F800000.toInt() or (mant shl 13)
            else -> sign or ((exp + 112) shl 23) or (mant shl 13)
        }
        return Float.fromBits(bits)
    }
}
