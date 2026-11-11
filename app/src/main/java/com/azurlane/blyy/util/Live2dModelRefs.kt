package com.azurlane.blyy.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.Locale

/**
 * 模型目录内的文件索引：把 model3.json 里写的引用（`Moc` / `Textures` / `Motions`…）
 * 解析成**磁盘上真实存在的相对路径**。
 *
 * 为什么需要它：手动导入的模型普遍来自 Windows 上的二次打包，常见三类引用漂移
 * —— 大小写不一致（`Textures/texture_00.png` vs `textures/texture_00.png`）、
 * 分隔符是反斜杠、贴图被移到别的层级。Android 文件系统大小写敏感，
 * 这类模型在旧实现里会直接解析失败 → 归入"model3.json 损坏"而**整个不显示**，
 * 而从游戏资源包导入的模型引用是程序生成的，永远严丝合缝，于是呈现
 * 「只有游戏导入的资源能看见」。
 */
class Live2dFileIndex(private val dir: File, private val maxFiles: Int = 20000) {

    /** 小写相对路径 → 真实相对路径 */
    private val byPath = HashMap<String, String>()

    /** 小写文件名 → 真实相对路径（可能多份） */
    private val byName = HashMap<String, MutableList<String>>()

    init {
        build()
    }

    private fun build() {
        val rootPath = dir.absolutePath
        var count = 0
        val stream = dir.walkTopDown().onEnter { it == dir || count < maxFiles }
        for (file in stream) {
            if (!file.isFile) continue
            if (count >= maxFiles) break
            val rel = file.absolutePath
                .removePrefix(rootPath)
                .trimStart(File.separatorChar)
                .replace('\\', '/')
            if (rel.isEmpty()) continue
            count++
            byPath.putIfAbsent(rel.lowercase(Locale.ROOT), rel)
            byName.getOrPut(rel.substringAfterLast('/').lowercase(Locale.ROOT)) { mutableListOf() }.add(rel)
        }
    }

    val size: Int get() = byPath.size

    /**
     * 引用 → 真实相对路径（`/` 分隔）。
     *
     * 依次尝试：归一化后的精确路径 → 忽略大小写 → 忽略所在子目录（按文件名 + 末级目录名择优）。
     */
    fun resolve(ref: String?): String? {
        val normalized = normalizeRef(ref) ?: return null
        byPath[normalized.lowercase(Locale.ROOT)]?.let { return it }
        val candidates = byName[normalized.substringAfterLast('/').lowercase(Locale.ROOT)] ?: return null
        if (candidates.size == 1) return candidates[0]
        val wantDir = normalized.substringBeforeLast('/', "").substringAfterLast('/')
        return candidates.firstOrNull { candidate ->
            wantDir.isNotEmpty() && candidate.substringBeforeLast('/', "").substringAfterLast('/') == wantDir
        } ?: candidates.minByOrNull { candidate -> candidate.count { it == '/' } }
    }

    companion object {
        /** 引用归一化：反斜杠 → `/`，去前导 `/` 与 `./`；穿越路径返回 null */
        fun normalizeRef(ref: String?): String? {
            val raw = ref?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val unified = raw.replace('\\', '/').trimStart('/').removePrefix("./").trim()
            if (unified.isEmpty()) return null
            if (unified.split('/').any { it == ".." }) return null
            return unified
        }
    }
}

/**
 * model3.json 引用修正：把 [Live2dFileIndex] 解析出的真实路径写回 JSON 文本。
 *
 * 修正范围仅限 `FileReferences`（`Moc` / `Textures` / `Physics` / `Pose` /
 * `UserData` / `DisplayInfo` / `Motions[].File|Sound` / `Expressions[].File`），
 * 其余字段原样保留；未解析到的引用保持原文（可能是运行期才用的可选资源）。
 */
object Live2dModelRefs {

