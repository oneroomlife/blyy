package com.azurlane.blyy.data.model

import kotlinx.serialization.Serializable

/**
 * 语音触发标签映射
 * 将关键词映射到舰娘语音场景(scene)标签
 * @param keyword 触发关键词
 * @param sceneTags 对应的语音场景标签列表（按优先级排序）
 * @param priority 匹配优先级，数字越大越优先
 * @param preferredSkinName 偏好皮肤名，为空则使用默认皮肤
 */
data class VoiceTagMapping(
    val keyword: String,
    val sceneTags: List<String>,
    val priority: Int = 0,
    val preferredSkinName: String = ""
)

/**
 * 聊天消息类型
 */
enum class ChatMessageType {
    USER,       // 用户发送的文本消息
    AI,         // 啾信回复的文本消息
    VOICE,      // 舰娘语音消息
    STICKER,    // 表情包消息
    SYSTEM      // 系统提示消息
}

/**
 * 消息发送状态机
 *
 * 状态流转：
 * SENDING → SUCCESS（正常完成）
 * SENDING → FAILED（网络错误 / 解析失败 / API 错误）
 * FAILED  → SENDING（用户点击重试）
 * FAILED  → SUCCESS（重试成功）
 *
 * 仅对 [ChatMessageType.USER] 和 [ChatMessageType.AI] 有效；
 * VOICE / STICKER / SYSTEM 消息恒为 SUCCESS（无需网络等待）。
 *
 * 向后兼容：旧数据反序列化时 [status] 字段缺失，默认 [SUCCESS]，
 * 不会破坏已有聊天记录。
 */
enum class MessageStatus {
    SENDING,    // 发送中 / AI 回复生成中
    SUCCESS,    // 发送成功
    FAILED      // 发送失败（可重试）
}

/**
 * 聊天消息
 *
 * @param status 消息发送状态，参见 [MessageStatus]。
 *  旧数据反序列化时缺失该字段会被 [kotlinx.serialization] 忽略（需 Json 配置 ignoreUnknownKeys），
 *  实际值默认为 [MessageStatus.SUCCESS]，保证向后兼容。
 */
@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String = ChatMessageType.USER.name,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val shipName: String = "",
    val voiceUrl: String = "",
    val stickerUrl: String = "", // 新增表情包URL字段
    val dialogue: String = "",
    val avatarUrl: String = "",
    /** 消息发送状态（SENDING/SUCCESS/FAILED），默认 SUCCESS 保证旧数据兼容 */
    val status: String = MessageStatus.SUCCESS.name
)

/**
 * 表情包资源模型
 */
@Serializable
data class StickerResource(
    val url: String,
    val tags: List<String>,
    val name: String = ""
)

/**
 * 消息分组位置枚举
 * 用于实现连续消息分组渲染：
 * - 头像仅在 SINGLE / FIRST 显示
 * - 圆角按组内位置变化
 * - 组内间距 4dp，组间间距 12dp
 */
enum class GroupPosition {
    /** 独立消息（前后不同发送者） */
    SINGLE,
    /** 组首条（显示头像，顶部圆角 12 12 4 4） */
    FIRST,
    /** 组中间（隐藏头像，全收窄 4 12 4 4） */
    MIDDLE,
    /** 组末条（隐藏头像，底部圆角+尾巴 4 12 12 4） */
    LAST
}

/**
 * 判断当前消息在分组中的位置
 * @param messages 完整消息列表
 * @param index 当前消息索引
 * @param senderOf 获取消息发送者标识的函数
 */
fun <T> getGroupPosition(messages: List<T>, index: Int, senderOf: (T) -> String): GroupPosition {
    val current = senderOf(messages[index])
    val prev = if (index > 0) senderOf(messages[index - 1]) else null
    val next = if (index < messages.size - 1) senderOf(messages[index + 1]) else null
    val sameAsPrev = prev == current
    val sameAsNext = next == current
    return when {
        !sameAsPrev && !sameAsNext -> GroupPosition.SINGLE
        !sameAsPrev && sameAsNext -> GroupPosition.FIRST
        sameAsPrev && sameAsNext -> GroupPosition.MIDDLE
        else -> GroupPosition.LAST
    }
}

/** 会话列表项（JUUSTAGRAM 设计规范） */
data class Conversation(
    val id: String,
    val name: String,
    val preview: String,
    val avatarEmoji: String? = null,
    val avatarEmojiBgColor: Long = 0xFFE0F2FE,
    val unreadCount: Int = 0,
    val isWaitingReply: Boolean = false,
    val subtitle: String? = null,
    val isSelected: Boolean = false,
    val isNavigable: Boolean = false
)

/** 提供 displayName 的接口，用于筛选弹窗的泛型 Pill 按钮 */
interface DisplayNameProvider {
    val displayName: String
}

/** 筛选 - 回复状态 */
enum class ReplyStatus(override val displayName: String) : DisplayNameProvider {
    ALL("全部"), REPLIED("已回复"), UNREPLIED("未回复")
}

