package com.azurlane.blyy.util.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
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
 * 1. **绝不阻断功能**：Keystore 在部分定制 ROM / 无安全硬件的设备上可能抛
 *    `KeyStoreException` / `ProviderException`。此时 [encrypt] 降级为明文返回并记日志，
 *    应用照常可用——安全是增强，不是单点故障。
 * 2. **向后兼容**：历史上写入的是明文，[decrypt] 对不带 [PREFIX] 的值原样返回；
 *    下一次写入（[PlayerSettingsDataStore] 的 setter 或 [PlayerSettingsDataStore.migrateSecretsToEncrypted]）
 *    会把它换成密文，无需一次性迁移脚本，也不会因迁移失败丢数据。
 * 3. **可判定损坏**：解密失败返回空串而不是抛异常，调用方看到的是「未配置」——
 *    用户可以重新填 Key，而不是面对一个崩溃的 App。
 *
 * 密文格式：`enc1:` + Base64(iv(12B) + ciphertext + tag(16B))。
 * GCM 的 IV 每次加密随机生成（`setRandomizedEncryptionRequired(true)` 默认开启）。
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

    /** Keystore 不可用时的熔断，避免每次读写都抛一次异常（异常栈有成本） */
    @Volatile
    private var keystoreUnavailable = false

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
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed, fallback to plaintext", t)
            plain
        }
    }

    /**
     * 解密。非 [PREFIX] 开头的值视为历史明文，原样返回。
     * 解密失败返回空串（等价于"未配置"），并记一次日志。
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
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 密钥被清除（换机/恢复备份/系统重置 Keystore）时 GCM 校验必然失败
            Log.w(TAG, "decrypt failed", t)
            null
        }
    }

    /** 幂等：明文→密文，已加密或空值原样返回 */
    fun encryptIfNeeded(value: String?): String {
        if (value.isNullOrEmpty()) return value ?: ""
        return if (isEncrypted(value)) value else encrypt(value)
    }

    /** 供设置页「导出诊断」使用：判断字段名是否需要打码 */
    fun looksSensitive(keyName: String): Boolean {
        val lower = keyName.lowercase()
        return SENSITIVE_HINTS.any { lower.contains(it) }
    }

    private val SENSITIVE_HINTS = listOf(
        "api_key", "apikey", "token", "secret", "password", "passwd",
        "uid", "server", "auth"
    )

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
