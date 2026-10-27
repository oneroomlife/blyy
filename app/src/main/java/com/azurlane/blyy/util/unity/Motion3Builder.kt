package com.azurlane.blyy.util.unity

import java.io.File

/**
 * `motion3.json` 生成器（Cubism 3/4 动作格式）。
 *
 * ## 曲线段格式
 * `Segments` 是扁平浮点数组：首元素恒为 `0`（Linear）+ 起点值，其后每段是
 * `[类型, …参数]`，Linear/Stepped/InverseStepped 各 3 个数、Bezier 7 个数。
 *
 * ## 与 Cubism 解析器严格对齐的两处坑（都踩过）
 * 1. **只有起点、没有段的曲线仍然会占用一个 `segments` 槽位**：
 *    `CubismMotion.parse` 每轮循环先写 `segments[o].basePointIndex` 再按段类型取值，
 *    循环结束前无条件 `++o`。所以常量曲线若写成 `[0, value]`（0 段），
 *    解析器会写到 `TotalSegmentCount` 之外 → `Cannot set properties of undefined`。
 *    这里给常量曲线补一个覆盖整段时长的 Linear 段，槽位数与计数始终一致。
 * 2. `Meta.TotalSegmentCount` / `TotalPointCount` 必须**精确等于**解析器实际会
 *    消费的槽位/点数，多一个少一个都会让整条动作被判为加载失败。
 *
 * ## 待机动作的首尾闭合
 * 碧蓝的 idle 剪辑是按「播一次再由游戏交叉淡化到下一段」制作的，直接原地循环会在
 * 接缝处**跳变**：实测 `chaijun_6` 的 82 条 idle 全部存在首尾差（典型是特效/开关类
 * 参数 `TX_...`/`Circle_QTE_...` 从 0 直接跳到满量程，每圈闪一下）。
 * [loopSeamless] = true 时在最后 [SEAM_WINDOW_SEC] 内把值平滑过渡回首帧值（smoothstep，
 * 首尾值与一阶导都连续），使任意 idle 都能被运行时原地无限循环而不露接缝。
 */
private const val SEAM_WINDOW_SEC = 0.35f