/** 筛选 - 聊天类型 */
enum class ChatType(override val displayName: String) : DisplayNameProvider {
    ALL("全部"), PRIVATE("私人聊天"), CHANNEL("频道聊天")
}

/** 筛选 - 阵营 */
enum class Faction(override val displayName: String) : DisplayNameProvider {
    ALL("全阵营"), EAGLE_UNION("白鹰"), ROYAL_NAVY("皇家"),
    SAKURA_EMPIRE("重樱"), IRON_BLOOD("铁血"), DRAGON_EMPERY("东煌"),
    SARDEGNA("撒丁帝国"), NORTHERN_PARLIAMENT("北方联合")
}

/** 筛选条件 */
data class FilterCriteria(
    val replyStatus: ReplyStatus = ReplyStatus.ALL,
    val chatType: ChatType = ChatType.ALL,
    val faction: Faction = Faction.ALL
)

/**
 * 会话类型
 */
enum class SessionType {
    PRIVATE,    // 私聊（单舰娘）
    GROUP       // 群聊（多舰娘）
}

/**
 * 群聊成员（群内舰娘的配置快照）
 *
 * 每个成员保存完整的人格 + 语音配置，确保群聊中不同舰娘
 * 使用各自的提示词、语音、表情包设置，互不干扰。
 *
 * @param personaId 关联的舰娘人格配置 ID（用于成员去重和来源追踪）。为空表示临时成员
 */
@Serializable
data class GroupMember(
    val id: String = java.util.UUID.randomUUID().toString(),
    val personaId: String = "",
    /** 啾信显示名称 */
    val jiuxinName: String = "",
    /** 舰娘头像 URL */
    val avatarUrl: String = "",
    /** 人格提示词 */
    val systemPrompt: String = "",
    /** 语音舰娘名称 */
    val voiceShipName: String = "",
    /** 语音舰娘头像 URL */
    val voiceShipAvatar: String = "",
    /** 是否启用语音发送 */
    val voiceEnabled: Boolean = true,
    /** 语音随机触发概率 */
    val voiceRandomChance: Float = 0.1f,
    /** 语音触发关键词 */
    val voiceKeywords: String = "你好;早安;晚安;加油;辛苦了",
    /** 是否启用表情包 */
    val stickersEnabled: Boolean = true,
    /** 表情包发送概率 */
    val stickerChance: Float = 0.8f
)

/**
 * 聊天会话
 *
 * 配置隔离机制：每个会话保存创建时的完整配置快照，切换会话时恢复对应配置，
 * 确保不同舰娘的对话使用各自的 API、人格、语音等设置，互不干扰。
 *
 * 群聊扩展：当 [sessionType] 为 GROUP 时，[groupMembers] 保存群内所有舰娘的配置快照，
 * 群聊消息通过 [ChatMessage.shipName] / [ChatMessage.avatarUrl] 标识发送者，
 * API 调用时以各成员的 systemPrompt 构造群聊上下文。
 *
 * @param presetId 关联的预设 ID（用于按舰娘去重显示）。为空表示使用默认配置
 * @param avatarUrl 创建会话时的舰娘头像快照（用于列表显示和去重，不依赖预设是否存在）
 * @param jiuxinName 创建会话时的舰娘名称快照（用于列表显示和去重）
 * @param apiUrl API Base URL 快照
 * @param apiKey API Key 快照
 * @param model 模型名称快照
 * @param systemPrompt 人格提示词快照
 * @param voiceShipName 语音舰娘名称快照
 * @param voiceShipAvatar 语音舰娘头像 URL 快照
 * @param voiceEnabled 是否启用语音发送
 * @param voiceRandomChance 语音随机触发概率
 * @param voiceKeywords 语音触发关键词
 * @param stickersEnabled 是否启用表情包
 * @param stickerChance 表情包发送概率
 */
@Serializable
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val presetId: String = "",
    val avatarUrl: String = "",
    val jiuxinName: String = "",
    // ── 配置快照（用于会话级配置隔离） ──
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val voiceShipName: String = "",
    val voiceShipAvatar: String = "",
    val voiceEnabled: Boolean = true,
    val voiceRandomChance: Float = 0.1f,
    val voiceKeywords: String = "你好;早安;晚安;加油;辛苦了",
    val stickersEnabled: Boolean = true,
    val stickerChance: Float = 0.8f,
    // ── 群聊扩展字段 ──
    /** 会话类型：PRIVATE=私聊，GROUP=群聊 */
    val sessionType: String = SessionType.PRIVATE.name,
    /** 群聊成员列表（仅群聊会话有效，每个成员含完整人格配置快照） */
    val groupMembers: List<GroupMember> = emptyList(),
    /**
     * 群聊稳定标识（仅群聊会话有效）。
     *
     * 用于 [com.azurlane.blyy.viewmodel.JiuxinViewModel.computeShipKey] 中群聊去重 key 计算，
     * 替代原先的 session.name，确保群聊重命名后历史面板聚合不断裂。
     *
     * - 创建群聊时生成（[createGroupSession]）
     * - 删除群聊会话自动创建新会话时继承（[deleteSession]）
     * - 为当前群聊新建历史对话时继承（[createNewSessionForCurrentShip]）
     * - 重命名群聊时**不**变更（[renameGroupSession]）
     *
     * 空字符串表示旧数据（兼容性回退到 session.name + memberHash 计算）
     */
    val groupId: String = "",
    /** 会话级聊天背景 URL（空表示使用全局背景）。
     *  优先级：会话级 backgroundUrl > 全局 aiChatBackgroundUrl > 默认纯色 */
    val backgroundUrl: String = ""
) {
    /** 是否为群聊会话 */
    val isGroup: Boolean get() = sessionType == SessionType.GROUP.name
}

