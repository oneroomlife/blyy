package com.example.blyy.util

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
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
    
    private var lastRequestTime = 0L
    private val minRequestInterval = 500L
    
    private val _isRateLimited = MutableStateFlow(false)
    val isRateLimited: StateFlow<Boolean> = _isRateLimited.asStateFlow()
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private fun getRandomUserAgent(): String {
        return userAgents.random()
    }
    
    private suspend fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTime
        if (elapsed < minRequestInterval) {
            delay(minRequestInterval - elapsed)
        }
        lastRequestTime = System.currentTimeMillis()
    }
    
    suspend fun fetchDocument(
        url: String,
        maxRetries: Int = 3,
        useCache: Boolean = true
    ): Result<Document> {
        if (useCache) {
            val cached = CacheManager.get<Document>(CacheNamespaces.HTML_DOCUMENT, url)
            if (cached != null) {
                Log.d(TAG, "Cache hit for: $url")
                return Result.success(cached)
            }
        }
        
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                enforceRateLimit()
                
                val userAgent = getRandomUserAgent()
                Log.d(TAG, "Fetching (attempt ${attempt + 1}/$maxRetries): $url")
                
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
                
                _isRateLimited.value = false
                Log.d(TAG, "Successfully fetched: $url")
                return Result.success(doc)
                
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for $url: ${e.message}")
                
                when {
                    e.message?.contains("403") == true || e.message?.contains("567") == true -> {
                        _isRateLimited.value = true
                        val backoffTime = (attempt + 1) * 2000L
                        Log.w(TAG, "Rate limited, backing off for ${backoffTime}ms")
                        delay(backoffTime)
                    }
                    e.message?.contains("429") == true -> {
                        _isRateLimited.value = true
                        val backoffTime = (attempt + 1) * 5000L
                        Log.w(TAG, "Too many requests, backing off for ${backoffTime}ms")
                        delay(backoffTime)
                    }
                    else -> {
                        delay((attempt + 1) * 1000L)
                    }
                }
            }
        }
        
        Log.e(TAG, "All retries exhausted for: $url")
        return Result.failure(lastException ?: Exception("Unknown error"))
    }
    
    suspend fun fetchImage(
        url: String,
        maxRetries: Int = 3,
        useCache: Boolean = true
    ): Result<ByteArray> {
        if (useCache) {
            val cached = CacheManager.get<ByteArray>(CacheNamespaces.IMAGE_DATA, url)
            if (cached != null) {
                Log.d(TAG, "Image cache hit for: $url")
                return Result.success(cached)
            }
        }
        
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                enforceRateLimit()
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", getRandomUserAgent())
                    .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://wiki.biligame.com/")
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }
                    
                    val bytes = response.body?.bytes() ?: throw Exception("Empty response")
                    
                    if (useCache) {
                        CacheManager.put(CacheNamespaces.IMAGE_DATA, url, bytes)
                    }
                    
                    Log.d(TAG, "Successfully fetched image: $url")
                    return Result.success(bytes)
                }
                
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Image fetch attempt ${attempt + 1} failed for $url: ${e.message}")
                delay((attempt + 1) * 1500L)
            }
        }
        
        return Result.failure(lastException ?: Exception("Unknown error"))
    }
    
    fun clearCache() {
        CacheManager.clearNamespace(CacheNamespaces.HTML_DOCUMENT)
        CacheManager.clearNamespace(CacheNamespaces.IMAGE_DATA)
        Log.d(TAG, "Cache cleared")
    }
    
    fun clearExpiredCache() {
        CacheManager.clearExpired()
        Log.d(TAG, "Expired cache entries cleared")
    }
    
    fun getCacheStats(): Pair<Int, Int> {
        val stats = CacheManager.getStats()
        return Pair(
            stats[CacheNamespaces.HTML_DOCUMENT] ?: 0,
            stats[CacheNamespaces.IMAGE_DATA] ?: 0
        )
    }
    
    fun preloadImages(urls: List<String>) {
        Log.d(TAG, "Preloading ${urls.size} images")
    }
}
