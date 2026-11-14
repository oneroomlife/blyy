package com.azurlane.blyy.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.ShipCache

/**
 * 应用主数据库。
 *
 * - version 8：为 [GuessHistory] 补充 timestamp / mode 单列索引（修复 ORDER BY 排序扫描）
 * - version 9：为 [Ship] 补充 birthday 生日列（"MM-DD" 格式，空 = 未知），
 *   数据由静态名单运行期回填
 * - version 10：新增 ship_cache 表（语音/立绘/档案/详情的本地缓存），
 *   用于拆掉"断网即空白、冷启动必联网"的老问题（见 docs/FEATURE_EXPANSION_2026-11.md A3）
 * - exportSchema = true：从该版本起生成 schema JSON，便于后续编写基于快照的 Migration 测试
 *
 * 升级路径：v7 → v8 通过 [MIGRATION_7_8] 添加索引；v8 → v9 通过 [MIGRATION_8_9]
 * 加列，均不清库。历史版本 v1→v7 因无 schema JSON 无法回填 Migration，保留
 * [fallbackToDestructiveMigration] 作为安全网，仅当未来遗漏 Migration 时兜底。
 */
@Database(entities = [Ship::class, GuessHistory::class, ShipCache::class], version = 10, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shipDao(): ShipDao
    abstract fun guessHistoryDao(): GuessHistoryDao

    /** 语音/立绘/档案/详情的本地缓存（v10） */
    abstract fun shipCacheDao(): ShipCacheDao
}
