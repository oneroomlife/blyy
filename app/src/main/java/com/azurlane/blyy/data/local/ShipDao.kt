package com.azurlane.blyy.data.local

import androidx.room.*
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.StudentFilterData
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ShipDao {
    @Query("SELECT * FROM ships ORDER BY name ASC")
    abstract fun getAllShips(): Flow<List<Ship>>

    /** ships 表 name + birthday 投影（upsert 前读取已有生日用于保留） */
    data class NameBirthday(val name: String, val birthday: String)

    @Query("SELECT * FROM ships WHERE isFavorite = 1 ORDER BY name ASC")
    abstract fun getFavoriteShips(): Flow<List<Ship>>

    @Query("""
        SELECT * FROM ships
        WHERE archiveType = :archiveType
        ORDER BY
            CASE rarity
                WHEN '海上传奇' THEN 1
                WHEN '决战方案' THEN 1
                WHEN '三星' THEN 1
                WHEN '超稀有' THEN 2
                WHEN '最高方案' THEN 2
                WHEN '二星' THEN 2
                WHEN '精锐' THEN 3
                WHEN '一星' THEN 3
                WHEN '稀有' THEN 4
                WHEN '普通' THEN 5
                ELSE 6
            END ASC,
            name ASC
    """)
    abstract fun getShipsByArchiveType(archiveType: String): Flow<List<Ship>>

    @Query("""
        SELECT * FROM ships
        WHERE isFavorite = 1 AND archiveType = :archiveType
        ORDER BY
            CASE rarity
                WHEN '海上传奇' THEN 1
                WHEN '决战方案' THEN 1
                WHEN '三星' THEN 1
                WHEN '超稀有' THEN 2
                WHEN '最高方案' THEN 2
                WHEN '二星' THEN 2
                WHEN '精锐' THEN 3
                WHEN '一星' THEN 3
                WHEN '稀有' THEN 4
                WHEN '普通' THEN 5
                ELSE 6
            END ASC,
            name ASC
    """)
    abstract fun getFavoriteShipsByArchiveType(archiveType: String): Flow<List<Ship>>

    @Query("SELECT * FROM ships WHERE name = :name AND archiveType = :archiveType LIMIT 1")
    abstract suspend fun getShipByName(name: String, archiveType: String): Ship?

    @Query("SELECT name FROM ships WHERE isFavorite = 1 AND archiveType = :archiveType")
    abstract suspend fun getFavoriteNamesByArchiveType(archiveType: String): List<String>

    /** 已有非空生日的 name → birthday 映射（upsert 保留用，避免 REPLACE 抹掉回填值） */
    @Query("SELECT name, birthday FROM ships WHERE archiveType = :archiveType AND birthday != ''")
    abstract suspend fun getBirthdaysByArchiveType(archiveType: String): List<NameBirthday>

    /** ships 表 name + filterData 投影（upsert 前读取已有筛选数据用于保留合并） */
    data class NameFilter(val name: String, val filterData: String)

    /** 已有非空 filterData 的 name → filterData 映射（upsert 保留用，避免 REPLACE 抹掉 enrich 结果） */
    @Query("SELECT name, filterData FROM ships WHERE archiveType = :archiveType AND filterData != ''")
    abstract suspend fun getFilterDatasByArchiveType(archiveType: String): List<NameFilter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(ships: List<Ship>)

    @Transaction
    open suspend fun upsertShips(ships: List<Ship>) {
        if (ships.isEmpty()) return
        val archiveType = ships.first().archiveType
        val favorites = getFavoriteNamesByArchiveType(archiveType).toSet()
        // OnConflictStrategy.REPLACE 是"删旧插新"，不在新对象里的列会丢——
        // 收藏状态、生日与 filterData 都必须先读库再决定写什么
        val birthdays = getBirthdaysByArchiveType(archiveType)
            .associate { it.name to it.birthday }
        val filterDatas = getFilterDatasByArchiveType(archiveType)
            .associate { it.name to it.filterData }
        val shipsToUpsert = ships.map { ship ->
            var merged = if (ship.name in favorites) ship.copy(isFavorite = true) else ship
            val existingBirthday = birthdays[ship.name]
            if (existingBirthday != null && ship.birthday != existingBirthday) {
                merged = merged.copy(birthday = existingBirthday)
            }
            // filterData 按完整度保留：列表刷新只解析到少数几维，不能把 enrich
            // 写入的完整 14 维数据冲回子集；反过来 enrich 的全量也能正常覆盖旧子集
            val existingFilter = filterDatas[ship.name]
            if (existingFilter != null &&
                StudentFilterData.richnessOf(existingFilter) > StudentFilterData.richnessOf(ship.filterData)
            ) {
                merged = merged.copy(filterData = existingFilter)
            }
            merged
        }
        insertAll(shipsToUpsert)
    }

    @Query("SELECT name FROM ships WHERE archiveType = :archiveType")
    abstract suspend fun getNamesByArchiveType(archiveType: String): List<String>

    @Query("DELETE FROM ships WHERE archiveType = :archiveType AND name IN (:names)")
    abstract suspend fun deleteShipsByArchiveTypeAndNames(archiveType: String, names: List<String>)

    /**
     * 删除指定档案类型中已不存在于 [keptNames] 的记录（单事务内完成）。
     *
     * 不使用 `NOT IN (:names)` 一次性传入全部保留名：舰船/学生数量（~850）已逼近
     * SQLite 默认 999 个宿主参数上限，超限抛 "too many SQL variables"。
     * 改为事务内取现有名单、内存差集后按 500 一批 `IN` 删除。
     */
    @Transaction
    open suspend fun deleteStaleShips(archiveType: String, keptNames: List<String>) {
        val kept = keptNames.toHashSet()
        val stale = getNamesByArchiveType(archiveType).filterNot { it in kept }
        stale.chunked(500).forEach { deleteShipsByArchiveTypeAndNames(archiveType, it) }
    }

    @Query("UPDATE ships SET avatarUrl = :avatar, borderUrl = :border, link = :link, type = :type, rarity = :rarity, faction = :faction, extra = :extra WHERE name = :name AND archiveType = :archiveType")
    abstract suspend fun updateShipMetadata(
        name: String,
        archiveType: String,
        avatar: String,
        border: String?,
        link: String,
        type: String,
        rarity: String,
        faction: String,
        extra: String
    )

    @Query("UPDATE ships SET isFavorite = :isFav WHERE name = :name AND archiveType = :archiveType")
    abstract suspend fun updateFavorite(name: String, archiveType: String, isFav: Boolean)

    /**
     * 生日回填：仅当库内值与名单值不同才写（名单是唯一权威源，且未知名单舰不受影响）。
     * 条件更新让重复调用天然幂等，不触发 Room 无效化监听器的无谓重发射。
     */
    @Query("UPDATE ships SET birthday = :birthday WHERE name = :name AND archiveType = 'DOCK' AND birthday != :birthday")
    abstract suspend fun updateDockBirthdayIfChanged(name: String, birthday: String)

    @Transaction
    open suspend fun seedDockBirthdays(birthdays: Map<String, String>) {
        if (birthdays.isEmpty()) return
        birthdays.forEach { (name, birthday) ->
            updateDockBirthdayIfChanged(name, birthday)
        }
    }

    /** 有生日数据的 DOCK 舰娘（日历页数据源，收藏/头像等字段随行携带） */
    @Query("SELECT * FROM ships WHERE archiveType = 'DOCK' AND birthday != '' ORDER BY name ASC")
    abstract fun getDockShipsWithBirthday(): Flow<List<Ship>>

    @Query("DELETE FROM ships WHERE archiveType = :archiveType")
    abstract suspend fun deleteByArchiveType(archiveType: String)
}
