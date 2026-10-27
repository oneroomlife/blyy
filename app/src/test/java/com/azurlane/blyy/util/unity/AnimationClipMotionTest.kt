package com.azurlane.blyy.util.unity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 资源包动作转换回归测试（AnimationClip → motion3.json）。
 *
 * 样本放在工程根的 `resource/test/`（不入包，仅本地存在时运行）。
 * 重点守护两个曾经让「动作一条都加载不出来」的契约：
 *  1. 绑定路径 hash 必须能反解成 moc3 参数 id（Animator.StringToHash）。
 *  2. `Meta.TotalSegmentCount/TotalPointCount` 必须与 Cubism 解析器实际消费的
 *     槽位/点数**逐条相等**（常量曲线占一个槽位但不自带段 → 少算就整条动作失败）。
 */
class AnimationClipMotionTest {

    private fun sample(name: String): File {
        val candidates = listOf(
            File("../resource/test/$name"),
            File("../../resource/test/$name"),
            File("resource/test/$name")
        )
        return candidates.firstOrNull { it.isFile } ?: File("__missing__/$name")
    }

    @Test
    fun `animator string to hash matches unity crc32`() {
        // 实测自资源包：GenericBinding.attribute 恒为 3702945584（= CRC32("Value")）
        assertEquals(3702945584L, AnimatorHash.hash("Value"))
        // 大小写敏感（Unity 侧不会自动 lowercase）
        assertTrue(AnimatorHash.hash("value") != AnimatorHash.hash("Value"))
        assertEquals(0L, AnimatorHash.hash(""))
    }

    @Test
    fun `idle motions are seam closed so they can loop in place`() {
        // 接缝闭合是「陪伴模式待机无缝」的数据前提：不闭合的特效/开关参数
        //（TX_xxx / Circle_QTE_xxx / ParamNN）每圈会从 0 直接跳到满量程
        val file = sample("chaijun_6")
        assumeTrue("未找到 resource/test/chaijun_6", file.isFile)
        val builder = Motion3Builder(duration = 2f, fps = 60f, loop = true, loopSeamless = true)
        val plain = Motion3Builder(duration = 2f, fps = 60f, loop = true, loopSeamless = false)

        // 0 → 3 的斜坡（末值远离首值，模拟特效参数）
        val times = FloatArray(21) { it * 0.1f }
        val values = FloatArray(21) { it * 0.15f }
        builder.addCurve("TX_test", times, values)
        plain.addCurve("TX_test", times, values)

        val closed = firstCurvePoints(requireNotNull(builder.build()))
        val raw = firstCurvePoints(requireNotNull(plain.build()))
        assertTrue("闭合后首尾必须相等: ${closed.first()} -> ${closed.last()}",
            kotlin.math.abs(closed.last() - closed.first()) < 1e-3f)
        assertTrue("未闭合时必须保留原始首尾差", kotlin.math.abs(raw.last() - raw.first()) > 1f)
        // 闭合窗口内的过渡必须单调收敛到首值（不能先冲高再回落）
        val seam = closed.takeLast(3)
        assertTrue("接缝窗口应平滑收敛: $seam", seam.zipWithNext().all { (a, b) -> b <= a + 1e-4f })
    }

