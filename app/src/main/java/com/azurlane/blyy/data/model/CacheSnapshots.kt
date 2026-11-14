package com.azurlane.blyy.data.model

import kotlinx.serialization.Serializable

/**
 * 缓存快照的序列化 DTO。
 *
 * 为什么需要它们：`VoicesData` / `ShipGallery` 是展示模型，其中 `ShipGallery` 用了
 * `List<Pair<String, String>>`（label → url），而 `Pair` 没有 kotlinx 序列化器。
 * 直接给展示模型加 `@Serializable` 会把"持久化格式"和"UI 结构"焊死，以后改 UI 就得写
 * 数据迁移；这里用独立 DTO 做转换，缓存格式与 UI 解耦。
 */

/** 语音快照：与 `ShipRepository.VoicesData` 一一对应（含秘书舰回退用的 Q 版小人 URL） */
@Serializable
data class VoicesCacheSnapshot(
    val voices: List<VoiceLine> = emptyList(),
    val defaultFigureUrl: String? = null,
    /** 皮肤名 → 立绘 URL */
    val skinFigureMap: Map<String, String> = emptyMap(),
    val defaultChibiUrl: String? = null,
    val fetchedAt: Long = 0L
)

/** 立绘快照：`ShipGallery` 的可序列化形态 */
@Serializable
data class GalleryCacheSnapshot(
    val shipName: String = "",
    val illustrations: List<LabeledUrl> = emptyList(),
    val figures: List<LabeledUrl> = emptyList(),
    val fetchedAt: Long = 0L
)

/** (标签, URL) —— 替代 UI 层的 `Pair<String, String>` */
@Serializable
data class LabeledUrl(
    val label: String = "",
    val url: String = ""
)

/** 档案快照：`ShipCharacterInfo` 的可序列化形态 */
@Serializable
data class ProfileCacheSnapshot(
    val identity: String = "",
    val personality: String = "",
    val keywords: String = "",
    val possessions: String = "",
    val hairColor: String = "",
    val eyeColor: String = "",
    val moePoints: String = "",
    val fetchedAt: Long = 0L
)

// ── DTO ↔ 展示模型 转换（VoicesData 的转换在 ShipRepository 内完成，避免 model 反向依赖） ──

fun List<Pair<String, String>>.toLabeledUrls() =
    map { LabeledUrl(label = it.first, url = it.second) }

fun ProfileCacheSnapshot.toCharacterInfo(shipName: String = "") = ShipCharacterInfo(
    shipName = shipName,
    identity = identity,
    personality = personality,
    keywords = keywords,
    possessions = possessions,
    hairColor = hairColor,
    eyeColor = eyeColor,
    moePoints = moePoints
)

fun ShipCharacterInfo.toProfileSnapshot() = ProfileCacheSnapshot(
    identity = identity,
    personality = personality,
    keywords = keywords,
    possessions = possessions,
    hairColor = hairColor,
    eyeColor = eyeColor,
    moePoints = moePoints,
    fetchedAt = System.currentTimeMillis()
)