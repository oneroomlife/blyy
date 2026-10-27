package com.azurlane.blyy.util.unity

/**
 * Unity `AnimationClip` → Live2D 参数曲线 的解码器。
 *
 * ## 背景
 * 碧蓝航线的 Live2D 皮肤包**不含** `motion3.json`：动作是 Unity 的 Mecanim 动画片段
 * （`AnimationClip`，113 条左右，命名即 `idle5` / `touch_head` / `touch_drag12`）。
 * 每条片段的曲线数据以「压缩片段」（streamed / dense / constant 三选一）存放在
 * `m_MuscleClip.m_Clip` 里，曲线索引对应 `m_ClipBindingConstant.genericBindings`
 * 的下标；绑定只给路径 **hash 与属性 hash**，需要按 `Animator.StringToHash`
 * （= 区分大小写的标准 CRC-32）反查 GameObject 路径，末段即 moc3 参数 id。
 *
 * ## 片段数据结构（Unity 2022.3 序列化）
 * ```
 * AnimationClip
 *  └ m_MuscleClip                     ClipMuscleConstant
 *     ├ m_StartTime / m_StopTime
 *     └ m_Clip                        OffsetPtr<Clip>
 *        └ data                       Clip
 *           ├ m_StreamedClip          { data: u32[], curveCount: u16 }
 *           ├ m_DenseClip             { m_FrameCount, m_CurveCount, m_SampleRate, m_BeginTime, m_SampleArray }
 *           └ m_ConstantClip          { data: float[] }   # 只取首帧值的曲线
 *  └ m_ClipBindingConstant            { genericBindings: GenericBinding[], pptrCurveMapping }
 * ```
 * `m_StreamedClip.data` 是逐帧打包的关键帧：`[time, keyCount, (index, c0, c1, outSlope, value)…]`。
 *
 * ## 为什么可以直接线性化
 * streamed 片段按帧记录变化量（60fps），关键帧本身足够密，用 Linear 段重建与原始
 * Bezier 视觉等价，且不会因切线外推产生过冲。
 */
internal object AnimationClipDecoder {

    /** 一条 GenericBinding（曲线 → 属性） */
    data class Binding(
        /** `Animator.StringToHash(path)`，u32 */
        val pathHash: Long,
        /** 属性 hash（本模型中恒为 `Value` 字段的 CRC32） */
        val attribute: Long,
        val classId: Int
    )

    /** 流式片段的单条关键帧 */
    class StreamKey(
        val index: Int,
        var inSlope: Float,
        val outSlope: Float,
        val value: Float,
        val c0: Float,
        val c1: Float
    )

    /** 流式片段的一帧 */
    class StreamFrame(
        val time: Float,
        val keys: HashMap<Int, StreamKey>
    )

    class Clip(
        val name: String,
        val duration: Float,
        val fps: Float,
        val loop: Boolean,
        val bindings: List<Binding>,
        val frames: List<StreamFrame>,
        val streamedCurveCount: Int,
        val constantValues: List<Float>
    ) {
        /** 曲线总数（= 绑定数，本模型中 1 绑定 = 1 曲线） */
        val curveCount: Int get() = bindings.size
    }

