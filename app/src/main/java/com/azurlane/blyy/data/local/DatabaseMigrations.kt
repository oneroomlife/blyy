package com.azurlane.blyy.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移集合。
 *
 * 每次实体结构变更必须：
 * 1. 递增 [AppDatabase] 的 version
 * 2. 新增对应的 [Migration]
 * 3. 在 [AppModule.provideDatabase] 中通过 [androidx.room.RoomDatabase.Builder.addMigrations] 注册
 *
 * 历史版本（v1→v7）因 exportSchema=false 无法回填 Migration，保留 fallbackToDestructiveMigration 兜底；
 * 从 v8 起所有升级路径必须显式提供 Migration，不再依赖破坏性迁移。
 */
object DatabaseMigrations {

    /**
     * v7 → v8：为 guess_history 表补充 timestamp / mode 单列索引。
     *
     * 不修改表结构，仅添加索引，数据完整保留。
     * 索引若已存在（如开发期手动创建）则 IF NOT EXISTS 跳过，保证幂等。
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_guess_history_timestamp` ON `guess_history` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_guess_history_mode` ON `guess_history` (`mode`)")
        }
    }
}
