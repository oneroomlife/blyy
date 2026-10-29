package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.util.unity.AlLive2dExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个资源包的导入记录。
 *
 * 同一个「文件路径」只会有一条记录；大小/修改时间变化或提取管线升级后
 * 由扫描逻辑判为「有更新」，重新导入即覆盖。
 */
@Serializable
data class GameImportRecord(
    /** 资源包在设备上的路径（游戏目录绝对路径 / SAF uri 字符串） */
    val path: String,
    /** [GameAssetKind.key] */
    val kind: String = GameAssetKind.LIVE2D.key,
    val size: Long = 0L,
    val modified: Long = 0L,
    /** 导入后的资源 id（= 库内目录名） */
    val assetId: String = "",
    /** imported / failed */
    val status: String = STATUS_IMPORTED,
    val error: String? = null,
    val importedAt: Long = 0L,
    /** 提取管线版本（低于当前版本说明产物过期） */
    val pipelineVersion: Int = 0,
    val textureCount: Int = 0,
    val pageCount: Int = 0,
    /** Live2D 生成的动作数 */
    val motionCount: Int = 0,
    val textureSize: String? = null
) {
    val isImported: Boolean get() = status == STATUS_IMPORTED
    val assetKind: GameAssetKind get() = GameAssetKind.fromKey(kind) ?: GameAssetKind.LIVE2D

    companion object {
        const val STATUS_IMPORTED = "imported"
        const val STATUS_FAILED = "failed"
    }
}

