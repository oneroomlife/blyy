package com.azurlane.blyy.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live2D 皮肤库核心：根目录管理、递归扫描、model3.json 解析、缩略图与删除/重命名。
 *
 * 存储约定：
 * - 模型根目录 = <app 外部私有目录>/live2d/<模型id>/...（免权限，adb 可直接推送，
 *   与 SD 资源库的 blhx_sd 目录策略一致）
 * - 缩略图目录 = <根目录>/.thumbs/<id>.jpg（查看器首次加载后由 WebView 截图回传生成）
 *
 * 仅支持 Cubism 3/4（*.model3.json + *.moc3）；Cubism 2 不在支持范围。
 */
@Singleton
class Live2dLibrary @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Live2dLibrary"
        private const val ROOT_DIR_NAME = "live2d"
        private const val THUMBS_DIR_NAME = ".thumbs"
        private const val MODEL3_EXT = ".model3.json"
        private const val MAX_SCAN_DEPTH = 4

    /** id 尾部皮肤序号后缀（如 "2" / "12"，输入为 substringAfterLast('_') 的结果） */
    private val BARE_SKIN_INDEX_REGEX = Regex("""^[0-9]{1,2}$""")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** assets/blhx_avatar 文件名缓存（懒加载，999 个条目，仅字符串名） */
    @Volatile
    private var avatarNames: Set<String>? = null

    fun rootDir(): File {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        return File(base, ROOT_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun thumbsDir(): File = File(rootDir(), THUMBS_DIR_NAME).apply { if (!exists()) mkdirs() }

    fun thumbFileFor(id: String): File = File(thumbsDir(), "$id.jpg")

    /** 模型 id 是否合法（防路径穿越：只允许一层目录名） */
    fun isValidId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..") &&
            id != THUMBS_DIR_NAME

    /**
     * 全量扫描模型库：遍历根目录一级文件夹，递归寻找 model3.json 并解析元信息。
     * 返回按显示 id 排序的有效模型；解析失败目录以 [Live2dInvalidDir] 形式回传。
     */
    suspend fun scan(): Pair<List<Live2dModelInfo>, List<Live2dInvalidDir>> =
        withContext(Dispatchers.IO) {
            val root = rootDir()
            val models = mutableListOf<Live2dModelInfo>()
            val invalid = mutableListOf<Live2dInvalidDir>()

            val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
            for (dir in dirs) {
                if (dir.name == THUMBS_DIR_NAME) continue
                try {
                    val model3 = findModel3(dir)
                    if (model3 == null) {
                        // 无 model3.json：空目录顺手清理；有杂文件的目录归入"无法识别"提示
                        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) {
                            dir.delete()
                        } else if (hasAnyFile(dir)) {
                            invalid += Live2dInvalidDir(dir.name, "未找到 *.model3.json")
                        }
                    } else {
                        val relName = model3.relativeTo(dir).invariantSeparatorsPath
                        val info = parseModel3(dir, relName)
                        if (info != null) {
                            models += info
                        } else {
                            invalid += Live2dInvalidDir(dir.name, "model3.json 损坏或缺少 .moc3 文件")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "扫描模型目录失败: ${dir.name}", e)
                    invalid += Live2dInvalidDir(dir.name, "扫描失败: ${e.message ?: "未知错误"}")
                }
            }
            models.sortBy { it.id }
            models to invalid
        }

    /** 按 id 读取单个模型（查看器用）；不存在或损坏返回 null */
    suspend fun findById(id: String): Live2dModelInfo? = withContext(Dispatchers.IO) {
        if (!isValidId(id)) return@withContext null
        val dir = File(rootDir(), id)
        if (!dir.isDirectory) return@withContext null
        val model3 = findModel3(dir) ?: return@withContext null
        parseModel3(dir, model3.relativeTo(dir).invariantSeparatorsPath)
    }

    /** 删除模型目录与缩略图 */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidId(id)) return@withContext false
        val dir = File(rootDir(), id)
        val ok = dir.deleteRecursively()
        thumbFileFor(id).delete()
        ok
    }

    /**
     * 重命名模型：直接重命名目录（model3.json 内部引用全部为目录内相对路径，安全）。
     * 返回 null 表示成功，否则为失败原因。
     */
    suspend fun rename(id: String, newName: String): String? = withContext(Dispatchers.IO) {
        val sanitized = newName.trim()
        when {
            !isValidId(id) -> "无效的模型 ID"
            sanitized.isEmpty() -> "名称不能为空"
            !isValidId(sanitized) -> "名称不能包含路径分隔符"
            sanitized == id -> null
            File(rootDir(), sanitized).exists() -> "已存在同名模型"
            else -> {
                val dir = File(rootDir(), id)
                if (!dir.isDirectory) return@withContext "模型不存在"
                val ok = runCatching { dir.renameTo(File(rootDir(), sanitized)) }
                    .getOrElse { false }
                if (ok) {
                    thumbFileFor(id).delete()
                    null
                } else {
                    "重命名失败（文件可能被占用）"
                }
            }
        }
    }

    /** 保存查看器回传的缩略图（JPEG 字节） */
    suspend fun saveThumb(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!isValidId(id)) return@withContext
        runCatching {
            thumbFileFor(id).writeBytes(bytes)
        }.onFailure { Log.w(TAG, "保存缩略图失败: $id", it) }
    }

    // ---------- 内部实现 ----------

    /** 深度优先寻找 model3.json（浅层优先，同一目录含多个时取最浅第一个） */
    private fun findModel3(dir: File): File? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(dir to 0)
        var fallback: File? = null
        while (queue.isNotEmpty()) {
            val (cur, depth) = queue.removeFirst()
            val children = cur.listFiles() ?: continue
            for (child in children) {
                if (child.isFile && child.name.endsWith(MODEL3_EXT, ignoreCase = true)) {
                    // 优先与目录同名的 model3.json（官方rip命名习惯）
                    if (child.nameWithoutExtension == dir.name) return child
                    fallback = fallback ?: child
                }
            }
            if (depth < MAX_SCAN_DEPTH) {
                for (child in children) {
                    if (child.isDirectory) queue.addLast(child to depth + 1)
                }
            }
        }
        return fallback
    }

    private fun hasAnyFile(dir: File): Boolean {
        val queue = ArrayDeque<File>()
        queue.addLast(dir)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isFile) return true
                if (c.isDirectory) queue.addLast(c)
            }
        }
        return false
    }

    /** 解析 model3.json 并统计目录信息；缺 moc3 或解析失败返回 null */
    private fun parseModel3(dir: File, model3Rel: String): Live2dModelInfo? {
        val file = File(dir, model3Rel)
        if (!file.isFile) return null
        return try {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val version = root["Version"]?.jsonPrimitive?.content
            val fr = root["FileReferences"]?.jsonObject ?: return null
            val moc = fr["Moc"]?.jsonPrimitive?.content
            if (moc.isNullOrBlank() || !File(dir, moc).isFile) return null

            val textures = fr["Textures"]?.jsonArray?.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            } ?: emptyList()
            // 贴图必须至少一张真实存在，否则渲染必然失败 → 视为无效模型
            if (textures.isEmpty() || textures.none { File(dir, it).isFile }) return null
            val physics = fr["Physics"]?.jsonPrimitive?.content
            val pose = fr["Pose"]?.jsonPrimitive?.content

            val motionGroups = fr["Motions"]?.jsonObject?.map { (group, arr) ->
                val motions = arr.jsonArray.mapNotNull { m ->
                    runCatching {
                        val obj = m.jsonObject
                        Live2dMotion(
                            file = obj["File"]!!.jsonPrimitive.content,
                            sound = obj["Sound"]?.jsonPrimitive?.content
                        )
                    }.getOrNull()
                }
                group to motions
            }?.sortedBy { it.first } ?: emptyList()

            val expressions = fr["Expressions"]?.jsonArray?.mapNotNull { e ->
                runCatching {
                    val obj = e.jsonObject
                    Live2dExpression(
                        name = obj["Name"]?.jsonPrimitive?.content
                            ?: File(obj["File"]!!.jsonPrimitive.content).nameWithoutExtension,
                        file = obj["File"]!!.jsonPrimitive.content
                    )
                }.getOrNull()
            } ?: emptyList()

            // 目录统计（文件数 + 总大小）
            var fileCount = 0
            var sizeBytes = 0L
            File(dir, "").walkTopDown().forEach { f ->
                if (f.isFile) {
                    fileCount++
                    sizeBytes += f.length()
                }
            }

            Live2dModelInfo(
                id = dir.name,
                dir = dir,
                model3File = model3Rel,
                version = version,
                mocFile = moc,
                textures = textures,
                physicsFile = physics?.takeIf { File(dir, it).isFile },
                poseFile = pose?.takeIf { File(dir, it).isFile },
                motionGroups = motionGroups,
                expressions = expressions,
                thumbFile = thumbFileFor(dir.name).takeIf { it.isFile },
                avatarAsset = resolveAvatarAsset(dir.name),
                sizeBytes = sizeBytes,
                fileCount = fileCount
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 model3.json 失败: ${file.path}", e)
            null
        }
    }

    /**
     * 模型 id → 内置舰娘头像资产兜底。
     * 模型目录普遍沿用拼音命名（如 aierdeliqi_4），去掉 `_N` 皮肤序号后缀，
     * 与 assets/blhx_avatar 的无声调全拼文件名匹配（aierdeliqi.webp）。
     */
    private fun resolveAvatarAsset(id: String): String? {
        val lastIndex = id.lastIndexOf('_')
        val possibleIndex = if (lastIndex >= 0) id.substring(lastIndex + 1) else ""
        val base = if (BARE_SKIN_INDEX_REGEX.matches(possibleIndex)) {
            id.substring(0, lastIndex)
        } else {
            id
        }.lowercase()
        if (base.isEmpty()) return null
        val names = avatarNames ?: synchronized(this) {
            avatarNames ?: run {
                val set = runCatching {
                    context.assets.list("blhx_avatar")?.toSet() ?: emptySet()
                }.getOrDefault(emptySet())
                avatarNames = set
                set
            }
        }
        val hit = setOfNotNull("$base.webp", "$base.png", "$base.jpg").firstOrNull { it in names }
        return hit
    }
}
