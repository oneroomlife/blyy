package com.azurlane.blyy.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份文件格式（单一 JSON 文件，人可读、可 diff、可手工检查）。
 *
 * 设计原则：
 * 1. **只存"用户产生的状态"**：誓约/收藏、答题成绩、偏好设置。
 *    立绘/语音/舰娘名录来自 wiki 与游戏资源，属于可再生成数据，不进备份；
 * 2. **凭证永不进备份**：AI API Key、会话快照（含 Key 副本）、啾信预设、游戏 UID/服务器
 *    全部排除。理由是这些字段在设备内是 Keystore 加密的，跨设备必然解不开，
 *    导出明文反而制造泄露面；
 * 3. **schema 版本化**：`schema` 字段决定恢复逻辑分支，未知版本直接拒绝而不是"尽力而为"；
 * 4. **元信息齐全**：写入时记录 app 版本与时间，恢复前 UI 可以展示"这份备份来自哪个版本"。
 */
@Serializable
data class BackupPayload(
    val schema: Int = CURRENT_SCHEMA,
    val appVersion: String = "",
    val appVersionCode: Int = 0,
    /** 备份生成时间（毫秒） */
    val createdAt: Long = 0L,
    /** 舰娘状态（目前只有誓约=收藏这一位，后续扩展字段保持向后兼容） */
    val shipStates: List<ShipStateBackup> = emptyList(),
    /** 答题成绩（猜舰娘历史记录） */
    val scores: List<ScoreBackup> = emptyList(),
    /** 偏好设置（已按类型编码，见 [PrefBackup]） */
    val preferences: List<PrefBackup> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /** 恢复时接受的最低/最高 schema；跨大版本不兼容时明确拒绝 */
        const val MIN_SUPPORTED_SCHEMA = 1
        const val MAX_SUPPORTED_SCHEMA = 1
    }

    /** 备份内容摘要，用于 UI 展示（不泄露具体内容） */
    fun summary(): String = buildString {
        append("${shipStates.size} 个誓约/收藏")
        append(" · ${scores.size} 条成绩")
        append(" · ${preferences.size} 项设置")
    }
}

/** 单个舰娘的持久状态 */
@Serializable
data class ShipStateBackup(
    val name: String,
    @SerialName("archive_type") val archiveType: String = "DOCK",
    val favorite: Boolean = false
)

/** 一局答题成绩（与 `guess_history` 行一一对应，sessionId 用于去重） */
@Serializable
data class ScoreBackup(
    val sessionId: String = "",
    val mode: String = "IMAGE",
    val difficulty: String = "EASY",
    val totalQuestions: Int = 0,
    val correctAnswers: Int = 0,
    val totalScore: Int = 0,
    val hintsUsedTotal: Int = 0,
    val skippedQuestions: Int = 0,
    val totalPossibleScore: Int = 0,
    val timestamp: Long = 0L,
    val accuracy: Float = 0f,
    val averageScore: Float = 0f
)

/**
 * 一项偏好设置。
 *
 * 为什么不直接塞 `JsonElement`：DataStore 的 key 是**带类型**的，
 * 恢复时必须用与原类型一致的 key 写回（`floatPreferencesKey` vs `intPreferencesKey`），
 * 否则 `1.15f` 会被写成 `1`。因此显式记录 [type]。
 */
@Serializable
data class PrefBackup(
    val name: String,
    /** string / bool / int / long / float / string_set */
    val type: String,
    /** 标量值的文本表示；string_set 时为空串 */
    val value: String = "",
    /** 仅 type == string_set 时有值 */
    val setValues: List<String>? = null
) {
    companion object {
        const val TYPE_STRING = "string"
        const val TYPE_BOOL = "bool"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_STRING_SET = "string_set"
    }
}
