package com.azurlane.blyy.util

import android.content.Context
import android.util.Log

/**
 * 舰娘 SD 小人资源解析器。
 *
 * 将舰娘中文名映射到打包在 APK assets 中的 Spine 3.8.99 SD 小人动画资源，
 * 优先返回 SD 动画资源名（无扩展名），匹配不到时返回 null 由调用方回退到静态立绘。
 *
 * 资源约定：
 * - 资源位于 `app/src/main/assets/blhx_sd/<name>.{skel,atlas,png}`，三件套同名。
 * - 文件名是拼音（如 `boge` = 博格），与 biligame wiki 的舰娘中文名不一致，
 *   需要通过 [SHIP_NAME_TO_SD_ASSET] 显式映射。
 *
 * 当前为最小可跑 Demo：仅映射「博格」→ `boge`。
 * 后续扩展时只需：
 * 1. 在 [SHIP_NAME_TO_SD_ASSET] 中补充条目，或引入拼音转换库自动生成；
 * 2. 把对应 `<name>.skel/.atlas/.png` 三件套放入 `assets/blhx_sd/`。
 *
 * 性能策略：
 * - 首次调用时通过 `AssetManager.list("blhx_sd")` 构建可用资源索引，O(n) 一次性构建。
 * - 后续查询为 O(1)：先查映射表得到候选 assetName，再校验索引中是否真的存在该 .skel 文件。
 * - 索引在进程生命周期内缓存（资源随 APK 打包，运行期不变）。
 */
object LocalSdResolver {

    private const val TAG = "LocalSdResolver"
    private const val SD_DIR = "blhx_sd"
    private const val SKEL_EXT = ".skel"

    /**
     * 舰娘中文名 → SD 资源拼音名映射表。
     *
     * 注意：这里只列了 Demo 用的「博格」。完整映射表后续按需扩展，
     * 也可改为运行时按舰娘名做拼音转换 + 模糊匹配（参考 [LocalAvatarResolver] 的 buildVariants）。
     */
    private val SHIP_NAME_TO_SD_ASSET: Map<String, String> = mapOf(
        "博格" to "boge"
    )

    /** 进程级缓存：assets/blhx_sd/ 下所有 .skel 文件的主名（不含扩展名） */
    @Volatile
    private var availableSdAssets: Set<String>? = null

    private val lock = Any()

    /**
     * 解析舰娘 SD 资源名。
     *
     * @param context 任意 Context（内部使用 ApplicationContext 避免泄漏）
     * @param shipName 舰娘中文名（与 biligame wiki 图鉴列表一致）
     * @return SD 资源主名（如 "boge"），匹配不到返回 null
     */
    fun resolve(context: Context, shipName: String): String? {
        if (shipName.isBlank()) return null
        val candidate = SHIP_NAME_TO_SD_ASSET[shipName] ?: return null
        val index = ensureIndex(context)
        return if (candidate in index) candidate else {
            Log.w(TAG, "Mapped $shipName → $candidate but .skel not found in assets/$SD_DIR/")
            null
        }
    }

    /** 懒加载构建 assets/blhx_sd/ 下 .skel 文件主名索引（线程安全双重检查锁） */
    private fun ensureIndex(context: Context): Set<String> {
        availableSdAssets?.let { return it }
        synchronized(lock) {
            availableSdAssets?.let { return it }
            val index = try {
                val files = context.applicationContext.assets.list(SD_DIR) ?: emptyArray()
                files.filter { it.endsWith(SKEL_EXT) }
                    .map { it.removeSuffix(SKEL_EXT) }
                    .toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list SD assets", e)
                emptySet()
            }
            availableSdAssets = index
            Log.i(TAG, "SD asset index built: ${index.size} entries")
            return index
        }
    }
}