    /**
     * 从类型树读出的字段表构造 [Clip]；缺少必要字段（非 Live2D 动作片段）时返回 null。
     */
    fun decode(values: Map<String, Any?>): Clip? {
        val name = values["m_Name"] as? String ?: return null
        val muscle = values["m_MuscleClip"] as? Map<*, *> ?: return null
        val startTime = (muscle["m_StartTime"] as? Number)?.toFloat() ?: 0f
        val stopTime = (muscle["m_StopTime"] as? Number)?.toFloat() ?: 0f
        val clipData = ((muscle["m_Clip"] as? Map<*, *>)?.get("data") as? Map<*, *>) ?: return null

        val streamed = clipData["m_StreamedClip"] as? Map<*, *>
        val constant = clipData["m_ConstantClip"] as? Map<*, *>
        val dense = clipData["m_DenseClip"] as? Map<*, *>

        val words = (streamed?.get("data") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val streamedCurveCount = (streamed?.get("curveCount") as? Number)?.toInt() ?: 0
        val constantValues = (constant?.get("data") as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f }
            ?: emptyList()

        // dense 片段（逐帧采样）在碧蓝的皮肤包里极少出现；这里作为兜底转换
        val denseValues = (dense?.get("m_SampleArray") as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f }
            ?: emptyList()
        val denseCurveCount = (dense?.get("m_CurveCount") as? Number)?.toInt() ?: 0
        val denseFrameCount = (dense?.get("m_FrameCount") as? Number)?.toInt() ?: 0
        val denseSampleRate = (dense?.get("m_SampleRate") as? Number)?.toFloat() ?: 60f
        val denseBegin = (dense?.get("m_BeginTime") as? Number)?.toFloat() ?: 0f

        val bindings = ((values["m_ClipBindingConstant"] as? Map<*, *>)?.get("genericBindings") as? List<*>)
            ?.mapNotNull { raw ->
                val b = raw as? Map<*, *> ?: return@mapNotNull null
                Binding(
                    pathHash = (b["path"] as? Number)?.toLong() ?: return@mapNotNull null,
                    attribute = (b["attribute"] as? Number)?.toLong() ?: 0L,
                    classId = (b["typeID"] as? Number)?.toInt() ?: 0
                )
            } ?: emptyList()

        if (bindings.isEmpty()) return null

        val frames = if (words.isNotEmpty()) decodeStreamed(words) else emptyList()

        // dense → 合成 frames（每帧同时间点，按键为曲线下标）
        val allFrames = if (frames.isNotEmpty()) frames else denseToFrames(
            denseValues, denseCurveCount, denseFrameCount, denseSampleRate, denseBegin
        )

        val duration = (stopTime - startTime).takeIf { it > 0f } ?: 0.5f
        val fps = (values["m_SampleRate"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 60f
        return Clip(
            name = name,
            duration = duration,
            fps = fps,
            loop = readLoop(muscle),
            bindings = bindings,
            frames = allFrames,
            streamedCurveCount = streamedCurveCount,
            constantValues = constantValues
        )
    }

    /** `m_LoopTime`：Unity 片段默认循环（碧蓝的待机/触摸动作都是循环动作） */
    private fun readLoop(muscle: Map<*, *>): Boolean = (muscle["m_LoopTime"] as? Boolean) ?: true

    private fun denseToFrames(
        values: List<Float>,
        curveCount: Int,
        frameCount: Int,
        sampleRate: Float,
        beginTime: Float
    ): List<StreamFrame> {
        if (curveCount <= 0 || frameCount <= 0 || values.size < curveCount * frameCount) return emptyList()
        val rate = if (sampleRate > 0f) sampleRate else 60f
        return (0 until frameCount).map { fi ->
            val keys = HashMap<Int, StreamKey>(curveCount)
            for (ci in 0 until curveCount) {
                val v = values[fi * curveCount + ci]
                keys[ci] = StreamKey(ci, 0f, 0f, v, 0f, 0f)
            }
            StreamFrame(beginTime + fi / rate, keys)
        }
    }

    /**
     * `m_StreamedClip.data`（u32 字序列）→ 帧列表，并按 Unity 的语义回填 `inSlope`。
     *
     * 帧格式：`time: f32, keyCount: i32, keyCount × (index: i32, coeff[4]: f32×4)`，
     * 其中 `coeff[2] = outSlope`、`coeff[3] = value`。tangent 入切线需要结合前一个
     * 出现同一曲线索引的帧，因此单独做一次回溯（与 AssetStudio `StreamedClip.ReadData` 一致）。
     */
    fun decodeStreamed(words: List<Int>): List<StreamFrame> {
        val frames = ArrayList<StreamFrame>(256)
        var p = 0
        val n = words.size
        while (p + 1 < n) {
            val time = Float.fromBits(words[p])
            val keyCount = words[p + 1]
            p += 2
            if (keyCount < 0 || p + keyCount * 5 > n) break
            val keys = HashMap<Int, StreamKey>(keyCount * 2)
            repeat(keyCount) {
                val index = words[p]
                val c0 = Float.fromBits(words[p + 1])
                val c1 = Float.fromBits(words[p + 2])
                val outSlope = Float.fromBits(words[p + 3])
                val value = Float.fromBits(words[p + 4])
                p += 5
                keys[index] = StreamKey(index, 0f, outSlope, value, c0, c1)
            }
            frames += StreamFrame(time, keys)
        }
        if (frames.size > 3) {
            for (fi in 2 until frames.size - 1) {
                val frame = frames[fi]
                for (key in frame.keys.values) {
                    for (pi in fi - 1 downTo 0) {
                        val pre = frames[pi].keys[key.index] ?: continue
                        val dxRaw = frame.time - frames[pi].time
                        key.inSlope = nextInSlope(dxRaw, pre, key)
                        break
                    }
                }
            }
        }
        return frames
    }

    /** Unity `StreamedCurveKey.CalculateNextInSlope`：Stepped 曲线返回 +Inf */
    private fun nextInSlope(dxRaw: Float, pre: StreamKey, cur: StreamKey): Float {
        if (pre.c0 == 0f && pre.c1 == 0f && pre.outSlope == 0f) return Float.POSITIVE_INFINITY
        val dx = if (dxRaw > 0.0001f) dxRaw else 0.0001f
        val dy = cur.value - pre.value
        val length = 1f / (dx * dx)
        val d1 = pre.outSlope * dx
        val d2 = dy + dy + dy - d1 - d1 - pre.c1 / length
        return d2 / dx
    }

    /**
     * 曲线下标 → 关键帧序列 `(time, value)`。
     * 只保留该曲线真正出现过的帧（streamed 片段是按需记录的）。
     */
    fun track(clip: Clip, curveIndex: Int): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(64)
        for (frame in clip.frames) {
            val key = frame.keys[curveIndex] ?: continue
            out += frame.time to key.value
        }
        return out
    }

    /** 曲线下标 → 常量值（streamed 之后的曲线落在 constant 片段里） */
    fun constantOf(clip: Clip, curveIndex: Int): Float? {
        val idx = curveIndex - clip.streamedCurveCount
        return clip.constantValues.getOrNull(idx)
    }

}

/**
 * `Animator.StringToHash` 的等价实现：**区分大小写**的标准 CRC-32
 * （多项式 0xEDB88320，初值/末值异或 0xFFFFFFFF），输入为完整 GameObject 路径。
 * 实测：`"Value"` → 3702945584，与该资源包里 GenericBinding 的 attribute 完全一致。
 */
internal object AnimatorHash {

    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            table[i] = c
        }
    }

    fun hash(text: String): Long {
        var crc = -1 // 0xFFFFFFFF
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            crc = (crc ushr 8) xor TABLE[(crc xor b.toInt()) and 0xFF]
        }
        return (crc.inv().toLong() and 0xFFFFFFFFL)
    }
}
