package com.azurlane.blyy.util.unity

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 碧蓝航线 Live2D 资源包（UnityFS AssetBundle）→ 标准 Cubism 3/4 目录 的提取器。
 *
 * 碧蓝航线的 Live2D 皮肤并不以标准 Cubism 目录下发，而是打包成 UnityFS：
 * ```
 * UnityFS (LZ4HC)
 *  └─ CAB-xxxx                       # SerializedFile v22
 *      ├─ MonoBehaviour (最大, >10MB) # CubismMoc: 首 48 字节头 + 整个 .moc3
 *      ├─ TextAsset  "<model>.physics3"
 *      ├─ Texture2D  texture_00/01    # 4096², ASTC_4x4, 数据在 .resS
 *      ├─ AnimationClip ...           # Mecanim muscle clip（不参与渲染）
 *      └─ AssetBundle  live2d/<model>
 *  └─ CAB-xxxx.resS                  # 流式贴图数据
 * ```
 * 提取后写出标准结构供现有 WebView 渲染：
 * ```
 * <out>/<id>.model3.json
 * <out>/model.moc3
 * <out>/physics3.json          （存在时）
 * <out>/textures/texture_00.png ...
 * ```
 */
@Singleton
class AlLive2dExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AlLive2dExtractor"

        /** 记录来源信息的侧车文件（供详情页展示，不影响 Cubism 解析） */
        const val SOURCE_FILE = "_blyy_source.json"

        /** 动作输出目录（model3.json 内引用为 `motions/xxx.motion3.json`） */
        const val MOTION_DIR = "motions"

        /**
         * 提取管线版本：产物结构变化时 +1，索引里的旧条目会被判为过期并重新解包。
         * 1 → 2：修复贴图上下镜像 + 由 AnimationClip 生成 motion3.json 动作
         * 2 → 3：贴图解码改用共享的 [UnityTextureExtractor]（支持 ASTC 5x5/6x6/… 与
         *        RGBA32 翻转），并复用解压镜像缓存避免重复解压
         */
        const val EXTRACTOR_VERSION = 3

        private const val MOC3_HEADER_PROBE = 512
        private const val COPY_CHUNK = 1 shl 20
        private val MOC3_MAGIC = byteArrayOf(
            'M'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), '3'.code.toByte()
        )
    }

    /** 提取结果摘要 */
    data class Report(
        val id: String,
        val moc3Bytes: Long,
        val mocVersion: Int,
        val textures: List<String>,
        val textureSize: String,
        val physicsBytes: Long,
        /** 生成的 motion3 动作数量 */
        val motionCount: Int,
        /** 动作组数量（idle / touch_head / …） */
        val motionGroups: Int,
        val elapsedMs: Long
    )

    /** 动作生成结果：组名 → motion3 相对路径列表 */
    private data class MotionsResult(
        val groups: Map<String, List<String>>,
        val count: Int
    )

    private data class MocInfo(
        val objectDef: SerializedFile.ObjectDef,
        val dataOffset: Long,
        val dataSize: Long,
        val version: Int
    )

    /**
     * 把 [bundleFile] 提取到 [outputDir]（会被清空重建）。
     *
     * @param id 模型 id（= 目录名，用于 model3.json 命名）
     * @param maxTextureSize 贴图最长边上限（4096² ASTC 全量解码占用较高，默认降到 2048）
     * @param onProgress 进度回调（0f..1f, 阶段描述），会在 IO 线程调用
     */
    suspend fun extract(
        bundleFile: File,
        outputDir: File,
        id: String,
        maxTextureSize: Int = 2048,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!bundleFile.isFile) throw IOException("资源包不存在：${bundleFile.name}")
        if (!UnityFsBundle.detect(bundleFile)) throw IOException("不是 UnityFS 资源包：${bundleFile.name}")

        onProgress(0.02f, "解析资源包头")
        val bundle = UnityFsBundle.open(bundleFile)

        val imageFile = UnityBundleSession.imageCacheFile(context, bundleFile)
        val expectedTotal = bundle.nodes.sumOf { it.size }
        if (!imageFile.isFile || imageFile.length() != expectedTotal) {
            onProgress(0.04f, "解压资源包（${bundle.blocks.size} 个数据块）")
            runCatching { imageFile.delete() }
            bundle.decompressTo(imageFile) { done, total ->
                val fraction = if (total <= 0L) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
                onProgress(0.04f + fraction * 0.62f, "解压资源包 ${(fraction * 100).toInt()}%")
            }
        }

        val report = try {
            BundleImage(imageFile, bundle.nodes).use { image ->
                onProgress(0.68f, "读取资源目录")
                val serializedNode = bundle.serializedNode()
                    ?: throw IOException("资源包内没有序列化文件（CAB）")
                val sf = SerializedFile.open(image, serializedNode)
                Log.i(TAG, "资源包 ${bundleFile.name}: 对象 ${sf.objects.size}, 类型 ${sf.types.size}")

                // ---- 1. moc3 ----
                onProgress(0.72f, "定位模型数据")
                val moc = findMoc3(sf)
                    ?: throw IOException("资源包内没有找到 MOC3 模型数据（可能不是 Live2D 皮肤包）")

                outputDir.deleteRecursively()
                if (!outputDir.exists() && !outputDir.mkdirs()) throw IOException("无法创建目录：${outputDir.path}")
                val textureDir = File(outputDir, "textures").apply { mkdirs() }

                onProgress(0.75f, "导出模型数据")
                val mocFile = File(outputDir, "model.moc3")
                copyRange(image, serializedNode, moc.objectDef.byteStart + moc.dataOffset, moc.dataSize, mocFile)

                // ---- 2. physics3 ----
                onProgress(0.80f, "导出物理配置")
                var physicsFile: File? = null
                var physicsBytes = 0L
                sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).forEach { def ->
                    if (physicsFile != null) return@forEach
                    val asset = runCatching { UnityTextAssetReader.read(sf, def) }.getOrNull()
                        ?: return@forEach
                    val name = asset.name
                    val script = asset.bytes
                    if (name.endsWith(".physics3") || name.contains("physics3")) {
                        val target = File(outputDir, "physics3.json")
                        target.writeBytes(script)
                        physicsFile = target
                        physicsBytes = script.size.toLong()
                    }
                }

                // ---- 3. 贴图 ----
                val textures = UnityTextureExtractor.list(sf).sortedBy { it.name }
                if (textures.isEmpty()) throw IOException("资源包内没有可用的贴图")

                val resNode = bundle.resourceNode()
                if (resNode == null && textures.any { it.isStreamed }) {
                    throw IOException("资源包缺少 .resS 流式数据，无法读取贴图")
                }
                val stream = TextureStream { offset, size ->
                    readStream(
                        image,
                        resNode ?: throw IOException("资源包缺少 .resS 流式数据，无法读取贴图"),
                        offset,
                        size
                    )
                }

                val writtenTextures = ArrayList<String>(textures.size)
                var maxW = 0
                var maxH = 0
                textures.forEachIndexed { index, tex ->
                    val fraction = (index + 0.0f) / textures.size
                    onProgress(0.82f + fraction * 0.14f, "解码贴图 ${index + 1}/${textures.size}")
                    val outFile = File(textureDir, "${tex.name.ifBlank { "texture_$index" }}.png")
                    UnityTextureExtractor.extract(stream, tex, outFile, maxTextureSize)
                    writtenTextures += "textures/${outFile.name}"
                    // 记录源尺寸（而不是降采样后的尺寸），详情页展示才准确
                    maxW = maxOf(maxW, tex.width)
                    maxH = maxOf(maxH, tex.height)
                }

                // ---- 4. 动作：AnimationClip（Mecanim）→ motion3.json ----
                onProgress(0.96f, "解析动作数据")
                val rig = runCatching { Live2dRigIndex.build(sf) }
                    .onFailure { Log.w(TAG, "构建参数路径索引失败", it) }
                    .getOrNull()
                val motionDir = File(outputDir, MOTION_DIR).apply { mkdirs() }
                val motions = if (rig == null) {
                    MotionsResult(emptyMap(), 0)
                } else {
                    buildMotions(sf, rig, motionDir) { fraction, message ->
                        onProgress(0.96f + fraction * 0.03f, message)
                    }
                }

                // ---- 5. 合成 model3.json ----
                onProgress(0.99f, "生成模型配置")
                val model3Name = "$id.model3.json"
                val model3 = buildModel3(
                    id = id,
                    mocName = mocFile.name,
                    textures = writtenTextures,
                    physicsName = physicsFile?.name,
                    motionGroups = motions.groups,
                    eyeBlinkIds = rig?.eyeBlinkIds().orEmpty(),
                    lipSyncIds = rig?.lipSyncIds().orEmpty()
                )
                File(outputDir, model3Name).writeText(model3.toString())

                writeSourceInfo(
                    outputDir = outputDir,
                    bundleFile = bundleFile,
                    mocVersion = moc.version,
                    moc3Bytes = mocFile.length(),
                    textures = writtenTextures,
                    physicsBytes = physicsBytes,
                    imageBytes = imageFile.length(),
                    motionCount = motions.count,
                    motionGroups = motions.groups.size
                )

                Report(
                    id = id,
                    moc3Bytes = mocFile.length(),
                    mocVersion = moc.version,
                    textures = writtenTextures,
                    textureSize = if (maxW > 0) "${maxW}x$maxH" else "-",
                    physicsBytes = physicsBytes,
                    motionCount = motions.count,
                    motionGroups = motions.groups.size,
                    elapsedMs = System.currentTimeMillis() - started
                )
            }
        } finally {
            // 解压镜像动辄 60MB+，提取完成立即释放
            runCatching { imageFile.delete() }
        }
        onProgress(1f, "完成")
        report
    }

    /** 判断文件是否是碧蓝航线 Live2D 资源包（UnityFS + 内含 MOC3 或 AssetBundle 名含 live2d） */
    fun looksLikeLive2dBundle(file: File): Boolean = UnityFsBundle.detect(file)

    // ------------------------------------------------------------------ 内部实现

    /** 在 MonoBehaviour 里按 `MOC3` 魔数定位 moc3：`... 字符串名 | u32 长度 | 'MOC3' ...` */
    private fun findMoc3(sf: SerializedFile): MocInfo? {
        var best: MocInfo? = null
        for (def in sf.objectsOfClass(SerializedFile.CLASS_MONO_BEHAVIOUR)) {
            if (def.byteSize < 64) continue
            val probe = runCatching { sf.peek(def, minOf(def.byteSize, MOC3_HEADER_PROBE)) }.getOrNull()
                ?: continue
            val magicAt = indexOfMagic(probe) ?: continue
            // 长度前缀：紧邻魔数之前的 u32（小端），必须与剩余长度一致
            val remaining = def.byteSize - magicAt
            var size = remaining.toLong()
            if (magicAt >= 4) {
                val declared = readLeInt(probe, magicAt - 4)
                if (declared > 0 && declared.toLong() == remaining.toLong()) {
                    size = declared.toLong()
                } else if (declared > 0 && magicAt + declared <= def.byteSize) {
                    size = declared.toLong()
                }
            }
            if (size < 1024) continue
            val version = probe[magicAt + 4].toInt() and 0xFF
            val candidate = MocInfo(def, magicAt.toLong(), size, version)
            if (best == null || candidate.dataSize > best.dataSize) best = candidate
        }
        return best
    }

    private fun indexOfMagic(bytes: ByteArray): Int? {
        val limit = bytes.size - MOC3_MAGIC.size
        for (i in 0..limit) {
            if (bytes[i] == MOC3_MAGIC[0] && bytes[i + 1] == MOC3_MAGIC[1] &&
                bytes[i + 2] == MOC3_MAGIC[2] && bytes[i + 3] == MOC3_MAGIC[3]
            ) {
                return i
            }
        }
        return null
    }

    private fun readLeInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readStream(
        image: BundleImage,
        node: UnityFsBundle.Node,
        offset: Long,
        size: Long
    ): ByteBuffer {
        if (offset < 0 || size <= 0 || offset + size > node.size) {
            throw IOException("贴图数据越界：offset=$offset size=$size node=${node.size}")
        }
        val buffer = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        val chunk = ByteArray(minOf(size, COPY_CHUNK.toLong()).toInt())
        var done = 0L
        while (done < size) {
            val n = minOf(chunk.size.toLong(), size - done).toInt()
            image.readFully(node, offset + done, chunk, 0, n)
            buffer.put(chunk, 0, n)
            done += n
        }
        buffer.flip()
        return buffer
    }

    private fun copyRange(
        image: BundleImage,
        node: UnityFsBundle.Node,
        start: Long,
        size: Long,
        target: File
    ) {
        val chunk = ByteArray(minOf(size, COPY_CHUNK.toLong()).toInt().coerceAtLeast(1))
        target.outputStream().buffered().use { out ->
            var done = 0L
            while (done < size) {
                val n = minOf(chunk.size.toLong(), size - done).toInt()
                image.readFully(node, start + done, chunk, 0, n)
                out.write(chunk, 0, n)
                done += n
            }
        }
    }

    private fun buildModel3(
        id: String,
        mocName: String,
        textures: List<String>,
        physicsName: String?,
        motionGroups: Map<String, List<String>>,
        eyeBlinkIds: List<String>,
        lipSyncIds: List<String>
    ): JsonObject = buildJsonObject {
        put("Version", 3)
        put("Name", id)
        put("FileReferences", buildJsonObject {
            put("Moc", mocName)
            put("Textures", buildJsonArray { textures.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            if (physicsName != null) put("Physics", physicsName)
            // Motions 必须挂在 FileReferences 下（放到根节点会被 Cubism 运行时忽略，
            // 表现为"模型能显示但一个动作都加载不出来"）
            if (motionGroups.isNotEmpty()) {
                put("Motions", buildJsonObject {
                    motionGroups.forEach { (group, files) ->
                        put(group, buildJsonArray {
                            files.forEach { file ->
                                add(buildJsonObject { put("File", file) })
                            }
                        })
                    }
                })
            }
        })
        // 眨眼/口型分组来自资源包里的参数命名（`ParamEyeLOpen` / `ParamMouthOpenY`）；
        // 碧蓝把部分参数泛化成 ParamNN，取不到标准名时不写该组——宁可不眨眼，
        // 也不要猜错参数导致五官被压扁
        if (eyeBlinkIds.isNotEmpty() || lipSyncIds.isNotEmpty()) {
            put("Groups", buildJsonArray {
                if (eyeBlinkIds.isNotEmpty()) {
                    add(buildJsonObject {
                        put("Target", "Parameter")
                        put("Name", "EyeBlink")
                        put("Ids", buildJsonArray {
                            eyeBlinkIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    })
                }
                if (lipSyncIds.isNotEmpty()) {
                    add(buildJsonObject {
                        put("Target", "Parameter")
                        put("Name", "LipSync")
                        put("Ids", buildJsonArray {
                            lipSyncIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    })
                }
            })
        } else {
            put("Groups", buildJsonArray { })
        }
    }

    /**
     * 把资源包里的全部 [SerializedFile.CLASS_ANIMATION_CLIP]（Mecanim 动作片段）
     * 转成 Cubism 的 `motions/<片段名>.motion3.json`，并按语义动作组归类。
     *
     * 片段名本身就是碧蓝的动作名（`idle5` / `touch_head` / `touch_drag12`…），
     * 直接沿用 → 查看器的触摸/陪伴逻辑与语音场景表（摸头/普通触摸/登录…）无需改动。
     */
    private fun buildMotions(
        sf: SerializedFile,
        rig: Live2dRigIndex,
        motionDir: File,
        onProgress: (Float, String) -> Unit
    ): MotionsResult {
        val groups = LinkedHashMap<String, MutableList<String>>()
        val defs = sf.objectsOfClass(SerializedFile.CLASS_ANIMATION_CLIP)
        if (defs.isEmpty()) return MotionsResult(emptyMap(), 0)

        var written = 0
        var index = 0
        for (def in defs) {
            index++
            val clip = runCatching { AnimationClipDecoder.decode(sf.readValues(def)) }
                .onFailure { Log.w(TAG, "解析动作片段失败: def=${def.pathID}", it) }
                .getOrNull() ?: continue
            val fraction = index.toFloat() / defs.size
            onProgress(fraction, "转换动作 $index/${defs.size}")

            val group = Live2dMotionGroups.of(clip.name)
            // 待机组做首尾闭合：运行时会把待机动作原地无限循环（见 viewer.js 的
            // setIsLoop 接线），而碧蓝的 idle 剪辑首尾并不闭合（特效/开关参数常从 0
            // 跳到满量程）→ 不闭合就会每圈闪一下
            val builder = Motion3Builder(
                duration = clip.duration,
                fps = clip.fps,
                loop = clip.loop,
                loopSeamless = group == Live2dMotionGroups.IDLE
            )
            var curveCount = 0
            for (ci in clip.bindings.indices) {
                val paramId = rig.paramOf(clip.bindings[ci]) ?: continue
                if (ci < clip.streamedCurveCount) {
                    val points = AnimationClipDecoder.track(clip, ci)
                    if (points.size < 2) {
                        // 单帧曲线：Cubism 的曲线至少要一段才能被采样，按常量处理
                        val value = points.firstOrNull()?.second
                        if (value != null && builder.addConstantCurve(paramId, value)) curveCount++
                        continue
                    }
                    val times = FloatArray(points.size)
                    val values = FloatArray(points.size)
                    for (i in points.indices) {
                        times[i] = points[i].first
                        values[i] = points[i].second
                    }
                    if (builder.addCurve(paramId, times, values)) curveCount++
                } else {
                    val value = AnimationClipDecoder.constantOf(clip, ci) ?: continue
                    if (builder.addConstantCurve(paramId, value)) curveCount++
                }
            }
            if (curveCount == 0) {
                Log.w(TAG, "动作片段无可用曲线: ${clip.name}")
                continue
            }
            val fileName = sanitizeFileName(clip.name)
            val target = File(motionDir, "$fileName.motion3.json")
            runCatching { builder.writeTo(target) }
                .onFailure {
                    Log.w(TAG, "写出动作失败: ${clip.name}", it)
                    target.delete()
                }
            if (!target.isFile) continue
            val rel = "$MOTION_DIR/${target.name}"
            groups.getOrPut(group) { mutableListOf() } += rel
            written++
        }

        // 动作组内按名字排序（idle1, idle2, … idle10 而不是字典序 idle1, idle10, idle2），
        // 只为展示稳定；运行时是随机取样，顺序不影响观感
        val sorted = groups.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to v.sortedWith(MOTION_ORDER) }
        Log.i(TAG, "动作生成完成：${written} 条 / ${sorted.size} 组")
        return MotionsResult(sorted, written)
    }

    /** `idle2` 排在 `idle10` 前面：同组内按「前缀 + 数字」自然序 */
    private val MOTION_ORDER = Comparator<String> { a, b ->
        val an = a.substringAfterLast('/').substringBefore('.')
        val bn = b.substringAfterLast('/').substringBefore('.')
        val ap = an.takeWhile { !it.isDigit() }
        val bp = bn.takeWhile { !it.isDigit() }
        if (ap != bp) ap.compareTo(bp) else {
            val ai = an.drop(ap.length).toIntOrNull() ?: -1
            val bi = bn.drop(bp.length).toIntOrNull() ?: -1
            if (ai != bi) ai.compareTo(bi) else an.compareTo(bn)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\s]"""), "_").trim()
        return cleaned.ifEmpty { "motion" }.take(64)
    }

    private fun writeSourceInfo(
        outputDir: File,
        bundleFile: File,
        mocVersion: Int,
        moc3Bytes: Long,
        textures: List<String>,
        physicsBytes: Long,
        imageBytes: Long,
        motionCount: Int,
        motionGroups: Int
    ) {
        val info = buildJsonObject {
            put("source", "unityfs")
            put("extractorVersion", EXTRACTOR_VERSION)
            put("bundle", bundleFile.name)
            put("bundleBytes", bundleFile.length())
            put("imageBytes", imageBytes)
            put("mocVersion", mocVersion)
            put("moc3Bytes", moc3Bytes)
            put("physicsBytes", physicsBytes)
            put("motions", motionCount)
            put("motionGroups", motionGroups)
            put("textures", buildJsonArray {
                textures.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("extractedAt", System.currentTimeMillis())
        }
        OutputStreamWriter(File(outputDir, SOURCE_FILE).outputStream(), Charsets.UTF_8).use {
            it.write(info.toString())
        }
    }
}
