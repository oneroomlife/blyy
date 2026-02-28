package com.example.blyy.data.local

import androidx.room.*
import com.example.blyy.data.model.Ship
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipDao {
    @Query("SELECT * FROM ships ORDER BY name ASC")
    fun getAllShips(): Flow<List<Ship>>

    @Query("SELECT * FROM ships WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteShips(): Flow<List<Ship>>

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
    fun getShipsByArchiveType(archiveType: String): Flow<List<Ship>>

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
    fun getFavoriteShipsByArchiveType(archiveType: String): Flow<List<Ship>>

    @Query("SELECT * FROM ships WHERE name = :name LIMIT 1")
    suspend fun getShipByName(name: String): Ship?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ships: List<Ship>)

    @Transaction
    suspend fun upsertShips(ships: List<Ship>) {
        insertAll(ships)
        ships.forEach { ship ->
            updateShipMetadata(
                ship.name,
                ship.avatarUrl,
                ship.borderUrl,
                ship.link,
                ship.type,
                ship.rarity,
                ship.faction,
                ship.extra,
                ship.archiveType
            )
        }
    }

    @Query("UPDATE ships SET avatarUrl = :avatar, borderUrl = :border, link = :link, type = :type, rarity = :rarity, faction = :faction, extra = :extra, archiveType = :archiveType WHERE name = :name")
    suspend fun updateShipMetadata(
        name: String,
        avatar: String,
        border: String?,
        link: String,
        type: String,
        rarity: String,
        faction: String,
        extra: String,
        archiveType: String
    )

    @Query("UPDATE ships SET isFavorite = :isFav WHERE name = :name")
    suspend fun updateFavorite(name: String, isFav: Boolean)
}