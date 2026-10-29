package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.util.unity.AlLive2dExtractor
import com.azurlane.blyy.util.unity.UnityBundleSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 * - 失败详情写入索引，列表里能直接看到原因，而不是静默消失；
 * - 协程取消**不算**失败：只清理暂存，不写 FAILED 记录、不改变旧产物。
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
     * @param paintingCompanions 静态立绘的附属包（`painting/<名字>` prefab、`_rw` / `_front` 图层）：
     *   有 prefab 才能按游戏内的图层布局合成完整立绘，缺失时退化为单层网格还原
     */
    suspend fun importLocal(
        bundleFile: File,
        sourcePath: String,
        fileNameHint: String? = null,
        kindHint: GameAssetKind? = null,
        sizeHint: Long = 0L,
        modifiedHint: Long = 0L,
        paintingCompanions: List<File> = emptyList(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        // 解压镜像（与包等大的 .img）只服务于本次导入内「探测 → 提取」的复用，
        // 结束后必须删除，否则每个包都会在 cacheDir 里永久留下几十 MB 的残留
        try {
            doImport(
                bundleFile = bundleFile,
                sourcePath = sourcePath,
                fileNameHint = fileNameHint,
                kindHint = kindHint,
                sizeHint = sizeHint,
                modifiedHint = modifiedHint,
                paintingCompanions = paintingCompanions,
                onProgress = onProgress
            )
        } finally {
            runCatching { UnityBundleSession.imageCacheFile(context, bundleFile).delete() }
        }
    }

    private suspend fun doImport(
        bundleFile: File,
        sourcePath: String,
        fileNameHint: String?,
        kindHint: GameAssetKind?,
        sizeHint: Long,
        modifiedHint: Long,
        paintingCompanions: List<File>,
        onProgress: (Float, String) -> Unit
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

                // 立绘与过场图都是「图片型」资源：同一个提取器，
                // 过场图包里没有网格/prefab，会自动落到 RAW 模式（取最大贴图）
                GameAssetKind.PAINTING, GameAssetKind.LOADING_BG -> {
                    val paintingReport = paintingExtractor.extract(
                        bundleFile = bundleFile,
                        outputDir = staging,
                        id = assetId,
                        companions = if (kind == GameAssetKind.PAINTING) paintingCompanions else emptyList(),
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

            // 原子落库：暂存目录 rename 到目标目录（跨分区时退化为复制）。
            // 旧资产先挪到暂存区备份而不是直接删除：落库中途失败（复制半途抛 IO 异常、
            // 协程取消等）时把备份放回原位，用户已导入的资源不会凭空消失
            val target = library.dirOf(kind, assetId)
            val backup = File(library.stagingDir(kind), "$assetId.replaced")
            if (target.exists()) {
                runCatching { backup.deleteRecursively() }
                if (!target.renameTo(backup)) target.deleteRecursively()
            }
            try {
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
            } catch (t: Throwable) {
                runCatching { target.deleteRecursively() }
                if (backup.isDirectory) runCatching { backup.renameTo(target) }
                throw t
            }
            runCatching { backup.deleteRecursively() }

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
            // 秘书舰的 SD 资源库直接读这个目录：导入成功后立刻让它看到新小人
            if (kind == GameAssetKind.SD_CHAR) {
                runCatching { com.azurlane.blyy.util.SDResourceManager.onGameImportChanged() }
            }
            Log.i(TAG, "导入完成：${kind.key}/$assetId（${System.currentTimeMillis() - started}ms）")
            onProgress(1f, "完成")
            Result(kind = kind, assetId = assetId, probe = probe, elapsedMs = System.currentTimeMillis() - started)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 取消不是导入失败：只清理半成品，绝不写 FAILED 记录——
            // 否则用户退出页面后，剩余没导完的条目会被永久标记成「失败」
            runCatching { staging.deleteRecursively() }
            throw c
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
        is java.util.zip.ZipException -> "压缩格式异常"
        else -> t.message?.substringAfterLast('/')?.take(180)?.takeIf { it.isNotBlank() }
            ?: t.javaClass.simpleName
    }
}