internal class Motion3Builder(
    private val duration: Float,
    private val fps: Float,
    private val loop: Boolean,
    /** 待机动作：做首尾闭合，保证原地循环无跳变 */
    private val loopSeamless: Boolean = false
) {
    private val body = StringBuilder(4096)
    private var curveCount = 0
    private var segmentSlots = 0
    private var pointCount = 0

    /**
     * 追加一条参数曲线（统一线性段）。
     * @return 是否写入（点数不足 / 全为非法值时返回 false）
     */
    fun addCurve(id: String, times: FloatArray, values: FloatArray): Boolean {
        if (id.isBlank() || times.isEmpty() || times.size != values.size) return false
        val cleaned = sanitize(times, values)
        if (cleaned.isEmpty()) return false
        if (cleaned.size == 1) return addConstantCurve(id, cleaned[0].second)
        val points = if (loopSeamless) seamClose(cleaned) else cleaned
        appendCurveHead(id)
        // 起点
        body.append('0').append(',').append(fmt(points[0].second))
        var segments = 0
        var pointCount = 1
        var lastTime = points[0].first
        for (i in 1 until points.size) {
            val t = points[i].first
            if (t <= lastTime) continue
            lastTime = t
            body.append(',').append('0').append(',')
                .append(fmt(t)).append(',').append(fmt(points[i].second))
            segments++
            pointCount++
        }
        finishCurve()
        segmentSlots += if (segments > 0) segments else 1
        this.pointCount += pointCount
        return true
    }

    /** 追加一条常量曲线（覆盖整段时长的一次 Linear 段） */
    fun addConstantCurve(id: String, value: Float): Boolean {
        if (id.isBlank() || !value.isFinite()) return false
        appendCurveHead(id)
        val end = if (duration > 0.001f) duration else 0.1f
        val v = fmt(value)
        body.append('0').append(',').append(v)
            .append(',').append('0').append(',').append(fmt(end)).append(',').append(v)
        finishCurve()
        segmentSlots += 1
        pointCount += 2
        return true
    }

    /** 组装完整 JSON；没有任何曲线时返回 null（不允许生成空动作） */
    fun build(): String? {
        if (curveCount == 0) return null
        val sb = StringBuilder(body.length + 512)
        sb.append("{\"Version\":3,\"Meta\":{")
        sb.append("\"Duration\":").append(fmt(if (duration > 0.001f) duration else 0.5f))
        sb.append(",\"Fps\":").append(fmt(if (fps > 0f) fps else 60f))
        sb.append(",\"Loop\":").append(loop)
        sb.append(",\"AreBeziersRestricted\":true")
        sb.append(",\"CurveCount\":").append(curveCount)
        sb.append(",\"UserDataCount\":0,\"TotalUserDataSize\":0")
        sb.append(",\"TotalSegmentCount\":").append(segmentSlots)
        sb.append(",\"TotalPointCount\":").append(pointCount)
        sb.append("},\"Curves\":[").append(body).append("]}")
        return sb.toString()
    }

    fun writeTo(file: File) {
        val json = build() ?: error("动作曲线为空")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    // ------------------------------------------------------------------ 内部

    private fun appendCurveHead(id: String) {
        if (curveCount > 0) body.append(',')
        body.append("{\"Target\":\"Parameter\",\"Id\":\"").append(escape(id))
            .append("\",\"FadeInTime\":-1,\"FadeOutTime\":-1,\"Segments\":[")
        curveCount++
    }

    private fun finishCurve() {
        body.append("]}")
    }

    /**
     * 待机动作首尾闭合：最后 [SEAM_WINDOW_SEC] 内把值从「原始曲线」平滑拉到「首帧值」。
     *
     * 权重用 smoothstep（`3p²-2p³`），在窗口起点权重 0（与原曲线 C0/C1 连续）、
     * 终点权重 1（值精确等于首帧值）；再补一个 `t = duration` 的收尾点，
     * 运行时把 startTime 重置回 0 时参数不发生跳变。
     */
    private fun seamClose(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 2) return points
        val total = if (duration > 0.001f) duration else points.last().first
        val window = minOf(SEAM_WINDOW_SEC, total * 0.25f)
        if (window <= 0.01f) return points
        val seamStart = total - window
        val firstValue = points.first().second
        val out = ArrayList<Pair<Float, Float>>(points.size + 2)
        for ((t, v) in points) {
            if (t <= seamStart) {
                out += t to v
                continue
            }
            val p = ((t - seamStart) / window).coerceIn(0f, 1f)
            val w = p * p * (3f - 2f * p)      // smoothstep
            if (out.isNotEmpty() && t <= out.last().first) {
                out[out.size - 1] = out.last().first to (v + (firstValue - v) * w)
            } else {
                out += t to (v + (firstValue - v) * w)
            }
        }
        // 收尾点：值精确等于首帧值，保证 t→duration 与 t→0 无差
        if (out.last().first < total - 1e-4f) out += total to firstValue
        else out[out.size - 1] = out.last().first to firstValue
        return out
    }

    /**
     * 时间去重（同帧多值取后者）+ 单调化 + 连续同值压缩（保留首尾）。
     * streamed 片段在同一时间点可能重复记录同一曲线，直接保留会让 Cubism 的
     * 二分查找退化成随机取值。
     */
    private fun sanitize(times: FloatArray, values: FloatArray): List<Pair<Float, Float>> {
        val sorted = ArrayList<Pair<Float, Float>>(times.size)
        for (i in times.indices) {
            val t = times[i]
            val v = values[i]
            if (!t.isFinite() || !v.isFinite()) continue
            val clamped = if (t > 0f) t else 0f
            if (sorted.isNotEmpty() && clamped <= sorted.last().first) {
                sorted[sorted.size - 1] = sorted.last().first to v
            } else {
                sorted += clamped to v
            }
        }
        if (sorted.size <= 2) return sorted
        val out = ArrayList<Pair<Float, Float>>(sorted.size)
        for (i in sorted.indices) {
            val cur = sorted[i]
            if (i > 0 && i < sorted.size - 1) {
                val prev = out.last()
                val next = sorted[i + 1]
                if (kotlin.math.abs(cur.second - prev.second) < 1e-4f &&
                    kotlin.math.abs(next.second - cur.second) < 1e-4f
                ) {
                    continue
                }
            }
            out += cur
        }
        return out
    }

    /** 定点格式化（4 位小数，去掉尾随 0）：避免 `1.0E-5` 这类科学计数法进 JSON */
    private fun fmt(value: Float): String {
        val scaled = Math.round(value.toDouble() * 10000.0)
        if (scaled == 0L) return "0"
        val neg = scaled < 0
        val abs = kotlin.math.abs(scaled)
        val intPart = abs / 10000
        val frac = abs % 10000
        val sb = StringBuilder(12)
        if (neg) sb.append('-')
        sb.append(intPart)
        if (frac != 0L) {
            var s = frac.toString()
            while (s.length < 4) s = "0$s"
            s = s.trimEnd('0')
            sb.append('.').append(s)
        }
        return sb.toString()
    }

    private fun escape(text: String): String =
        if (text.indexOf('"') < 0 && text.indexOf('\\') < 0) text else text.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** 碧蓝航线动作片段名 → 查看器动作组（与语音场景表 key 保持一字不差） */
internal object Live2dMotionGroups {

    /** 待机组名（运行时按名字大小写不敏感匹配；此处统一小写） */
    const val IDLE = "idle"

    /** 单独成组的语义动作（顺序即查看器展示顺序的优先级） */
    private val NAMED = listOf(
        "touch_head", "touch_body", "touch_special", "touch_drag",
        "main_1", "main_2", "main_3", "login", "home", "mail",
        "mission_complete", "mission", "complete", "effect", "wedding"
    )

    fun of(clipName: String): String {
        // touch_idle 与 idle 都是待机环：游戏里按权重随机播放，合并成 idle 组
        if (clipName == IDLE || clipName.startsWith("idle") || clipName.startsWith("touch_idle")) {
            return IDLE
        }
        for (g in NAMED) {
            // 前缀匹配即可覆盖 touch_drag12 / main_1 / idle5 这类带序号的名字；
            // NAMED 的顺序保证 mission_complete 先于 mission 命中
            if (clipName.startsWith(g)) return g
        }
        return clipName.substringBefore('_').ifBlank { "idle" }
    }
}
