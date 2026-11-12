package com.azurlane.blyy.util.gameimport

import androidx.compose.runtime.Immutable

/**
 * 游戏资源导入页顶部的「设备解码能力」提示。
 *
 * 提示可以被用户关掉（点「不再提示」后按 [code] 落盘记忆），因此**身份与文案必须分开**：
 * 身份用稳定的 [code] 记忆，[text] 只负责展示。这样改文案不会让老用户的关闭状态失效，
 * 将来新增另一种设备提示也不会把旧提示一起静默掉。
 *
 * 纯数据 + 纯函数，方便单测，不依赖 Compose 之外的东西。
 */
@Immutable
data class DeviceCapabilityNotice(
    /** 稳定的提示身份，用于持久化「不再提示」；**改动等于让所有用户重新看到提示** */
    val code: String,
    val text: String
) {
    companion object {
        /** 探测确认设备不接受 ASTC 6x6，SD 小人 / 过场图改走 CPU 软解 */
        const val CODE_ASTC_6X6_SOFTWARE = "astc6_software"

        /** 探测本身不可用（拿不到 GLES3 上下文），无法断言设备是否支持 */
        const val CODE_ASTC_PROBE_UNAVAILABLE = "astc_probe_unavailable"

        /** 过场图与 SD 小人贴图用的块尺寸 */
        private const val ASTC_BLOCK_6X6 = 6

        /**
         * 由探测结果推导需要展示的提示。
         *
         * @param acceptedBlocks `CompressedTextureDecoder.astcBlockSupport()` 的结果：
         *   `null` = 探测不可用；空集 = 上下文正常但一块都不接受；其余为实际接受的块尺寸
         * @return 无需提示（6x6 可硬件解码）时返回 null
         */
        fun of(acceptedBlocks: Set<Int>?): DeviceCapabilityNotice? = when {
            // 探测失败 ≠ 设备不支持：文案必须留出「不确定」的余地，别把锅甩给 GPU
            acceptedBlocks == null -> DeviceCapabilityNotice(
                code = CODE_ASTC_PROBE_UNAVAILABLE,
                text = "暂时读不到本机 GPU 的 ASTC 能力：SD 小人与过场图会按 CPU 解码处理" +
                    "（画质一致，单张大图解码会慢一些）；重启应用后会重新探测"
            )

            ASTC_BLOCK_6X6 in acceptedBlocks -> null

            else -> DeviceCapabilityNotice(
                code = CODE_ASTC_6X6_SOFTWARE,
                text = "当前 GPU 未硬件支持 ASTC 6x6：SD 小人与过场图会自动改用 CPU 解码" +
                    "（画质一致，单张大图解码会慢一些）"
            )
        }
    }
}
