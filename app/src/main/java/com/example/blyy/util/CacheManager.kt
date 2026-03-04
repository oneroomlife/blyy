package com.example.blyy.util

import android.util.Log
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CacheManager {
    private const val TAG = "CacheManager"
    private const val DEFAULT_MAX_SIZE = 100
    private const val DEFAULT_EXPIRE_TIME_MS = 30 * 60 * 1000L

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val expiresInMs: Long = DEFAULT_EXPIRE_TIME_MS
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > expiresInMs
    }

    private class LRUCache<K, V>(
        private val maxSize: Int
    ) : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    private val caches = mutableMapOf<String, LRUCache<String, CacheEntry<*>>>()
    private val lock = Any()

    private fun <T> getOrCreateCache(namespace: String, maxSize: Int = DEFAULT_MAX_SIZE): LRUCache<String, CacheEntry<T>> {
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            return caches.getOrPut(namespace) { LRUCache<String, CacheEntry<*>>(maxSize) } as LRUCache<String, CacheEntry<T>>
        }
    }

    fun <T> put(namespace: String, key: String, data: T, expiresInMs: Long = DEFAULT_EXPIRE_TIME_MS) {
        synchronized(lock) {
            val cache = getOrCreateCache<T>(namespace)
            cache[key] = CacheEntry(data, System.currentTimeMillis(), expiresInMs)
            Log.d(TAG, "Cache put: $namespace/$key")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(namespace: String, key: String): T? {
        synchronized(lock) {
            val cache = getOrCreateCache<T>(namespace)
            val entry = cache[key]
            if (entry != null) {
                if (!entry.isExpired()) {
                    Log.d(TAG, "Cache hit: $namespace/$key")
                    return entry.data as T
                } else {
                    cache.remove(key)
                    Log.d(TAG, "Cache expired: $namespace/$key")
                }
            }
            return null
        }
    }

    fun <T> getOrPut(namespace: String, key: String, expiresInMs: Long = DEFAULT_EXPIRE_TIME_MS, loader: () -> T): T {
        synchronized(lock) {
            val cached = get<T>(namespace, key)
            if (cached != null) return cached
            
            val data = loader()
            put(namespace, key, data, expiresInMs)
            return data
        }
    }

    suspend fun <T> getOrPutSuspend(namespace: String, key: String, expiresInMs: Long = DEFAULT_EXPIRE_TIME_MS, loader: suspend () -> T): T {
        val cached = get<T>(namespace, key)
        if (cached != null) return cached
        
        val data = loader()
        put(namespace, key, data, expiresInMs)
        return data
    }

    fun remove(namespace: String, key: String) {
        synchronized(lock) {
            val cache = getOrCreateCache<Any>(namespace)
            cache.remove(key)
            Log.d(TAG, "Cache removed: $namespace/$key")
        }
    }

    fun clearNamespace(namespace: String) {
        synchronized(lock) {
            caches.remove(namespace)
            Log.d(TAG, "Cache namespace cleared: $namespace")
        }
    }

    fun clearAll() {
        synchronized(lock) {
            caches.clear()
            Log.d(TAG, "All caches cleared")
        }
    }

    fun clearExpired() {
        synchronized(lock) {
            caches.forEach { (namespace, cache) ->
                cache.entries.removeIf { it.value.isExpired() }
            }
            Log.d(TAG, "Expired cache entries cleared")
        }
    }

    fun getStats(): Map<String, Int> {
        synchronized(lock) {
            return caches.mapValues { it.value.size }
        }
    }

    fun getTotalSize(): Int {
        synchronized(lock) {
            return caches.values.sumOf { it.size }
        }
    }
}

object CacheNamespaces {
    const val SHIP_GALLERY = "ship_gallery"
    const val SHIP_VOICES = "ship_voices"
    const val HTML_DOCUMENT = "html_document"
    const val IMAGE_DATA = "image_data"
    const val SHIP_LIST = "ship_list"
}