/**
 * 啾信预设配置
 *
 * 封装一套完整的 API + 舰娘人格配置，用户可保存多个预设，
 * 新建对话时可直接选择预设应用，无需重复输入配置。
 */
@Serializable
data class JiuxinPreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 预设名称（通常为舰娘名，如"标枪"） */
    val name: String = "",
    /** 啾信显示名称 */
    val jiuxinName: String = "",
    /** 啾信头像 URL */
    val avatarUrl: String = "",
    /** API Base URL */
    val apiUrl: String = "",
    /** API Key */
    val apiKey: String = "",
    /** 模型名称 */
    val model: String = "",
    /** 人格提示词 */
    val systemPrompt: String = "",
    /** 语音舰娘名称 */
    val voiceShipName: String = "",
    /** 语音舰娘头像 URL */
    val voiceShipAvatar: String = "",
    /** 是否启用语音发送 */
    val voiceEnabled: Boolean = true,
    /** 语音随机触发概率 */
    val voiceRandomChance: Float = 0.1f,
    /** 语音触发关键词 */
    val voiceKeywords: String = "你好;早安;晚安;加油;辛苦了",
    /** 是否启用表情包 */
    val stickersEnabled: Boolean = true,
    /** 表情包发送概率 */
    val stickerChance: Float = 0.8f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * API 配置（独立的 API 连接信息，可保存多套供选择）
 *
 * 与 [JiuxinPreset] 分离：API 配置可在多个舰娘人格间复用，
 * 用户无需为每个舰娘重复输入 API URL/Key/Model。
 */
@Serializable
data class ApiConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 配置名称（如"OpenAI 官方"、"第三方中转"） */
    val name: String = "",
    /** API Base URL */
    val apiUrl: String = "",
    /** API Key */
    val apiKey: String = "",
    /** 模型名称 */
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 舰娘人格配置（独立的角色人格信息，可保存多套供选择）
 *
 * 封装舰娘的头像、名称、人格提示词、语音设置等，
 * 与 API 配置分离，实现"API 配置 + 舰娘人格"的自由组合。
 */
@Serializable
data class PersonaConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 舰娘名称（如"标枪"） */
    val name: String = "",
    /** 啾信显示名称 */
    val jiuxinName: String = "",
    /** 舰娘头像 URL */
    val avatarUrl: String = "",
    /** 人格提示词 */
    val systemPrompt: String = "",
    /** 语音舰娘名称 */
    val voiceShipName: String = "",
    /** 语音舰娘头像 URL */
    val voiceShipAvatar: String = "",
    /** 是否启用语音发送 */
    val voiceEnabled: Boolean = true,
    /** 语音随机触发概率 */
    val voiceRandomChance: Float = 0.1f,
    /** 语音触发关键词 */
    val voiceKeywords: String = "你好;早安;晚安;加油;辛苦了",
    /** 是否启用表情包 */
    val stickersEnabled: Boolean = true,
    /** 表情包发送概率 */
    val stickerChance: Float = 0.8f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 群聊打字成员标识（轻量级，仅含 UI 显示所需字段）
 *
 * 用于群聊并发回复场景下，同时显示多位舰娘"正在输入"的指示器。
 * 与 [GroupMember] 分离：避免在 UI 状态中持有完整成员配置快照。
 */
@Serializable
data class TypingMember(
    val name: String,
    val avatarUrl: String
)

/**
 * 啾信聊天会话状态
 *
 * 群聊并发扩展：
 * - [typingMembers] 在群聊并发回复期间，记录正在打字的成员列表。
 *   非空时 UI 优先渲染每位成员的 TypingIndicator；为空时回退到 [isLoading] 单一指示器（私聊场景）。
 * - [isLoading] 在群聊整轮回复（含互回轮次）完成前保持 true，
 *   阻止 [ChatInputBar] 发送避免消息错乱。
 */
data class JiuxinChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedShipName: String = "",
    val selectedShipAvatar: String = "",
    val voiceKeywords: List<String> = emptyList(),
    val voiceTagMappings: List<VoiceTagMapping> = emptyList(),
    val typingMembers: List<TypingMember> = emptyList()
)