/** 资源包索引导航结构 */
@Serializable
data class GameImportIndexData(
    val version: Int = CURRENT_VERSION,
    /** 各类资源上次扫描使用的游戏目录（kind key → 绝对路径） */
    val roots: Map<String, String> = emptyMap(),
    val entries: List<GameImportRecord> = emptyList(),
    val lastScanAt: Long = 0L
) {
    companion object {
        /** 1 = 四类资源统一索引（从旧 live2d_bundle_index.json 迁移而来） */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 游戏资源包索引（持久化到 `filesDir/game_import_index.json`）。
 *
 * 目的与旧 Live2D 专用索引一致：Shizuku 扫描出的每个资源包与提取结果一一对应，
 * 下次启动只读索引即可展示状态、免重复解包；只有新增 / 变化的包才处理。
 *
 * 与旧版的差异：
 * - 记录**资源类型**，四类资源共用一份索引；
 * - `pipelineVersion` 按类型比较（Spine 与 Live2D 的产物结构互不影响）；
 * - 首次读取时自动迁移 `live2d_bundle_index.json`，老用户不会掉「已导入」状态。
 */
@Singleton
class GameImportIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GameImportIndex"
        private const val FILE_NAME = "game_import_index.json"
        private const val LEGACY_FILE_NAME = "live2d_bundle_index.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    @Volatile
    private var cached: GameImportIndexData? = null

    private fun indexFile(): File = File(context.filesDir, FILE_NAME)
    private fun legacyFile(): File = File(context.filesDir, LEGACY_FILE_NAME)

    suspend fun load(): GameImportIndexData = mutex.withLock {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) { readOrMigrate() }
        cached = loaded
        loaded
    }

    suspend fun save(data: GameImportIndexData) {
        mutex.withLock {
            cached = data
            withContext(Dispatchers.IO) { write(data) }
        }
    }

    suspend fun roots(): Map<String, String> = load().roots

    suspend fun rememberRoot(kind: GameAssetKind, path: String) {
        val index = load()
        val roots = index.roots.toMutableMap().apply { put(kind.key, path) }
        if (roots == index.roots) return
        save(index.copy(roots = roots))
    }

    suspend fun entryFor(path: String): GameImportRecord? =
        load().entries.firstOrNull { it.path == path }

    suspend fun upsert(record: GameImportRecord) {
        val index = load()
        val entries = index.entries.filterNot { it.path == record.path } + record
        save(index.copy(entries = entries, lastScanAt = System.currentTimeMillis()))
    }

    /** 批量写入，并清掉扫描结果中已不存在的路径 */
    suspend fun replaceAll(records: List<GameImportRecord>, keepPaths: Set<String>) {
        val index = load()
        val merged = index.entries.filter { it.path in keepPaths }
            .associateBy { it.path }
            .toMutableMap()
        records.forEach { merged[it.path] = it }
        save(index.copy(entries = merged.values.toList(), lastScanAt = System.currentTimeMillis()))
    }

    suspend fun remove(path: String) {
        val index = load()
        if (index.entries.none { it.path == path }) return
        save(index.copy(entries = index.entries.filterNot { it.path == path }))
    }

    /** 资源目录改名后同步记录里的 assetId，避免下次扫描把它当成「待导入」 */
    suspend fun renameAsset(kind: GameAssetKind, oldId: String, newId: String) {
        val index = load()
        var changed = false
        val entries = index.entries.map { entry ->
            if (entry.kind == kind.key && entry.assetId == oldId) {
                changed = true
                entry.copy(assetId = newId)
            } else {
                entry
            }
        }
        if (changed) save(index.copy(entries = entries))
    }

    /** 资源目录被删除/改名后，清掉指向它的记录（避免索引残留「已导入」幽灵条目） */
    suspend fun removeByAsset(kind: GameAssetKind, assetId: String) {
        val index = load()
        val remaining = index.entries.filterNot { it.kind == kind.key && it.assetId == assetId }
        if (remaining.size == index.entries.size) return
        save(index.copy(entries = remaining))
    }

    /** 清空条目（保留根目录记忆） */
    suspend fun clearEntries() {
        val index = load()
        save(index.copy(entries = emptyList(), lastScanAt = 0L))
    }

    suspend fun importedEntries(): List<GameImportRecord> = load().entries.filter { it.isImported }

    // ------------------------------------------------------------------ 内部

    private fun readOrMigrate(): GameImportIndexData {
        val file = indexFile()
        if (file.isFile) {
            val parsed = runCatching { json.decodeFromString<GameImportIndexData>(file.readText()) }
                .onFailure { Log.w(TAG, "索引损坏，按空索引重建：${it.message}") }
                .getOrNull()
            if (parsed != null) {
                return if (parsed.version == GameImportIndexData.CURRENT_VERSION) {
                    parsed
                } else {
                    // 结构升级：条目保留（字段向后兼容），版本号刷新
                    parsed.copy(version = GameImportIndexData.CURRENT_VERSION)
                }
            }
        }
        return migrateLegacy()
    }

    /**
     * 旧 `live2d_bundle_index.json` → 统一索引。
     *
     * 旧记录没有类型字段，全部按 Live2D 处理；只有提取管线版本与当前一致的
     * 才标记为已导入，其余等待重新解包（避免沿用可能损坏的旧产物）。
     */
    private fun migrateLegacy(): GameImportIndexData {
        val legacy = legacyFile()
        if (!legacy.isFile) return GameImportIndexData()
        val migrated = runCatching {
            val root = json.parseToJsonElement(legacy.readText()).jsonObject
            val entries = root["entries"]?.jsonArray?.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? =
                    (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                fun num(key: String): Long? = str(key)?.toLongOrNull()

                val path = str("path") ?: return@mapNotNull null
                val assetId = str("modelId") ?: return@mapNotNull null
                val status = str("status") ?: GameImportRecord.STATUS_FAILED
                val oldPipeline = num("extractorVersion")?.toInt() ?: 0
                GameImportRecord(
                    path = path,
                    kind = GameAssetKind.LIVE2D.key,
                    size = num("size") ?: 0L,
                    modified = num("modified") ?: 0L,
                    assetId = assetId,
                    status = if (status == GameImportRecord.STATUS_IMPORTED) {
                        GameImportRecord.STATUS_IMPORTED
                    } else {
                        GameImportRecord.STATUS_FAILED
                    },
                    error = str("error"),
                    importedAt = num("importedAt") ?: 0L,
                    pipelineVersion = if (oldPipeline == AlLive2dExtractor.EXTRACTOR_VERSION) {
                        GamePipelineVersions.LIVE2D
                    } else {
                        0
                    },
                    textureCount = num("textureCount")?.toInt() ?: 0,
                    motionCount = num("motionCount")?.toInt() ?: 0,
                    textureSize = str("textureSize")
                )
            }.orEmpty()
            val legacyRoots = root["roots"]?.jsonArray?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }.orEmpty()
            val roots = legacyRoots.firstOrNull()?.let { mapOf(GameAssetKind.LIVE2D.key to it) }
                ?: emptyMap()
            GameImportIndexData(
                version = GameImportIndexData.CURRENT_VERSION,
                roots = roots,
                entries = entries,
                lastScanAt = 0L
            )
        }.onFailure { Log.w(TAG, "迁移旧索引失败：${it.message}") }
            .getOrElse { GameImportIndexData() }

        if (migrated.entries.isNotEmpty()) {
            runCatching { write(migrated) }
            Log.i(TAG, "已迁移旧 Live2D 索引：${migrated.entries.size} 条记录")
        }
        return migrated
    }

    private fun write(data: GameImportIndexData) {
        runCatching {
            val file = indexFile()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(GameImportIndexData.serializer(), data))
            // Android(Linux) 上 rename 会原子替换已存在的目标文件；
            // 先 delete 再 rename 会在两步之间留下「整份索引消失」的崩溃窗口
            if (!tmp.renameTo(file)) {
                // 极少数文件系统不支持覆盖 rename 时退化为读写
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "写入索引失败：${it.message}") }
    }
}
