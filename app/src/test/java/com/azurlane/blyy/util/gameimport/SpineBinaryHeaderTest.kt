package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spine 二进制文件头解析回归。
 *
 * 只解析「hash + version」两个 varint 字符串——导入时用来确认包内确实是
 * Spine 3.8 二进制（版本不匹配会在渲染阶段黑屏，提前拦住比事后排查便宜）。
 */
class SpineBinaryHeaderTest {

    private fun binaryHeader(hash: String, version: String): ByteArray {
        // Spine 二进制字符串长度 = 实际长度 + 1（0 = null，1 = 空串）
        fun varint(value: Int): ByteArray {
            val out = ArrayList<Byte>()
            var v = value
            while (true) {
                if (v < 0x80) {
                    out += v.toByte()
                    break
                }
                out += ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
            return out.toByteArray()
        }
        val hashBytes = hash.toByteArray(Charsets.UTF_8)
        val versionBytes = version.toByteArray(Charsets.UTF_8)
        return varint(hashBytes.size + 1) + hashBytes + varint(versionBytes.size + 1) + versionBytes
    }

    @Test
    fun `解析 3_8_99 版本`() {
        // 真实包头：28 字节 hash（长度前缀 0x1c）+ 版本（长度前缀 0x07）
        val hash = "RJAq8SNh08y/f0htDHnno+w703s".padEnd(27, 'x').take(27)
        val bytes = binaryHeader(hash, "3.8.99")
        assertEquals("3.8.99", SpineBinaryHeader.readVersion(bytes))
    }

    @Test
    fun `版本被运行时支持`() {
        assertTrue(SpineBinaryHeader.isSupportedByRuntime("3.8.99"))
        assertTrue(SpineBinaryHeader.isSupportedByRuntime(null))
        assertFalse(SpineBinaryHeader.isSupportedByRuntime("4.1.24"))
    }

    @Test
    fun `损坏数据返回 null`() {
        assertNull(SpineBinaryHeader.readVersion(ByteArray(0)))
        assertNull(SpineBinaryHeader.readVersion(byteArrayOf(0x7F)))
        // 长度声明超出实际长度
        assertNull(SpineBinaryHeader.readVersion(byteArrayOf(0x40, 0x41, 0x42)))
    }
}
