package com.azurlane.blyy.util.unity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * ASTC 软件解码器的黄金样本对拍。
 *
 * 样本由参考实现（Texture2DDecoder `astc.cpp`，与 UnityPy / AssetStudio 同源）
 * 在 JVM 之外生成，覆盖：
 * - 全部 2D 块尺寸：4x4 / 5x5 / 6x6 / 8x5 / 8x8 / 10x6 / 10x10 / 12x12；
 * - 单分区 / 多分区、单平面 / 双平面（dual plane）、LDR / HDR CEM（含 7/11/14/15）；
 * - 边缘块（全 0、void-extent、保留块）。
 *
 * 每条样本给的是「解码后像素的 sha256」。像素按参考实现的输出布局
 * （小端 ARGB → 内存里的 B,G,R,A 字节序）哈希，因此这里也要按同一顺序拼字节。
 *
 * 为什么值得留这套夹具：ASTC 是位域密集的格式，抄错一个 `>>` 就会「解出来能看，
 * 但颜色偏 / 边缘错位」，靠肉眼完全看不出来；只有逐块哈希对拍能兜住。
 */
class AstcDecoderFixtureTest {

    private data class BlockCase(
        val bw: Int,
        val bh: Int,
        val block: ByteArray,
        val sha: String,
        /** true = 规范外的越界块，参考实现自身读越界内存，不做哈希对拍 */
        val unsafe: Boolean = false
    )

    private data class ImageCase(
        val width: Int,
        val height: Int,
        val bw: Int,
        val bh: Int,
        val data: ByteArray,
        val sha: String
    )

    private fun readResource(name: String): List<String> {
        val stream = javaClass.getResourceAsStream("/astc/$name")
            ?: error("缺少测试资源 /astc/$name")
        return stream.bufferedReader().readLines()
    }

    private fun blockCases(): List<BlockCase> = readResource("astc_blocks.txt")
        .asSequence()
        .filter { it.startsWith("block ") || it.startsWith("unsafe ") }
        .map { line ->
            val parts = line.split(' ')
            BlockCase(
                bw = parts[1].toInt(),
                bh = parts[2].toInt(),
                block = parts[3].hexToBytes(),
                sha = parts[4],
                unsafe = parts[0] == "unsafe"
            )
        }
        .toList()

