package com.azurlane.blyy.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NetworkHelper"

@Singleton
class NetworkHelper @Inject constructor() {

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
    )

    private val lastRequestTime = AtomicLong(0L)
    private val minRequestInterval = 500L
    private val rateLimitMutex = Mutex()

    private fun getRandomUserAgent(): String {
        return userAgents.random()
    }

    private suspend fun enforceRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastRequestTime.get()
            val elapsed = now - last
            if (elapsed < minRequestInterval) {
                delay(minRequestInterval - elapsed)
            }
            lastRequestTime.set(System.currentTimeMillis())
        }
    }

    suspend fun fetchDocument(
        url: String,
        maxRetries: Int = 3,
        useCache: Boolean = true
    ): Result<Document> = withContext(Dispatchers.IO) {
        if (useCache) {
            val cached = CacheManager.get<Document>(CacheNamespaces.HTML_DOCUMENT, url)
            if (cached != null) {
                Log.d(TAG, "Cache hit for: $url")
                return@withContext Result.success(cached)
            }
        }

        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                enforceRateLimit()

                val userAgent = getRandomUserAgent()
                Log.d(TAG, "Fetching (attempt ${attempt + 1}/$maxRetries): $url")

                // Jsoup.connect().get() 为阻塞式 HTTP 调用，必须运行在 IO 线程
                val doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cache-Control", "max-age=0")
                    .referrer("https://wiki.biligame.com/blhx/%E9%A6%96%E9%A1%B5")
                    .timeout(25000)
                    .followRedirects(true)
                    .maxBodySize(0)
                    .get()

                if (useCache) {
                    CacheManager.put(CacheNamespaces.HTML_DOCUMENT, url, doc)
                }

                Log.d(TAG, "Successfully fetched: $url")
                return@withContext Result.success(doc)

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for $url: ${e.message}")

                when {
                    e.message?.contains("403") == true || e.message?.contains("567") == true -> {
                        // 被反爬拦截：指数退避后重试
                        delay((attempt + 1) * 2000L)
                    }
                    e.message?.contains("429") == true -> {
                        // 请求过于频繁：更长的退避
                        delay((attempt + 1) * 5000L)
                    }
                    else -> {
                        delay((attempt + 1) * 1000L)
                    }
                }
            }
        }

        Log.e(TAG, "All retries exhausted for: $url")
        Result.failure(lastException ?: Exception("Unknown error"))
    }
}
