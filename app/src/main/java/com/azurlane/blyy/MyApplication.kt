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
import com.azurlane.blyy.util.RefererResolver
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), ImageLoaderFactory {

    /**
     * 复用 Hilt 单例 OkHttpClient（AppModule 提供）。
     *
     * 原实现在此重复构建独立客户端（独立连接池/超时配置），造成：
     * 1. 双份连接池与线程资源浪费
     * 2. 网络配置分散在两处，维护时容易失同步
     *
     * 通过 newBuilder() 派生图片专用客户端：共享连接池、Dispatcher 与磁盘缓存，
     * 仅追加图片防盗链所需的 Referer/UA 拦截器。
     * Hilt 字段注入在 Application.onCreate() 中完成，而 newImageLoader()
     * 由 Coil 在首次图片请求时（远晚于 onCreate）调用，注入时机安全。
     */
    @Inject
    lateinit var sharedOkHttpClient: OkHttpClient

    override fun newImageLoader(): ImageLoader {
        val imageOkHttpClient = sharedOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val host = originalRequest.url.host

                // 根据图片域名动态设置 Referer，突破不同站点的防盗链
                val referer = RefererResolver.getRefererByHost(host)

                val newRequest = originalRequest.newBuilder()
                    // 伪装浏览器
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", referer)
                    .build()
                chain.proceed(newRequest)
            }
            .build()

        val builder = ImageLoader.Builder(this)
            .okHttpClient(imageOkHttpClient)
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
                    .maxSizePercent(0.1) // 磁盘缓存 10%，存储更多表情包
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
