package com.example.blyy.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.blyy.data.model.Ship // 导入你的实体类

// 1. 指定数据库包含的表 (Ship::class) 和版本号
@Database(entities = [Ship::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // 2. 必须提供一个抽象方法来获取 DAO
    // 这就是为什么 AppModule 里能调用 db.shipDao() 的原因
    abstract fun shipDao(): ShipDao
}