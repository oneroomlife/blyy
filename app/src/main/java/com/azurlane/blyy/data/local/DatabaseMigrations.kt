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

    /**
     * v8 → v9：为 ships 表补充 birthday 生日列（"MM-DD" 零填充格式，空 = 未知）。
     *
     * 仅加列，数据完整保留；列值由打包的静态名单 assets/blhx_birthdays.json
     * 在运行期回填（见 ShipRepository.ensureDockBirthdaysSeeded），
     * 迁移本身不携带数据，保持 Migration 纯结构变更的约定。
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `ships` ADD COLUMN `birthday` TEXT NOT NULL DEFAULT ''")
        }
    }
}
