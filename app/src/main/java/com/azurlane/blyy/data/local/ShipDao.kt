package com.azurlane.blyy.data.local

import androidx.room.*
import com.azurlane.blyy.data.model.Ship
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ShipDao {
    @Query("SELECT * FROM ships ORDER BY name ASC")
    abstract fun getAllShips(): Flow<List<Ship>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(ships: List<Ship>)

    @Transaction
    open suspend fun upsertShips(ships: List<Ship>) {
        if (ships.isEmpty()) return
        val archiveType = ships.first().archiveType
        val favorites = getFavoriteNamesByArchiveType(archiveType).toSet()
        val shipsToUpsert = ships.map {
            if (it.name in favorites) it.copy(isFavorite = true) else it
        }
        insertAll(shipsToUpsert)
    }

    @Query("DELETE FROM ships WHERE archiveType = :archiveType AND name NOT IN (:names)")
    abstract suspend fun deleteOldShipsByArchiveType(archiveType: String, names: List<String>)

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

    @Query("DELETE FROM ships WHERE archiveType = :archiveType")
    abstract suspend fun deleteByArchiveType(archiveType: String)
}
