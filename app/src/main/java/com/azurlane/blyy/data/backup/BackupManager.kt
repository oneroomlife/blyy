package com.azurlane.blyy.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.azurlane.blyy.BuildConfig
import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.widget.WidgetSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份 / 恢复的核心实现。
 *
 * 覆盖范围（对应 docs/FEATURE_EXPANSION_2026-11.md P0-5「换机即清零」）：
 * - 誓约/收藏（`ships.isFavorite`）
 * - 答题成绩（`guess_history` 全量）
 * - 全部非敏感偏好（外观/播放/秘书舰/Live2D/组件/AI 人格与记忆…）
 *
 * **不包含**：API Key、会话快照、啾信预设、游戏 UID/服务器、可重建缓存 —— 规则集中在
 * [PlayerSettingsDataStore.readBackupPrefs] 的排除名单里，导出与恢复两侧都执行。
 *
 * 三条可靠性约定：
 * 1. 读取备份时**先完整解析并校验**，任何字段非法都整包拒绝，绝不"恢复一半"；
 * 2. 恢复顺序为 设置 → 誓约 → 成绩，每步幂等（成绩按 sessionId 去重、誓约只置 true）；
 * 3. 舰娘名录尚未建立时（新装/离线），誓约进入 `pending_oath_restore`，
 *    下次名录刷新由 `ShipRepository` 自动补写，不会静默丢失。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shipDao: ShipDao,
    private val guessHistoryDao: GuessHistoryDao,
    private val settings: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "BackupManager"
        private const val LOCAL_DIR = "backups"
        private const val KEEP_LOCAL_FILES = 5
        private const val AUTO_BACKUP_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** SAF 导出用的 MIME；备份是纯 JSON，便于用户自己打开检查 */
        const val MIME_TYPE = "application/json"

        // 校验上限统一由 BackupValidator 持有（可单测）；这里只保留文件级限制
        private const val MAX_FILE_BYTES = BackupValidator.MAX_FILE_BYTES
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // ─────────────────────────── 导出 ───────────────────────────

    /** 组装当前数据的备份载荷（纯内存，不落盘） */
    suspend fun createPayload(): BackupPayload = withContext(Dispatchers.IO) {
        val ships = shipDao.getAllShips().first()
            .asSequence()
            .filter { it.isFavorite }
            .map { ShipStateBackup(name = it.name, archiveType = it.archiveType, favorite = true) }
            .toList()

        val scores = guessHistoryDao.getAllHistory().first().map { it.toBackup() }

        BackupPayload(
            schema = BackupPayload.CURRENT_SCHEMA,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            createdAt = System.currentTimeMillis(),
            shipStates = ships,
            scores = scores,
            preferences = settings.readBackupPrefs()
        )
    }

    /** 导出到用户选择的位置（SAF）。返回写入字节数 */
    suspend fun exportTo(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = createPayload()
            val bytes = json.encodeToString(BackupPayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
            // 用 "w" 而不是 "wt"：部分第三方 DocumentsProvider（云盘）不认 "wt"，
            // CREATE_DOCUMENT 拿到的一定是新文件，不需要显式截断。
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: error("无法打开目标文件")
            stream.use { it.write(bytes) }
            settings.setLastBackupAt(payload.createdAt)
            Log.i(
                TAG,
                "exportTo OK: ships=${payload.shipStates.size} scores=${payload.scores.size} " +
                    "prefs=${payload.preferences.size} bytes=${bytes.size}"
            )
            bytes.size.toLong()
        }.onFailure { Log.e(TAG, "exportTo failed", it) }
    }

    /** 导出到应用私有目录（自动备份 / 手动"存到本机"） */
    suspend fun exportToLocalFile(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = createPayload()
            val dir = localDir().apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(payload.createdAt))
            val file = File(dir, "blyy-backup-$stamp.json")
            file.writeText(json.encodeToString(BackupPayload.serializer(), payload))
            pruneLocalFiles()
            settings.setLastBackupAt(payload.createdAt)
            Log.i(TAG, "exportToLocalFile OK: ${file.name} (${file.length()}B)")
            file
        }.onFailure { Log.e(TAG, "exportToLocalFile failed", it) }
    }

    /**
     * 到点自动备份（默认开启，24h 一次）。
     *
     * 触发点放在进程冷启动：项目没有 WorkManager 依赖，而备份本身只需一次 IO，
     * 用「启动 + 24h 闸门」比为了一个周期任务引入整套调度框架更划算。
     */
    suspend fun autoBackupIfDue(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!settings.autoBackupEnabled.first()) return@runCatching false
            val last = settings.lastBackupAt.first()
            if (last > 0 && System.currentTimeMillis() - last < AUTO_BACKUP_INTERVAL_MS) {
                return@runCatching false
            }
            exportToLocalFile().isSuccess
        }.getOrDefault(false)
    }

    // ─────────────────────────── 读取与校验 ───────────────────────────

    /** 读取并解析备份文件（用户选择的 SAF 文档） */
    suspend fun readFromUri(uri: Uri): Result<BackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            // 先看大小再读：直接 readBytes() 一个被构造的 GB 级文件会先 OOM 再校验，等于没校验
            val knownSize = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
            if (knownSize > MAX_FILE_BYTES) {
                error("备份文件过大（${knownSize / 1024 / 1024} MB），已拒绝解析")
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                // 未知大小时用带上限的读法，避免 readBytes() 无界分配
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_FILE_BYTES) error("备份文件超过 32 MB，已拒绝解析")
                }
                buffer.toByteArray()
            } ?: error("无法读取所选文件")

            val text = bytes.toString(Charsets.UTF_8)
            BackupValidator.parse(json, text)
        }.onFailure { Log.w(TAG, "readFromUri failed: ${it.message}", it) }
    }

    /** 读取并解析本地备份文件 */
    suspend fun readFromFile(file: File): Result<BackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) error("备份文件不存在")
            if (file.length() > MAX_FILE_BYTES) error("备份文件过大（${file.length() / 1024 / 1024} MB）")
            BackupValidator.parse(json, file.readText())
        }.onFailure { Log.w(TAG, "readFromFile failed: ${it.message}", it) }
    }

    // ─────────────────────────── 恢复 ───────────────────────────

    /**
     * 应用备份内容。
     *
     * 顺序有意为之：先设置（决定 UI/播放等即时可见项），再誓约，最后成绩。
     * 每一步都独立计数，便于 UI 展示"恢复了什么、跳过了什么"。
     */
    suspend fun restore(payload: BackupPayload): Result<RestoreResult> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsApplied = settings.writeBackupPrefs(payload.preferences)

            var oathApplied = 0
            val pending = mutableListOf<ShipStateBackup>()
            payload.shipStates.forEach { state ->
                if (!state.favorite) return@forEach
                val exists = shipDao.getShipByName(state.name, state.archiveType) != null
                if (exists) {
                    shipDao.updateFavorite(state.name, state.archiveType, true)
                    oathApplied++
                } else {
                    pending += state
                }
            }
            if (pending.isNotEmpty()) {
                settings.setPendingOathRestore(json.encodeToString<List<ShipStateBackup>>(pending))
            }

            var scoresInserted = 0
            var scoresSkipped = 0
            payload.scores.forEach { score ->
                val sessionId = score.sessionId.ifBlank { syntheticSessionId(score) }
                if (guessHistoryDao.existsBySession(sessionId, score.mode)) {
                    scoresSkipped++
                } else {
                    guessHistoryDao.insert(
                        GuessHistory(
                            mode = score.mode,
                            difficulty = score.difficulty,
                            totalQuestions = score.totalQuestions,
                            correctAnswers = score.correctAnswers,
                            totalScore = score.totalScore,
                            hintsUsedTotal = score.hintsUsedTotal,
                            skippedQuestions = score.skippedQuestions,
                            totalPossibleScore = score.totalPossibleScore,
                            timestamp = score.timestamp,
                            accuracy = score.accuracy,
                            averageScore = score.averageScore,
                            sessionId = sessionId
                        )
                    )
                    scoresInserted++
                }
            }

            val result = RestoreResult(
                shipsRestored = oathApplied,
                shipsPending = pending.size,
                scoresRestored = scoresInserted,
                scoresSkipped = scoresSkipped,
                preferencesRestored = prefsApplied,
                backupCreatedAt = payload.createdAt,
                backupAppVersion = payload.appVersion
            )
            settings.setLastRestoreAt(System.currentTimeMillis())
            // 誓约/推荐池变了，桌面组件必须重绘，否则会继续显示恢复前的舰娘
            runCatching { WidgetSync.updateAll(context) }
                .onFailure { Log.w(TAG, "widget refresh after restore failed", it) }
            Log.i(TAG, "restore OK: $result")
            result
        }.onFailure { Log.e(TAG, "restore failed", it) }
    }

    /** 老备份可能没有 sessionId：用内容指纹生成稳定 id，保证重复恢复不会重复插入 */
    private fun syntheticSessionId(score: ScoreBackup): String =
        "restored-${score.mode}-${score.timestamp}-${score.totalScore}-${score.totalQuestions}"

    // ─────────────────────────── 本地备份文件 ───────────────────────────

    /** 本地备份列表（按时间倒序，含条目摘要） */
    suspend fun listLocalBackups(): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        val dir = localDir()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val payload = if (file.length() > MAX_FILE_BYTES) {
                    null // 异常大的文件不解析（列表仍展示，避免用户以为备份消失了）
                } else {
                    runCatching { BackupValidator.parse(json, file.readText()) }.getOrNull()
                }
                BackupFileInfo(
                    file = file,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    payload = payload
                )
            }
            .orEmpty()
    }

    suspend fun deleteLocalBackup(file: File): Boolean = withContext(Dispatchers.IO) {
        // 只允许删备份目录内的文件，避免被构造路径删除任意文件
        val dir = localDir().canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return@withContext false
        if (target.parentFile != dir) return@withContext false
        target.delete()
    }

    /** 分享本地备份文件（内部分享用；SAF 已能导出，这里给"存到网盘"留个快捷入口） */
    fun fileOf(info: BackupFileInfo): File = info.file

    private fun localDir(): File = File(context.filesDir, LOCAL_DIR)

    private fun pruneLocalFiles() {
        localDir().listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_LOCAL_FILES)
            ?.forEach { it.delete() }
    }

    // ─────────────────────────── 映射 ───────────────────────────

    private fun GuessHistory.toBackup() = ScoreBackup(
        sessionId = sessionId,
        mode = mode,
        difficulty = difficulty,
        totalQuestions = totalQuestions,
        correctAnswers = correctAnswers,
        totalScore = totalScore,
        hintsUsedTotal = hintsUsedTotal,
        skippedQuestions = skippedQuestions,
        totalPossibleScore = totalPossibleScore,
        timestamp = timestamp,
        accuracy = accuracy,
        averageScore = averageScore
    )
}

/** 本地备份文件信息（UI 列表项） */
data class BackupFileInfo(
    val file: File,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val payload: BackupPayload?
) {
    val createdAt: Long get() = payload?.createdAt ?: modifiedAt
    val summary: String get() = payload?.summary() ?: "文件损坏，无法解析"
    val appVersion: String get() = payload?.appVersion.orEmpty()
}

/** 恢复结果（UI 展示用） */
data class RestoreResult(
    val shipsRestored: Int,
    val shipsPending: Int,
    val scoresRestored: Int,
    val scoresSkipped: Int,
    val preferencesRestored: Int,
    val backupCreatedAt: Long,
    val backupAppVersion: String
) {
    fun summary(): String = buildString {
        append("誓约 $shipsRestored 个")
        if (shipsPending > 0) append("（$shipsPending 个待名录刷新后补写）")
        append(" · 成绩 +$scoresRestored")
        if (scoresSkipped > 0) append("（跳过重复 $scoresSkipped）")
        append(" · 设置 $preferencesRestored 项")
    }
}