    @Test
    fun `chaijun_6 clips convert into playable motions`() {
        val file = sample("chaijun_6")
        assumeTrue("未找到 resource/test/chaijun_6", file.isFile)

        val bundle = UnityFsBundle.open(file)
        val imageFile = File.createTempFile("chaijun-motion", ".img")
        val outDir = createTempDir("motions")
        try {
            bundle.decompressTo(imageFile)
            BundleImage(imageFile, bundle.nodes).use { image ->
                val sf = SerializedFile.open(image, bundle.serializedNode()!!)
                val rig = Live2dRigIndex.build(sf)
                assertTrue("参数名索引为空", rig.parameterNames.size > 200)
                assertTrue("眨眼参数应可识别", rig.eyeBlinkIds().size == 2)

                val clips = sf.objectsOfClass(SerializedFile.CLASS_ANIMATION_CLIP)
                assertEquals("动画剪辑数量异常", 113, clips.size)

                var converted = 0
                var noCurve = 0
                var bodyAngleCurves = 0
                val groups = mutableMapOf<String, Int>()
                for (def in clips) {
                    val clip = AnimationClipDecoder.decode(sf.readValues(def)) ?: continue
                    val builder = Motion3Builder(clip.duration, clip.fps, clip.loop)
                    var curves = 0
                    for (ci in clip.bindings.indices) {
                        val id = rig.paramOf(clip.bindings[ci]) ?: continue
                        if (ci < clip.streamedCurveCount) {
                            val points = AnimationClipDecoder.track(clip, ci)
                            if (points.size < 2) {
                                val v = points.firstOrNull()?.second
                                if (v != null && builder.addConstantCurve(id, v)) curves++
                                continue
                            }
                            val times = FloatArray(points.size) { points[it].first }
                            val values = FloatArray(points.size) { points[it].second }
                            if (builder.addCurve(id, times, values)) curves++
                        } else {
                            val v = AnimationClipDecoder.constantOf(clip, ci) ?: continue
                            if (builder.addConstantCurve(id, v)) curves++
                        }
                    }
                    if (curves == 0) {
                        noCurve++
                        continue
                    }
                    val json = builder.build() ?: continue
                    val group = Live2dMotionGroups.of(clip.name)
                    groups[group] = (groups[group] ?: 0) + 1
                    if (clip.name == "touch_head") {
                        bodyAngleCurves = countCurvesWithId(json, "ParamBodyAngleX")
                    }
                    File(outDir, "${clip.name}.motion3.json").writeText(json)
                    converted++
                }

                assertEquals("所有片段都应能转换", 113, converted)
                assertEquals("不应有片段无曲线", 0, noCurve)
                assertEquals("touch_head 应包含 ParamBodyAngleX 曲线", 1, bodyAngleCurves)
                assertTrue("动作分组异常：$groups", (groups["idle"] ?: 0) >= 50)
                for (required in listOf("touch_head", "touch_body", "touch_special", "main_1", "login", "wedding")) {
                    assertTrue("缺少动作组 $required", (groups[required] ?: 0) > 0)
                }

                // 逐条校验 Cubism 解析器契约（这里模拟 parse 的槽位消费规则）
                val json = Json { ignoreUnknownKeys = true }
                for (f in outDir.listFiles().orEmpty()) {
                    val root = json.parseToJsonElement(f.readText()).jsonObject
                    val meta = root["Meta"]!!.jsonObject
                    val curves = root["Curves"]!!.jsonArray
                    assertEquals("CurveCount 不一致: ${f.name}", curves.size, meta["CurveCount"]!!.jsonPrimitive.content.toInt())

                    var slots = 0
                    var points = 0
                    for (curveEl in curves) {
                        val curve = curveEl.jsonObject
                        assertEquals("动作目标必须是 Parameter: ${f.name}", "Parameter", curve["Target"]!!.jsonPrimitive.content)
                        val segs = curve["Segments"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
                        assertTrue("曲线段过短: ${f.name}", segs.size >= 5)
                        assertEquals("曲线首段类型必须是 Linear: ${f.name}", 0f, segs[0])
                        // 与 Cubism 解析器一致：首点 + 每段
                        var cursor = 2
                        var segCount = 0
                        points += 1
                        while (cursor < segs.size) {
                            when (segs[cursor].toInt()) {
                                0, 2, 3 -> {
                                    assertTrue("Linear/Stepped 段越界: ${f.name}", cursor + 3 <= segs.size)
                                    cursor += 3
                                    points += 1
                                }
                                1 -> {
                                    assertTrue("Bezier 段越界: ${f.name}", cursor + 7 <= segs.size)
                                    cursor += 7
                                    points += 3
                                }
                                else -> throw AssertionError("非法段类型 ${segs[cursor]}: ${f.name}")
                            }
                            segCount++
                        }
                        assertEquals("段未完整消费: ${f.name}", segs.size, cursor)
                        slots += maxOf(segCount, 1)
                    }
                    assertEquals(
                        "TotalSegmentCount 与解析器槽位数不一致: ${f.name}",
                        slots,
                        meta["TotalSegmentCount"]!!.jsonPrimitive.content.toInt()
                    )
                    assertEquals(
                        "TotalPointCount 不一致: ${f.name}",
                        points,
                        meta["TotalPointCount"]!!.jsonPrimitive.content.toInt()
                    )
                }
            }
        } finally {
            imageFile.delete()
            outDir.deleteRecursively()
        }
    }

    /** 取第一条曲线的所有取值（起点 + 每段末值） */
    private fun firstCurvePoints(json: String): List<Float> {
        val root = Json.parseToJsonElement(json).jsonObject
        val segs = root["Curves"]!!.jsonArray[0].jsonObject["Segments"]!!.jsonArray
            .map { it.jsonPrimitive.content.toFloat() }
        val out = mutableListOf(segs[1])
        var k = 2
        while (k < segs.size) {
            when (segs[k].toInt()) {
                0, 2, 3 -> { out += segs[k + 2]; k += 3 }
                1 -> { out += segs[k + 6]; k += 7 }
                else -> break
            }
        }
        return out
    }

    private fun countCurvesWithId(json: String, id: String): Int {
        val root = Json.parseToJsonElement(json).jsonObject
        return root["Curves"]!!.jsonArray.count {
            it.jsonObject["Id"]?.jsonPrimitive?.content == id
        }
    }
}
