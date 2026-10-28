package com.azurlane.blyy.util.gameimport

import android.content.Context
import com.azurlane.blyy.util.MediaDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏资源导出：把已导入的图片型资源（静态立绘 / 过场图）存到用户可见的 Downloads。
 *
 * 落盘约定（与语音、相册等其它下载保持一致，统一在 `Downloads/BLYY/` 下）：
 *
 * ```
 * Downloads/BLYY/Painting/<显示名>_<资源id>.png     静态立绘
 * Downloads/BLYY/LoadingBg/<显示名>_<资源id>.png    过场图
 * ```
 *
 * - 文件名同时带**可读名**（`2B · 须臾的休憩`）与**稳定 id**（`2b_2`），
 *   前者方便人找，后者保证同名不同皮肤不会重名；
 * - 非法字符（`/ \ : * ? " < > |`）统一替换成 `_`，避免落盘失败；
 * - API 29+ 走 MediaStore（无需存储权限），同名文件由系统自动追加 ` (1)`。
 */
@Singleton
class GameAssetExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** 导出某类资源的子目录（相对 `Downloads/`） */
        fun subPathOf(kind: GameAssetKind): String = when (kind) {
            GameAssetKind.PAINTING -> "${MediaDownloader.ROOT_DIR}/Painting"
            GameAssetKind.LOADING_BG -> "${MediaDownloader.ROOT_DIR}/LoadingBg"
            else -> "${MediaDownloader.ROOT_DIR}/GameAssets"
        }

        /** 文件名：`<显示名>_<id>.png`（显示名缺失时退回 id） */
        fun fileNameOf(asset: GameAssetInfo, displayName: String?): String {
            val id = MediaDownloader.sanitizeFileName(asset.id, "asset")
            val readable = MediaDownloader.sanitizeFileName(displayName.orEmpty(), id)
            val base = if (readable == id) id else "${readable}_$id"
            return "$base.png"
        }

        /** 该资源能否导出成图片（Spine/Live2D 不是单张图，走各自的分享路径） */
        fun canExport(asset: GameAssetInfo): Boolean = asset.isImage && asset.previewFile?.isFile == true
    }

    /**
     * 把资源图片写到 Downloads。
     *
     * @param displayName 查看器展示的名字（中文舰名 + 皮肤），用于可读文件名
     */
    suspend fun export(
        asset: GameAssetInfo,
        displayName: String?
    ): MediaDownloader.Result = withContext(Dispatchers.IO) {
        val image: File? = asset.previewFile
        if (image == null || !image.isFile) {
            return@withContext MediaDownloader.Result.Failure("图片文件缺失，无法导出")
        }
        val bytes = runCatching { image.readBytes() }.getOrElse { error ->
            return@withContext MediaDownloader.Result.Failure(error.message ?: "读取图片失败")
        }
        MediaDownloader.saveBytes(
            context = context,
            bytes = bytes,
            fileName = fileNameOf(asset, displayName),
            subPath = subPathOf(asset.kind)
        )
    }
}
