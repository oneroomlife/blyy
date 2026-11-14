package com.azurlane.blyy.util.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的静态加密（AES-256-GCM + Android Keystore）。
 *
 * 背景：`PlayerSettingsDataStore` 里曾以明文存 AI API Key、API 配置 JSON（内含 Key）、
 * 会话快照（内含 Key 副本）与游戏 UID/服务器。任何能读到 Preferences 文件的路径
 * （root、备份、`adb backup`、第三方文件管理器、云同步误传）都会直接泄露凭证。
 *
 * 设计约束（按重要性）：
 * 1. **绝不阻断功能**：Keystore 在部分定制 ROM / 无安全硬件的设备上可能抛异常。
 *    此时 [encrypt] 降级为明文返回并记日志，应用照常可用——安全是增强，不是单点故障。
 * 2. **向后兼容**：历史上写入的是明文，[decrypt] 对不带 [PREFIX] 的值原样返回；
 *    下一次写入会把它换成密文，无需一次性迁移脚本，也不会因迁移失败丢数据。
 * 3. **可判定损坏**：[tryDecrypt] 区分"未配置"与"解不开"，调用方据此决定是否保留原始密文。
 *
 * 性能：DataStore 每次写入都会让所有派生 Flow 重新发射，而 `ai_chat_sessions` 这类
 * 几 KB 的 JSON 每次都跑一遍 Keystore AES-GCM 是纯浪费（播放会话每 5 秒写一次即触发）。
 * 因此解密结果按**密文**做小容量 LRU 缓存（最多 8 条），密文不变就不重复解密。
 *
 * 密文格式：`enc1:` + Base64(iv(12B) + ciphertext + tag(16B))。
 */
object SecretCipher {

    private const val TAG = "SecretCipher"

    /** 密文前缀。带版本号，未来换算法时可识别旧格式。 */
    private const val PREFIX = "enc1:"

    private const val KEY_ALIAS = "blyy_local_secret_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val DECRYPT_CACHE_SIZE = 8

    /** Keystore 整体不可用（无安全硬件/Provider 异常）时的熔断，避免每次读写都抛异常 */
    @Volatile
    private var keystoreUnavailable = false

    /** 密文 → 明文 的 LRU（accessOrder=true），避免同一段 JSON 被反复解密 */
    private val decryptCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > DECRYPT_CACHE_SIZE
    }

    /** 判断一个已存值是否为本工具产出的密文 */
    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    /**
     * 加密。空串直接返回，避免给"未配置"造出密文（会导致 UI 判断已配置）。
     * 失败时降级明文（见类注释第 1 条）。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        if (keystoreUnavailable) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(cipherText, 0, packed, iv.size, cipherText.size)
            val encoded = PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
            // 顺手把新密文写进解密缓存：写后立刻读取（DataStore 常见模式）就不用再解一次
            synchronized(decryptCache) { decryptCache[encoded] = plain }
            encoded
        } catch (e: KeyStoreException) {
            markUnavailable(e)
            plain
        } catch (e: UnrecoverableKeyException) {
            markUnavailable(e)
            plain
        } catch (e: ProviderException) {
            markUnavailable(e)
            plain
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed, fallback to plaintext", t)
            plain
        }
    }

    /**
     * 解密。非 [PREFIX] 开头的值视为历史明文，原样返回。
     * 解密失败返回空串（等价于"未配置"）。
     */
    fun decrypt(value: String?): String = tryDecrypt(value) ?: ""

    /**
     * 与 [decrypt] 相同，但**能区分"未配置"与"解不开"**：
     * - 非密文 / 空值 → 返回原文（可能为空串）
     * - 密文但解密失败（Keystore 被清、换机恢复备份、包名/签名变更）→ 返回 null
     *
     * 调用方据此可以「读时降级、写时保留原始密文」，避免把用户数据静默覆盖成空。
     */
    fun tryDecrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return ""
        if (!isEncrypted(value)) return value

        synchronized(decryptCache) {
            decryptCache[value]?.let { return it }
        }
        if (keystoreUnavailable) return null

        return try {
            val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES)
            )
            val plain = String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
            synchronized(decryptCache) { decryptCache[value] = plain }
            plain
        } catch (e: AEADBadTagException) {
            // 单条数据坏了（密钥被清/密文损坏），不影响其他字段，也不该熔断整个 Keystore
            Log.w(TAG, "decrypt failed (bad tag), treat as unset")
            null
        } catch (e: KeyStoreException) {
            markUnavailable(e)
            null
        } catch (e: UnrecoverableKeyException) {
            markUnavailable(e)
            null
        } catch (e: ProviderException) {
            markUnavailable(e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed", t)
            null
        }
    }

    /** 供设置页「导出诊断」使用：判断字段名是否需要打码 */
    fun looksSensitive(keyName: String): Boolean {
        val lower = keyName.lowercase()
        return SENSITIVE_HINTS.any { lower.contains(it) }
    }

    /** 测试/排查用：当前是否处于 Keystore 熔断状态 */
    fun isKeystoreUnavailable(): Boolean = keystoreUnavailable

    private val SENSITIVE_HINTS = listOf(
        "api_key", "apikey", "token", "secret", "password", "passwd",
        "uid", "server", "auth"
    )

    private fun markUnavailable(error: Throwable) {
        if (!keystoreUnavailable) {
            keystoreUnavailable = true
            Log.e(TAG, "AndroidKeyStore unavailable, sensitive fields fall back to plaintext", error)
        }
    }

    private fun obtainKey(): SecretKey {
        synchronized(this) {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // 不做生物识别绑定：后台服务（悬浮窗/小组件）也要能解密
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
