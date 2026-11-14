package com.azurlane.blyy.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azurlane.blyy.data.model.ShipCache

/**
 * 舰娘抓取结果缓存 DAO。
 *
 * 写入一律用「部分更新」语义（只覆盖非空字段），因为四类数据是**分别抓取**的：
 * 语音先回来时不应把已有的立绘/档案清空。为此不提供 `@Update` 整行覆盖，
 * 而是按字段 `COALESCE` 更新，再在不存在时插入。
 */
@Dao
abstract class ShipCacheDao {

    @Query("SELECT * FROM ship_cache WHERE shipName = :shipName AND archiveType = :archiveType LIMIT 1")
    abstract suspend fun get(shipName: String, archiveType: String): ShipCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(cache: ShipCache)

    @Query("DELETE FROM ship_cache")
    abstract suspend fun deleteAll()

    /**
     * 部分写入：只覆盖 [voicesJson] / [galleryJson] / [profileJson] / [detailJson] 中非空的那几段。
     *
     * 空串表示"本次没抓到/不更新"，`COALESCE(NULLIF(:x,''), 原值)` 正好表达这个语义。
     */
    @Query(
        """
        INSERT INTO ship_cache
            (shipName, archiveType, voicesJson, galleryJson, profileJson, detailJson, updatedAt)
        VALUES
            (:shipName, :archiveType,
             COALESCE(NULLIF(:voicesJson, ''), ''),
             COALESCE(NULLIF(:galleryJson, ''), ''),
             COALESCE(NULLIF(:profileJson, ''), ''),
             COALESCE(NULLIF(:detailJson, ''), ''),
             :updatedAt)
        ON CONFLICT(shipName, archiveType) DO UPDATE SET
            voicesJson  = COALESCE(NULLIF(:voicesJson, ''),  ship_cache.voicesJson),
            galleryJson = COALESCE(NULLIF(:galleryJson, ''), ship_cache.galleryJson),
            profileJson = COALESCE(NULLIF(:profileJson, ''), ship_cache.profileJson),
            detailJson  = COALESCE(NULLIF(:detailJson, ''),  ship_cache.detailJson),
            updatedAt   = :updatedAt
        """
    )
    abstract suspend fun patch(
        shipName: String,
        archiveType: String,
        voicesJson: String,
        galleryJson: String,
        profileJson: String,
        detailJson: String,
        updatedAt: Long
    )

    /** 便捷重载：只更新语音段 */
    suspend fun putVoices(shipName: String, archiveType: String, json: String) =
        patch(shipName, archiveType, json, "", "", "", System.currentTimeMillis())

    /** 便捷重载：只更新立绘段 */
    suspend fun putGallery(shipName: String, archiveType: String, json: String) =
        patch(shipName, archiveType, "", json, "", "", System.currentTimeMillis())

    /** 便捷重载：只更新档案段 */
    suspend fun putProfile(shipName: String, archiveType: String, json: String) =
        patch(shipName, archiveType, "", "", json, "", System.currentTimeMillis())

    /** 便捷重载：只更新详情段 */
    suspend fun putDetail(shipName: String, archiveType: String, json: String) =
        patch(shipName, archiveType, "", "", "", json, System.currentTimeMillis())
}