    private fun imageCases(): List<ImageCase> = readResource("astc_images.txt")
        .asSequence()
        .filter { it.startsWith("image ") }
        .map { line ->
            val parts = line.split(' ')
            ImageCase(
                width = parts[1].toInt(),
                height = parts[2].toInt(),
                bw = parts[3].toInt(),
                bh = parts[4].toInt(),
                data = parts[5].hexToBytes(),
                sha = parts[6]
            )
        }
        .toList()

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    /** ARGB(Int) → 参考实现的内存字节序 B,G,R,A */
    private fun argbToLeBytes(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 4)
        var i = 0
        for (p in pixels) {
            out[i] = (p and 0xFF).toByte()
            out[i + 1] = ((p ushr 8) and 0xFF).toByte()
            out[i + 2] = ((p ushr 16) and 0xFF).toByte()
            out[i + 3] = ((p ushr 24) and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `1952 条块级黄金样本全部逐字节一致`() {
        val cases = blockCases()
        assertTrue("样本数量异常：${cases.size}", cases.size >= 1900)

        var failures = 0
        val details = StringBuilder()
        cases.forEachIndexed { index, case ->
            val pixels = AstcDecoder.decodeBlock(case.block, 0, case.bw, case.bh)
            assertEquals("块尺寸像素数不符（#$index）", case.bw * case.bh, pixels.size)
            if (case.unsafe) {
                // 规范外块（权重位数超过 128）：参考实现读越界内存，只要求确定且不抛异常
                assertTrue(
                    "越界块解码结果应可复现（#$index）",
                    pixels.contentEquals(AstcDecoder.decodeBlock(case.block, 0, case.bw, case.bh))
                )
                return@forEachIndexed
            }
            val actual = sha256(argbToLeBytes(pixels))
            if (actual != case.sha) {
                failures++
                if (details.length < 2_000) {
                    details.append("\n#$index ${case.bw}x${case.bh} ${case.block.toHex()} ")
                        .append("期望=${case.sha.take(16)} 实际=${actual.take(16)}")
                }
            }
        }
        assertEquals("解码结果与参考实现不一致：$failures/${cases.size} 块$details", 0, failures)
    }

    @Test
    fun `整图样本的分块拼接与边缘裁剪正确`() {
        val cases = imageCases()
        assertTrue("整图样本缺失", cases.size >= 6)
        var failures = 0
        val details = StringBuilder()
        cases.forEachIndexed { index, case ->
            val pixels = AstcDecoder.decode(case.data, 0, case.width, case.height, case.bw, case.bh)
            assertEquals("像素数量不符（#$index）", case.width * case.height, pixels.size)
            val actual = sha256(argbToLeBytes(pixels))
            if (actual != case.sha) {
                failures++
                details.append("\n#$index ${case.width}x${case.height} 块${case.bw}x${case.bh} ")
                    .append("期望=${case.sha.take(16)} 实际=${actual.take(16)}")
            }
        }
        assertEquals("整图解码与参考实现不一致：$failures/${cases.size}$details", 0, failures)
    }

    @Test
    fun `边解边降采样与全尺寸最近邻缩放的像素一致`() {
        // 取一张整图样本，按 1/2、1/3 解码，与「全尺寸解码后取最近邻」逐像素比对。
        // 这条路径用于超大贴图（弱 GPU + 4096 立绘）省内存，错了会整张图错位。
        val case = imageCases().first()
        val full = AstcDecoder.decode(case.data, 0, case.width, case.height, case.bw, case.bh)
        for (divisor in listOf(2, 3, 5)) {
            val outW = (case.width / divisor).coerceAtLeast(1)
            val outH = (case.height / divisor).coerceAtLeast(1)
            val scaled = AstcDecoder.decode(
                data = case.data,
                offset = 0,
                width = case.width,
                height = case.height,
                bw = case.bw,
                bh = case.bh,
                outWidth = outW,
                outHeight = outH
            )
            assertEquals("缩放后像素数量不符（1/$divisor）", outW * outH, scaled.size)
            for (y in 0 until outH) {
                val sy = y * case.height / outH
                for (x in 0 until outW) {
                    val sx = x * case.width / outW
                    assertEquals(
                        "1/$divisor 缩放第 ($x,$y) 个像素与最近邻不一致",
                        full[sy * case.width + sx],
                        scaled[y * outW + x]
                    )
                }
            }
        }
    }

    @Test
    fun `flipVertical 与「解完自己倒序」逐像素一致`() {
        // Unity 的压缩纹理自下而上存放：Android 位图要自上而下，所以真实解码必须翻转。
        // 这条断言保证翻转只动行序、不动列，也不会在降采样路径上错位。
        val case = imageCases().first()
        val raw = AstcDecoder.decode(case.data, 0, case.width, case.height, case.bw, case.bh)
        val flipped = AstcDecoder.decode(
            data = case.data, offset = 0,
            width = case.width, height = case.height,
            bw = case.bw, bh = case.bh,
            flipVertical = true
        )
        for (y in 0 until case.height) {
            val src = (case.height - 1 - y) * case.width
            val dst = y * case.width
            for (x in 0 until case.width) {
                assertEquals("翻转后第 ($x,$y) 个像素不符", raw[src + x], flipped[dst + x])
            }
        }
    }

    @Test
    fun `flipVertical 在降采样路径上同样只翻行`() {
        val case = imageCases().first()
        val outW = (case.width / 2).coerceAtLeast(1)
        val outH = (case.height / 2).coerceAtLeast(1)
        val normal = AstcDecoder.decode(
            data = case.data, offset = 0,
            width = case.width, height = case.height, bw = case.bw, bh = case.bh,
            outWidth = outW, outHeight = outH
        )
        val flipped = AstcDecoder.decode(
            data = case.data, offset = 0,
            width = case.width, height = case.height, bw = case.bw, bh = case.bh,
            outWidth = outW, outHeight = outH, flipVertical = true
        )
        for (y in 0 until outH) {
            val src = (outH - 1 - y) * outW
            val dst = y * outW
            for (x in 0 until outW) {
                assertEquals("缩放+翻转第 ($x,$y) 个像素不符", normal[src + x], flipped[dst + x])
            }
        }
    }

    @Test
    fun `void-extent 纯色块解成单一颜色`() {
        // 0xFC 开头且第 2 字节 bit0=1 即 void-extent；8 bit 分量在 9/11/13/15 字节
        val block = ByteArray(16)
        block[0] = 0xFC.toByte()
        block[1] = 0x01
        block[9] = 10
        block[11] = 20
        block[13] = 30
        block[15] = 40
        val pixels = AstcDecoder.decodeBlock(block, 0, 6, 6)
        val expected = (40 shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertTrue("纯色块应全部同色", pixels.all { it == expected })
    }

    @Test
    fun `保留块解成品红而不是抛异常`() {
        val pixels = AstcDecoder.decodeBlock(ByteArray(16), 0, 4, 4)
        val magenta = (255 shl 24) or (255 shl 16) or (0 shl 8) or 255
        assertTrue("保留块应为品红", pixels.all { it == magenta })
    }

    @Test
    fun `数据不足时给出可读错误`() {
        val thrown = runCatching {
            AstcDecoder.decode(ByteArray(15), 0, 4, 4, 4, 4)
        }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $thrown", thrown is IllegalArgumentException)
    }


    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
