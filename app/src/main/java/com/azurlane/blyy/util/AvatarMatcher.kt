package com.azurlane.blyy.util

import android.util.Log

/**
 * 舰娘头像纯匹配核心（无 Android Context 依赖，可 JVM 单测）。
 *
 * 输入为文件名索引（键 = 不含扩展名小写文件名，值 = 实际完整文件名），
 * 输出命中的完整文件名；所有匹配策略见 [match] / [matchOath] 的 KDoc。
 *
 * 命名约定（与 assets/blhx_avatar 资源一致）：
 * - 文件名 = 舰娘名称的**无声调全拼** + 可选皮肤后缀 + 扩展名
 *   （例如 `boge.webp` 对应"博格"，`z23_h.webp` 对应"Z23"的誓约婚皮）
 * - 皮肤后缀：`_g` 改造、`_h` 誓约婚皮、`_2`/`_3` 换装、`_alter` META、
 *   `_idol` μ兵装、`_younv` 幼女、`_super` 布里等
 */
object AvatarMatcher {

    private const val TAG = "AvatarMatcher"

    /**
     * 舰娘名 → 头像文件名（不含扩展名，保持原始大小写）。
     *
     * 匹配逻辑已统一到 [ShipNameMatcher]：原名/别名 → 全拼 → 变体 → 包含 →
     * 前缀（音节边界）→ 模糊子串 → 首字母/逐字段 → 拼写容错。
     * 这里只负责把「小写基名 → 真实文件名」的索引喂进去、再把结果映射回文件名。
     */
    fun match(shipName: String, index: Map<String, String>): String? {
        if (shipName.isBlank() || index.isEmpty()) return null
        val hit = ShipNameMatcher.match(shipName, index.keys)
        if (hit == null) {
            Log.w(TAG, "[MISS] '$shipName'")
            return null
        }
        val file = index[hit.key] ?: index[hit.key.lowercase()]
        Log.d(TAG, "[MATCH] '$shipName' -> ${hit.stage}: $file")
        return file
    }

    /**
     * 誓约头像：优先特殊变体 → 改造 `_g` → 婚皮 `_h` → 默认。
     *
     * 与 [ShipNameMatcher.matchOath] 同一套规则：META/μ兵装/幼女没有婚皮，
     * 誓约后仍显示该变体；改造舰誓约后仍显示改造头像。
     */
    fun matchOath(shipName: String, index: Map<String, String>): String? {
        if (shipName.isBlank() || index.isEmpty()) return null
        val key = ShipNameMatcher.matchOath(shipName, index.keys) ?: return null
        return index[key] ?: index[key.lowercase()]
    }
}
