package com.azurlane.blyy.util.unity

import android.util.Log

/**
 * 资源包内的「绑定路径索引」：把动作曲线的 `path` hash 反查成 moc3 参数 id。
 *
 * Live2D 的 Unity 版把每个参数做成一个 GameObject（挂在根节点的 `Parameters` 下，
 * 名字就是 moc3 参数 id，如 `ParamAngleX`），Mecanim 动作曲线只记录**完整路径的
 * 哈希**；因此需要先把 `GameObject` / `Transform` 的层级还原成路径，再按
 * [AnimatorHash]（Animator.StringToHash）建哈希索引。
 *
 * 路径写法随动画根节点不同：可能是 `根/Parameters/ParamAngleX`，也可能是动画根
 * 之下的相对路径 `Parameters/ParamAngleX`。这里对每层后缀都建索引，
 * 命中即用（CRC32 撞库概率可忽略）。
 */
internal class Live2dRigIndex(
    /** hash → 参数 id */
    private val hashToParam: Map<Long, String>,
    /** 资源包里出现的全部参数 id（`Parameters` 子树下的 GameObject 名） */
    val parameterNames: Set<String>
) {

    /** 绑定 → moc3 参数 id（未知绑定返回 null） */
    fun paramOf(binding: AnimationClipDecoder.Binding): String? = hashToParam[binding.pathHash]

    /** 眨眼组参数：优先标准名，退化到形如 `ParamEyeLOpen` 的名字 */
    fun eyeBlinkIds(): List<String> {
        val exact = listOf("ParamEyeLOpen", "ParamEyeROpen").filter { it in parameterNames }
        if (exact.size == 2) return exact
        val loose = parameterNames.filter { Regex("""^ParamEye[LR]Open\d*$""").matches(it) }.sorted()
        return if (loose.size >= 2) loose.take(2) else emptyList()
    }

    /** 口型组参数：优先标准名，退化到形如 `ParamMouthOpenY` 的名字 */
    fun lipSyncIds(): List<String> {
        if ("ParamMouthOpenY" in parameterNames) return listOf("ParamMouthOpenY")
        val loose = parameterNames.filter { Regex("""^ParamMouthOpen\w*$""").matches(it) }.sorted()
        return loose.take(1)
    }

    companion object {
        private const val TAG = "Live2dRigIndex"

        /** GameObject / Transform 类 ID */
        private const val CLASS_GAME_OBJECT = 1
        private const val CLASS_TRANSFORM = 4

        private const val MAX_DEPTH = 64

        fun build(sf: SerializedFile): Live2dRigIndex {
            val names = HashMap<Long, String>(4096)
            for (def in sf.objectsOfClass(CLASS_GAME_OBJECT)) {
                val values = runCatching { sf.readValues(def) }.getOrNull() ?: continue
                val name = values["m_Name"] as? String ?: continue
                names[def.pathID] = name
            }

            // transform pathId → (父 transform, 所属 GameObject)
            val parents = HashMap<Long, Long>(4096)
            val owners = HashMap<Long, Long>(4096)
            for (def in sf.objectsOfClass(CLASS_TRANSFORM)) {
                val values = runCatching { sf.readValues(def) }.getOrNull() ?: continue
                parents[def.pathID] = pptrPathId(values["m_Father"])
                owners[def.pathID] = pptrPathId(values["m_GameObject"])
            }

            val hashToParam = HashMap<Long, String>(8192)
            val paramNames = LinkedHashSet<String>(1024)
            val pathCache = HashMap<Long, String>(4096)

            fun pathOf(transformId: Long): String {
                pathCache[transformId]?.let { return it }
                val segments = ArrayList<String>(8)
                var cur = transformId
                var guard = 0
                while (cur != 0L && guard < MAX_DEPTH) {
                    val name = owners[cur]?.let { names[it] }
                    if (name == null) break
                    segments += name
                    cur = parents[cur] ?: 0L
                    guard++
                }
                val path = segments.asReversed().joinToString("/")
                pathCache[transformId] = path
                return path
            }

            for (transformId in owners.keys) {
                val path = pathOf(transformId)
                if (path.isEmpty()) continue
                val segments = path.split('/')
                if ("Parameters" in segments) {
                    val paramName = segments.last()
                    if (paramName.isNotBlank()) paramNames += paramName
                }
                // 每层后缀都建索引：动画根可能不在路径起点
                for (k in segments.indices) {
                    val suffix = if (k == 0) path else segments.subList(k, segments.size).joinToString("/")
                    val hash = AnimatorHash.hash(suffix)
                    hashToParam.putIfAbsent(hash, segments.last())
                }
            }

            Log.d(TAG, "绑定路径索引：hash ${hashToParam.size}, 参数 ${paramNames.size}")
            return Live2dRigIndex(hashToParam, paramNames)
        }

        private fun pptrPathId(raw: Any?): Long {
            val map = raw as? Map<*, *> ?: return 0L
            return (map["m_PathID"] as? Number)?.toLong() ?: 0L
        }
    }
}
