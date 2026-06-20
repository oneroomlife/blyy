package com.azurlane.blyy.data.repository

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 排行榜 Token 安全解密工具
 *
 * 安全机制：
 * 1. 密码不以明文字符串常量存储，采用 XOR 混淆字节数组，运行时还原
 * 2. 密钥通过 PBKDF2WithHmacSHA256（100,000 次迭代 + 随机盐值）派生，属于带盐哈希算法
 * 3. 密文格式：salt(16) + iv(16) + ciphertext，Base64 编码存储
 * 4. 解密后通过 SHA-256 完整性哈希校验，确保解密结果未被篡改
 * 5. 解密后的明文仅存在于内存中，不会写入持久化存储或日志
 * 6. 中间密码字符数组在使用后立即清零（[PBEKeySpec.clearPassword]）
 */
internal object LeaderboardCrypto {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16

    // XOR 混淆掩码（7 字节，与密码等长）
    private val PASSWORD_MASK = byteArrayOf(0x6B, 0x4E, 0x29, 0x7C, 0x5A, 0x1D, 0x3F)

    // XOR 混淆后的密码字节（password[i] XOR mask[i]）
    private val PASSWORD_OBFUSCATED = byteArrayOf(0x5A, 0x7C, 0x1A, 0x48, 0x6F, 0x2B, 0x7E)

    // 预加密的 GitHub Token 密文（salt + iv + ciphertext，Base64 编码）
    private const val ENCRYPTED_TOKEN =
        "GltcAUMqusV9TvEoojMbteXjSO5WZVIUDCE5p89LPV/EFKXSSFnzzlM0byi6vaKSdBUnvwMJuGddlng0q5Y6H4iO7wI0VgNg1K5fZMbx9FgqexCLTBo2NxDAe/oo9hmeaPAQcc4xLxRwL5xoKoguPQE1JM7KiUmhFI/dM37+4GQ="

    // Token 明文的 SHA-256 完整性校验哈希（Base64），用于验证解密正确性
    private const val TOKEN_INTEGRITY_HASH = "k8Q0CsukkpK6XMssocmytdU3HN2MZqbLiQu59yJJOLw="

    /**
     * 从混淆字节数组还原密码，返回后调用方负责清零
     */
    private fun retrievePassword(): ByteArray {
        val result = ByteArray(PASSWORD_OBFUSCATED.size)
        for (i in PASSWORD_OBFUSCATED.indices) {
            result[i] = (PASSWORD_OBFUSCATED[i].toInt() xor PASSWORD_MASK[i].toInt()).toByte()
        }
        return result
    }

    /**
     * 解密并返回 GitHub Token。
     *
     * 流程：XOR 还原密码 → PBKDF2 派生密钥 → AES-CBC 解密 → SHA-256 完整性校验
     * 仅在需要调用 GitHub API 时调用，返回的 String 仅存在于调用方内存中。
     *
     * @throws SecurityException 如果解密结果完整性校验失败
     */
    fun decryptToken(): String {
        val combined = Base64.decode(ENCRYPTED_TOKEN, Base64.NO_WRAP)
        require(combined.size > SALT_LENGTH + IV_LENGTH) { "密文数据格式无效" }

        val salt = combined.copyOfRange(0, SALT_LENGTH)
        val iv = combined.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = combined.copyOfRange(SALT_LENGTH + IV_LENGTH, combined.size)

        // 从混淆数组还原密码字节
        val passwordBytes = retrievePassword()
        val keySpec = PBEKeySpec(
            passwordBytes.map { it.toInt().toChar() }.toCharArray(),
            salt, ITERATIONS, KEY_LENGTH_BITS
        )
        // 立即清零密码字节
        passwordBytes.fill(0)

        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec)
        val keyBytes = secretKey.encoded
        keySpec.clearPassword()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ciphertext)

        // 清零密钥字节
        keyBytes.fill(0)

        val token = String(decrypted, Charsets.UTF_8)

        // SHA-256 完整性校验
        val digest = MessageDigest.getInstance("SHA-256").digest(decrypted)
        val hashB64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        if (hashB64 != TOKEN_INTEGRITY_HASH) {
            // 清零明文
            decrypted.fill(0)
            throw SecurityException("Token 完整性校验失败，解密结果可能被篡改")
        }

        return token
    }
}
