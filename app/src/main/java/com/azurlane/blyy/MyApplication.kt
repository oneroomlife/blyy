package com.azurlane.blyy

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.azurlane.blyy.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MyApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS) // 增加超时时间以应对弱网
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true) // 开启自动重试
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val host = originalRequest.url.host

                // 根据图片域名动态设置 Referer，突破不同站点的防盗链
                val referer = when {
                    // gamekee CDN — 需要 gamekee 的 Referer
                    host.contains("gamekee.com") || host.contains("gamekee") ->
                        "https://www.gamekee.com/"
                    // B站图片 — 需要 biligame 的 Referer
                    host.contains("biligame.com") || host.contains("hdslb.com") ->
                        "https://wiki.biligame.com/"
                    // 其他域名 — 使用通用 Referer
                    else -> "https://www.google.com/"
                }

                val newRequest = originalRequest.newBuilder()
                    // 伪装浏览器
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", referer)
                    .build()
                chain.proceed(newRequest)
            }
            .build()

        val builder = ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                // 开启 GIF 支持
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 使用 25% 的应用内存作为图片缓存
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.1) // 增加到 10% 的磁盘空间以存储更多表情包
                    .build()
            }
            // 优化策略：优先使用缓存，离线可用
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)

        // 仅在 Debug 包启用 Coil 调试日志，避免 Release 包性能损耗
        if (BuildConfig.DEBUG) {
            builder.logger(DebugLogger())
        }

        return builder.build()
    }
}