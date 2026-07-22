package com.azurlane.blyy.di

import android.content.Context
import androidx.room.Room
import com.azurlane.blyy.data.local.AppDatabase
import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.util.AppIconManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "blhx_db"
        )
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideShipDao(db: AppDatabase): ShipDao {
        return db.shipDao()
    }

    @Provides
    @Singleton
    fun provideGuessHistoryDao(db: AppDatabase): GuessHistoryDao {
        return db.guessHistoryDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "okhttp_cache")
        val cache = Cache(cacheDir, 20L * 1024 * 1024) // 20MB disk cache
        // 群聊并发优化：提高单 host 并发请求数到 10（默认 5）
        // 群聊模式下多位舰娘同时调用同一 API 端点，默认 5 可能限制并发
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 10
        }
        return OkHttpClient.Builder()
            .cache(cache)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 整个请求总超时兜底：防止 TCP 连接成功但服务端持续慢速输出
            // 不触发 readTimeout 的场景（如非流式长回复），避免协程无限等待。
            // 120s 覆盖 max_tokens=1024 的慢速模型生成时间。
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideAppIconManager(@ApplicationContext context: Context): AppIconManager {
        return AppIconManager(context)
    }
}
