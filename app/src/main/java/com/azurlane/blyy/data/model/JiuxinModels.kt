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
    SYSTEM      // 系统提示消息
}

/**
 * 聊天消息
 */
@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String = ChatMessageType.USER.name,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val shipName: String = "",
    val voiceUrl: String = "",
    val dialogue: String = "",
    val avatarUrl: String = ""
)

/**
 * 聊天会话
 */
@Serializable
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 啾信聊天会话状态
 */
data class JiuxinChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedShipName: String = "",
    val selectedShipAvatar: String = "",
    val voiceKeywords: List<String> = emptyList(),
    val voiceTagMappings: List<VoiceTagMapping> = emptyList()
)
