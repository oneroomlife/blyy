package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.LeaderboardCategory
import com.azurlane.blyy.data.model.LeaderboardData
import com.azurlane.blyy.data.repository.LeaderboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeaderboardUiState(
    val isLoading: Boolean = false,
    val leaderboardData: LeaderboardData = LeaderboardData(),
    val selectedCategory: LeaderboardCategory = LeaderboardCategory.IMAGE_EASY,
    val error: String? = null
)

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val repository: LeaderboardRepository,
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "LeaderboardViewModel"
        /** 内存缓存有效期：5 分钟（毫秒） */
        private const val MEMORY_CACHE_TTL_MS = 5L * 60 * 1000
        /** 本地缓存有效期：1 小时（毫秒），超过仍可显示但会触发后台刷新 */
        private const val LOCAL_CACHE_TTL_MS = 60L * 60 * 1000
    }

    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    // ── 内存缓存（带时间戳，避免短时间内重复请求网络） ──
    /** 内存中的排行榜数据，null 表示尚未加载过 */
    private var memoryCache: LeaderboardData? = null
    /** 内存缓存写入时间戳（毫秒） */
    private var memoryCacheTimestamp: Long = 0L

    // 用户 UID / 服务器（复用小助手配置）
    val userUid = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val userServer = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 检查用户是否已配置 UID 和服务器 */
    fun isUserInfoConfigured(): Boolean {
        return userUid.value.isNotBlank() && userServer.value.isNotBlank()
    }

    fun selectCategory(category: LeaderboardCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /**
     * 刷新排行榜数据（带缓存策略）。
     *
     * 策略：
     * 1. 内存缓存未过期（≤ [MEMORY_CACHE_TTL_MS]）且非强制刷新 → 直接使用，不发起网络请求
     * 2. 有内存缓存但过期或强制刷新 → 先展示内存数据，后台刷新（强制刷新时显示 loading）
     * 3. 无内存缓存但有本地缓存 → 先展示本地数据，后台静默刷新
     * 4. 无任何缓存 → 显示加载状态，发起网络请求
     *
     * @param force 是否强制刷新（忽略缓存，始终发起网络请求并显示 loading）
     */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val memCache = memoryCache
        val memCacheValid = memCache != null &&
            (now - memoryCacheTimestamp) < MEMORY_CACHE_TTL_MS

        // 1. 内存缓存有效且非强制刷新 → 直接使用
        if (memCacheValid && !force) {
            Log.d(TAG, "内存缓存命中，跳过网络请求")
            _uiState.update { it.copy(isLoading = false, leaderboardData = memCache!!, error = null) }
            return
        }

        // 2. 有内存缓存 → 先展示，后台刷新（force 时显示 loading 让用户感知刷新）
        if (memCache != null) {
            if (force) {
                Log.d(TAG, "强制刷新：显示 loading，发起网络请求")
                _uiState.update { it.copy(isLoading = true, leaderboardData = memCache, error = null) }
                fetchFromNetwork(silent = false, preloadedData = memCache)
            } else {
                Log.d(TAG, "内存缓存过期，先展示后静默刷新")
                _uiState.update { it.copy(isLoading = false, leaderboardData = memCache, error = null) }
                fetchFromNetwork(silent = true)
            }
            return
        }

        // 3. 无内存缓存 → 尝试读取本地缓存
        viewModelScope.launch {
            val localCache = try {
                settings.getLeaderboardCache()
            } catch (e: Exception) {
                Log.w(TAG, "读取本地缓存失败: ${e.message}")
                null
            }

            if (localCache != null) {
                val (jsonStr, ts) = localCache
                val cachedData = repository.decodeCacheJson(jsonStr)
                if (cachedData != null) {
                    // 先展示本地缓存数据
                    _uiState.update { it.copy(isLoading = false, leaderboardData = cachedData, error = null) }
                    // 本地缓存未过期且非强制 → 不刷新；否则后台刷新
                    val localCacheValid = (now - ts) < LOCAL_CACHE_TTL_MS
                    if (localCacheValid && !force) {
                        Log.d(TAG, "本地缓存命中且未过期，跳过网络请求")
                        memoryCache = cachedData
                        memoryCacheTimestamp = ts
                    } else {
                        Log.d(TAG, "本地缓存已过期或强制刷新，后台刷新")
                        fetchFromNetwork(silent = !force, preloadedData = cachedData)
                    }
                    return@launch
                }
            }

            // 4. 无任何缓存 → 显示加载状态，发起网络请求
            _uiState.update { it.copy(isLoading = true, error = null) }
            fetchFromNetwork(silent = false)
        }
    }

    /**
     * 从网络拉取排行榜数据。
     *
     * @param silent 是否静默刷新（不显示 loading，失败时保留已有数据不显示错误）
     * @param preloadedData 预加载的数据（缓存），失败时保留
     */
    private fun fetchFromNetwork(
        silent: Boolean,
        preloadedData: LeaderboardData? = null
    ) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            val result = repository.fetchLeaderboard()
            result.onSuccess { data ->
                // 更新内存缓存
                memoryCache = data
                memoryCacheTimestamp = System.currentTimeMillis()
                // 持久化到本地缓存
                try {
                    settings.setLeaderboardCache(repository.encodeCacheJson(data))
                } catch (e: Exception) {
                    Log.w(TAG, "写入本地缓存失败: ${e.message}")
                }
                _uiState.update { it.copy(isLoading = false, leaderboardData = data, error = null) }
            }.onFailure { e ->
                Log.w(TAG, "网络刷新失败: ${e.message}")
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        // 有预加载数据时保留数据不显示错误（静默刷新或强制刷新失败），
                        // 无预加载数据时显示错误让用户重试
                        error = if (preloadedData != null) null
                        else e.message ?: "加载失败"
                    )
                }
            }
        }
    }
}
