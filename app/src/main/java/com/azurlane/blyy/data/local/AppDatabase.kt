package com.azurlane.blyy.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.data.model.Ship

@Database(entities = [Ship::class, GuessHistory::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shipDao(): ShipDao
    abstract fun guessHistoryDao(): GuessHistoryDao
}
