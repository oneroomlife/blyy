package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.azurlane.blyy.util.unity.AlLive2dExtractor
import com.azurlane.blyy.util.unity.UnityBundleSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏资源包导入调度器：**探测 → 分流提取 → 落库 → 写索引**。
 *
 * 四类资源共用同一条导入管道，差异只在提取器：
 * - Live2D → [AlLive2dExtractor]（产物结构保持原样，查看器/陪伴模式零改动）
 * - SD 小人 / 动态皮肤 → [SpineBundleExtractor]
 * - 静态立绘 → [PaintingBundleExtractor]
 *
 * 失败语义：
 * - 提取失败不留下半成品目录（暂存目录整体删除，目标目录保持原样）；
 * - 失败详情写入索引，列表里能直接看到原因，而不是静默消失。
 */
@Singleton
class GameBundleImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val live2dExtractor: AlLive2dExtractor,
    private val spineExtractor: SpineBundleExtractor,
    private val paintingExtractor: PaintingBundleExtractor,
    private val library: GameAssetLibrary,
    private val index: GameImportIndex
) {
    companion object {
        private const val TAG = "GameBundleImporter"
    }

    data class Result(
        val kind: GameAssetKind,
        val assetId: String,
        val probe: BundleProbeResult,
        val elapsedMs: Long
    )

    /**
     * 导入一个**本地**资源包文件。
     *
     * @param sourcePath 索引里的来源标识（游戏目录绝对路径 / SAF uri）；用于「同源更新」与 id 去重
     * @param fileNameHint 原始文件名（SAF 来源用），缺省取 [bundleFile] 名
     * @param kindHint 扫描目录推断的类型（仅作兜底，包内 `AssetBundle` 名优先）
     */
    suspend fun importLocal(
        bundleFile: File,
        sourcePath: String,
        fileNameHint: String? = null,
        kindHint: GameAssetKind? = null,
        sizeHint: Long = 0L,
        modifiedHint: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val displayName = fileNameHint ?: bundleFile.name

        val probe = runCatching {
            UnityBundleSession.open(context, bundleFile) { fraction, message ->
                onProgress(fraction * 0.32f, message)
            }.use { session -> BundleProbe.probe(session, kindHint) }
        }.getOrElse { error ->
            val reason = describe(error)
            index.upsert(
                GameImportRecord(
                    path = sourcePath,
                    kind = (kindHint ?: GameAssetKind.LIVE2D).key,
                    size = sizeHint,
                    modified = modifiedHint,
                    assetId = "",
                    status = GameImportRecord.STATUS_FAILED,
                    error = reason,
                    importedAt = 0L
                )
            )
            throw IOException(reason, error)
        }

        val kind = probe.kind ?: kindHint
        if (kind == null || !probe.importable) {
            val reason = probe.reason ?: "无法识别的资源包（既没有 moc3，也没有 skel 或可渲染贴图）"
            index.upsert(
                GameImportRecord(
                    path = sourcePath,
                    kind = (kindHint ?: kind ?: GameAssetKind.LIVE2D).key,
                    size = sizeHint,
                    modified = modifiedHint,
                    assetId = "",
                    status = GameImportRecord.STATUS_FAILED,
                    error = reason,
                    importedAt = 0L
                )
            )
            throw IOException(reason)
        }

        val assetId = allocateAssetId(
            base = GameAssetNaming.assetIdOf(displayName, kind),
            kind = kind,
            sourcePath = sourcePath
        )
        val staging = File(library.stagingDir(kind), assetId)
        staging.parentFile?.mkdirs()
        runCatching { staging.deleteRecursively() }

        try {
            onProgress(0.34f, "准备提取 ${GameAssetNaming.formatLabel(probe.format)}")
            val report = when (kind) {
                GameAssetKind.LIVE2D -> {
                    val modelReport = live2dExtractor.extract(
                        bundleFile = bundleFile,
                        outputDir = staging,
                        id = assetId,
                        onProgress = { fraction, message -> onProgress(0.34f + fraction * 0.6f, message) }
                    )
                    StageReport(
                        textureCount = modelReport.textures.size,
                        pageCount = modelReport.textures.size,
                        motionCount = modelReport.motionCount,
                        textureSize = modelReport.textureSize
                    )
                }

                GameAssetKind.PAINTING -> {
                    val paintingReport = paintingExtractor.extract(
                        bundleFile = bundleFile,
                        outputDir = staging,
                        id = assetId,
                        onProgress = { fraction, message -> onProgress(0.34f + fraction * 0.6f, message) }
                    )
                    StageReport(
                        textureCount = paintingReport.textureCount,
                        pageCount = 1,
                        motionCount = 0,
                        textureSize = "${paintingReport.width}x${paintingReport.height}"
                    )
                }

                GameAssetKind.SD_CHAR, GameAssetKind.DYNAMIC_SKIN -> {
                    val spineReport = spineExtractor.extract(
                        bundleFile = bundleFile,
                        outputDir = staging,
                        id = assetId,
                        kind = kind,
                        onProgress = { fraction, message -> onProgress(0.34f + fraction * 0.6f, message) }
                    )
                    StageReport(
                        textureCount = spineReport.pages.size,
                        pageCount = spineReport.pages.size,
                        motionCount = 0,
                        textureSize = spineReport.textureSize
                    )
                }
            }

            // 原子落库：暂存目录 rename 到目标目录（跨分区时退化为复制）
            val target = library.dirOf(kind, assetId)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }

            index.upsert(
                GameImportRecord(
                    path = sourcePath,
                    kind = kind.key,
                    size = if (sizeHint > 0) sizeHint else bundleFile.length(),
                    modified = if (modifiedHint > 0) modifiedHint else bundleFile.lastModified(),
                    assetId = assetId,
                    status = GameImportRecord.STATUS_IMPORTED,
                    importedAt = System.currentTimeMillis(),
                    pipelineVersion = GamePipelineVersions.of(kind),
                    textureCount = report.textureCount,
                    pageCount = report.pageCount,
                    motionCount = report.motionCount,
                    textureSize = report.textureSize
                )
            )
            Log.i(TAG, "导入完成：${kind.key}/$assetId（${System.currentTimeMillis() - started}ms）")
            onProgress(1f, "完成")
            Result(kind = kind, assetId = assetId, probe = probe, elapsedMs = System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            runCatching { staging.deleteRecursively() }
            val reason = describe(t)
            index.upsert(
                GameImportRecord(
                    path = sourcePath,
                    kind = kind.key,
                    size = sizeHint,
                    modified = modifiedHint,
                    assetId = assetId,
                    status = GameImportRecord.STATUS_FAILED,
                    error = reason,
                    importedAt = 0L
                )
            )
            throw if (t is IOException) t else IOException(reason, t)
        }
    }

    /**
     * SAF 单文件导入：先落地缓存（SAF 输入流只可顺序读一次），再走 [importLocal]。
     */
    suspend fun importUri(
        uri: Uri,
        kindHint: GameAssetKind? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "game_bundle"

        val cacheDir = File(context.cacheDir, "game_import").apply { mkdirs() }
        val local = File(cacheDir, "upload_${System.currentTimeMillis()}_${GameAssetNaming.sanitizeId(displayName)}")
        try {
            onProgress(0.02f, "读取所选文件")
            resolver.openInputStream(uri)?.use { input ->
                local.outputStream().buffered().use { output -> input.copyTo(output, 1 shl 20) }
            } ?: throw FileNotFoundException("无法打开所选文件：$displayName")

            importLocal(
                bundleFile = local,
                sourcePath = uri.toString(),
                fileNameHint = displayName,
                kindHint = kindHint,
                onProgress = { fraction, message -> onProgress(0.05f + fraction * 0.95f, message) }
            )
        } finally {
            runCatching { local.delete() }
        }
    }

    // ------------------------------------------------------------------ 内部

    private data class StageReport(
        val textureCount: Int,
        val pageCount: Int,
        val motionCount: Int,
        val textureSize: String?
    )

    /**
     * 资源 id 分配：
     * 1. 同一来源（path）重新导入 → 复用原 id（覆盖更新）；
     * 2. id 被**别的来源**占用 → 追加路径哈希后缀，避免互相覆盖。
     */
    private suspend fun allocateAssetId(base: String, kind: GameAssetKind, sourcePath: String): String {
        val existing = index.entryFor(sourcePath)
        if (existing != null && existing.assetId.isNotBlank() && existing.assetKind == kind) {
            return existing.assetId
        }
        val others = index.importedEntries().filter { it.kind == kind.key && it.path != sourcePath }
        if (others.none { it.assetId == base }) return base
        val hash = (sourcePath.hashCode() and 0xFFFF).toString(16).padStart(4, '0')
        var candidate = "${base}_$hash"
        var seq = 2
        while (others.any { it.assetId == candidate }) {
            candidate = "${base}_${hash}_$seq"
            seq++
        }
        return candidate
    }

    private fun describe(t: Throwable): String = when (t) {
        is FileNotFoundException -> "文件无法读取（可能已被移动或删除）"
        is java.util.zip.ZipException -> "压缩格式异常"
        else -> t.message?.substringAfterLast('/')?.take(180)?.takeIf { it.isNotBlank() }
            ?: t.javaClass.simpleName
    }
}