    /**
     * @param changedRefs 被改写的引用数量（0 表示引用本来就与磁盘一致）
     * @param missingCritical 关键引用（moc3 / 贴图）中查不到真实文件的那些
     * @param mocResolved / texturesResolved 关键引用是否可用（决定模型能否渲染）
     */
    data class RepairResult(
        val json: String,
        val changedRefs: Int,
        val missingCritical: List<String>,
        val mocResolved: String?,
        val texturesResolved: Int
    ) {
        val criticalOk: Boolean get() = mocResolved != null && texturesResolved > 0
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 解析失败（非法 JSON）返回 null；调用方应保留原文件不动 */
    fun repair(model3Text: String, index: Live2dFileIndex): RepairResult? {
        val root = runCatching { json.parseToJsonElement(model3Text) }.getOrNull() as? JsonObject ?: return null
        val fileRefs = root["FileReferences"] as? JsonObject ?: return null

        var changed = 0
        val missing = mutableListOf<String>()

        fun fix(element: JsonElement?, critical: Boolean): JsonElement {
            val primitive = (element as? JsonPrimitive)?.takeIf { it.isString } ?: return element ?: JsonNull
            val raw = primitive.content
            val resolved = index.resolve(raw)
            if (resolved == null) {
                if (critical) missing += raw
                // 至少把 Windows 反斜杠收敛成正斜杠，避免渲染端 404
                val unified = raw.replace('\\', '/')
                if (unified != raw) changed++
                return JsonPrimitive(unified)
            }
            if (resolved != raw) changed++
            return JsonPrimitive(resolved)
        }

        var mocResolved: String? = null
        var texturesResolved = 0

        val newFileRefs = buildJsonObject {
            for ((key, value) in fileRefs) {
                when (key) {
                    "Moc" -> {
                        mocResolved = index.resolve((value as? JsonPrimitive)?.contentOrNull())
                        put("Moc", fix(value, critical = true))
                    }
                    "Textures" -> {
                        val list = (value as? JsonArray)?.map { texture ->
                            val resolved = index.resolve((texture as? JsonPrimitive)?.contentOrNull())
                            if (resolved != null) texturesResolved++
                            fix(texture, critical = true)
                        } ?: emptyList()
                        put("Textures", JsonArray(list))
                    }
                    "Physics", "Pose", "UserData", "DisplayInfo" -> put(key, fix(value, critical = false))
                    "Motions" -> put("Motions", fixMotions(value as? JsonObject, ::fix))
                    "Expressions" -> put("Expressions", fixExpressions(value as? JsonArray, ::fix))
                    else -> put(key, value)
                }
            }
        }

        val newRoot = buildJsonObject {
            for ((key, value) in root) {
                if (key == "FileReferences") put(key, newFileRefs) else put(key, value)
            }
        }

        val text = if (changed > 0) {
            runCatching { json.encodeToString(JsonObject.serializer(), newRoot) }.getOrElse { model3Text }
        } else {
            model3Text
        }
        return RepairResult(
            json = text,
            changedRefs = changed,
            missingCritical = missing,
            mocResolved = mocResolved,
            texturesResolved = texturesResolved
        )
    }

    private fun fixMotions(
        motions: JsonObject?,
        fix: (JsonElement?, Boolean) -> JsonElement
    ): JsonElement {
        if (motions == null) return JsonObject(emptyMap())
        return JsonObject(
            motions.mapValues { (_, array) ->
                val items = (array as? JsonArray)?.map { item ->
                    val obj = item as? JsonObject ?: return@map item
                    JsonObject(
                        obj.mapValues { (key, value) ->
                            if (key == "File" || key == "Sound") fix(value, false) else value
                        }
                    )
                } ?: emptyList()
                JsonArray(items)
            }
        )
    }

    private fun fixExpressions(
        expressions: JsonArray?,
        fix: (JsonElement?, Boolean) -> JsonElement
    ): JsonElement {
        if (expressions == null) return JsonArray(emptyList())
        return JsonArray(
            expressions.map { item ->
                val obj = item as? JsonObject ?: return@map item
                JsonObject(obj.mapValues { (key, value) -> if (key == "File") fix(value, false) else value })
            }
        )
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null
}

/**
 * 把单个模型目录"落地合格化"：定位 model3.json → 修正引用 → 校验关键资源。
 *
 * 导入后立即调用（坏模型当场报失败，而不是静默进库后不显示），
 * 扫描时也会对历史遗留 / adb 推送的模型兜底调用一次（幂等）。
 */
object Live2dModelLocalizer {

    data class Outcome(
        val ok: Boolean,
        val reason: String? = null,
        val rewrittenRefs: Int = 0
    )

    private const val MAX_SEARCH_DEPTH = 4

    /**
     * @return [Outcome.ok] 为 true 表示该目录至少含一个可渲染模型；
     *         false 时 [Outcome.reason] 是面向用户的可读原因。
     */
    fun localize(modelDir: File): Outcome {
        val model3 = findPrimaryModel3(modelDir) ?: return Outcome(false, "未找到 *.model3.json")
        val text = runCatching { model3.readText() }.getOrNull()
            ?: return Outcome(false, "model3.json 无法读取")

        val index = Live2dFileIndex(modelDir)
        val repair = Live2dModelRefs.repair(text, index)
            // JSON 非法：交给扫描侧走原有"损坏"判定，这里不额外拦截
            ?: return Outcome(true)

        if (!repair.criticalOk) {
            val missing = repair.missingCritical.firstOrNull()
            val reason = if (repair.mocResolved == null) {
                "缺少 .moc3 模型文件${missing?.let { "（$it）" } ?: ""}"
            } else {
                "缺少贴图文件${missing?.let { "（$it）" } ?: ""}"
            }
            return Outcome(false, reason)
        }
        if (repair.changedRefs > 0) {
            runCatching { model3.writeText(repair.json) }
        }
        return Outcome(true, rewrittenRefs = repair.changedRefs)
    }

    /**
     * 主 model3 择优（跨平台确定序）：
     *
     * 1. **与自身所在目录同名**（官方 rip 命名习惯 `<id>/<id>/<id>.model3.json`）；
     * 2. 与顶层目录同名；
     * 3. BFS 最浅者，同深度按名字典序。
     *
     * 顺序必须来自显式排序而不是 [File.listFiles]：Windows NTFS 返回字母序，
     * Linux ext4 是哈希序——靠遍历序挑"第一个"在 CI/真机上会翻车。
     */
    fun findPrimaryModel3(modelDir: File): File? {
        data class Candidate(val file: File, val depth: Int)

        fun stemOf(file: File) = Live2dModelLayout.stripModel3Ext(file.name)

        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(modelDir to 0)
        val found = ArrayList<Candidate>()
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val children = current.listFiles()
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?: continue
            for (child in children) {
                if (!child.isFile) continue
                if (child.name.endsWith(Live2dModelLayout.MODEL3_EXT, ignoreCase = true)) {
                    found += Candidate(child, depth)
                }
            }
            if (depth < MAX_SEARCH_DEPTH) {
                for (child in children) {
                    if (child.isDirectory) queue.addLast(child to depth + 1)
                }
            }
        }
        if (found.isEmpty()) return null

        val topName = modelDir.name
        found.firstOrNull { candidate ->
            stemOf(candidate.file).equals(candidate.file.parentFile?.name ?: "", ignoreCase = true)
        }?.let { return it.file }
        found.firstOrNull { candidate ->
            stemOf(candidate.file).equals(topName, ignoreCase = true)
        }?.let { return it.file }
        return found.first().file
    }
}
