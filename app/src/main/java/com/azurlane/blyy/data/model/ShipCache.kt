package com.azurlane.blyy.data.model

import androidx.room.Entity
import androidx.room.Index

/**
 * 单个舰娘的**网络抓取结果缓存**（v10 新增）。
 *
 * 背景：此前语音/立绘/档案只存在内存 Map（[com.azurlane.blyy.util.CacheManager]）与
 * 5 分钟的 HTML 缓存里，进程一死就没了 —— 断网时语音列表、立绘浏览、角色档案**全空**，
 * 每次冷启动都要重新打 wiki。这张表把「抓过一次的舰娘」固化到本地：
 *
 * - 一个舰娘一行，四段 JSON 分别对应四类数据，按需回填（语音先抓就只写 voicesJson）；
 * - 主键 `(shipName, archiveType)`，与 `ships` 表的口径一致（DOCK / STUDENT）；
 * - JSON 里存的是**展示模型**（VoiceLine / ShipGallery / ShipCharacterInfo / ShipDetail），
 *   不存 HTML，避免体积与解析成本；
 * - `updatedAt` 供未来的过期策略/增量刷新使用（当前不做自动过期：
 *   游戏新增语音属于低频事件，用户可手动刷新）。
 */
@Entity(
    tableName = "ship_cache",
    primaryKeys = ["shipName", "archiveType"],
    indices = [Index(value = ["updatedAt"])]
)
data class ShipCache(
    val shipName: String,
    val archiveType: String = "DOCK",
    /** List<VoiceLine> 的 JSON；空串表示未抓过 */
    val voicesJson: String = "",
    /** ShipGallery 的 JSON */
    val galleryJson: String = "",
    /** ShipCharacterInfo 的 JSON（身份/性格/萌点等档案字段） */
    val profileJson: String = "",
    /** ShipDetail 的 JSON（属性/技能/装备/CV/画师） */
    val detailJson: String = "",
    val updatedAt: Long = 0L
) {
    fun hasVoices(): Boolean = voicesJson.isNotBlank()
    fun hasGallery(): Boolean = galleryJson.isNotBlank()
    fun hasProfile(): Boolean = profileJson.isNotBlank()
    fun hasDetail(): Boolean = detailJson.isNotBlank()
}
