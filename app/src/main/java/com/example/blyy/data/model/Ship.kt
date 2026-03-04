package com.example.blyy.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "ships")
data class Ship(
    @PrimaryKey val name: String,
    val avatarUrl: String,
    val borderUrl: String?,
    val link: String,     // 用于跳转详情页的 Wiki 后缀

    // 筛选字段 (对应 param1 - param4)
    val type: String,     // 驱逐, 轻巡... / 攻击类型...
    val rarity: String,   // 超稀有... / 星级...
    val faction: String,  // 皇家, 白鹰... / 学园...
    val extra: String,    // 改造, META... / 角色定位...

    val archiveType: String = "DOCK", // DOCK or STUDENT
    val isFavorite: Boolean = false // 用户收藏状态
)
