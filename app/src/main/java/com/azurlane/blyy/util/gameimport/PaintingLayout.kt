package com.azurlane.blyy.util.gameimport

import android.util.Log
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityValues

/**
 * 立绘 prefab（`painting/<名字>`，**不带 `_tex`**）的图层布局解析。
 *
 * 【游戏是怎么画出一张立绘的】
 * `painting/<名字>.ys` 里没有像素，它是一棵 UI 预制体树：
 * ```
 * 2B_2                        RectTransform 4604×2708（画布）
 * ├─ pifu / 2B_2              背景层   MeshImage → Sprite(2048×1206) @ painting/2b_2_tex
 * ├─ layers / 2B_2_rw         人物层   MeshImage → Sprite(1730×2048) + Mesh @ painting/2b_2_rw_tex
 * ├─ face                     表情槽（paintingface 里的差分表情贴上来）
 * └─ …
 * ```
 * 每个 `MeshImage` 组件持有 `m_Sprite`（贴图）与 `mMesh`（图集拼装网格）两个
 * **外部指针**，运行时按各自的 RectTransform（锚点/尺寸/位置/轴心/缩放）叠到画布上。
 * 所以「完整立绘 = 各图层按 prefab 布局叠起来」，缺了布局信息就只能拿到单独一层。
 *
 * 本类只做**几何**：把 prefab 树解析成 [Layer]（画面坐标 + 声明尺寸 + 引用 id），
 * 像素还原交给 [PaintingComposer]。坐标换算按 ALPA（AzurLanePaintingAnalysis-Kt）
 * 的 ExtendedTransform 语义逐条移植：
 * - `size = (锚点跨度 × 父尺寸 + sizeDelta) × 自身缩放`
 * - `pivotPosition = 锚点min × 父尺寸 + 锚点跨度 × 父尺寸 × pivot + 父原点 + 锚点位置`
 * - `origin = pivotPosition − pivot × size`（矩形左下角）
 * 其中「累积缩放」沿用 ALPA 的定义（取祖父链上的累积值，不乘父节点自身缩放），
 * 因为碧蓝的立绘 prefab 只有根节点带 0.53 这类缩放，根节点缩放不参与布局，
 * 换成常规矩阵乘法会让所有图层整体缩小一半。
 */
internal object PaintingLayout {

    private const val TAG = "PaintingLayout"

    /** prefab 层级深度上限（防御异常/损坏的树导致栈溢出） */
    private const val MAX_DEPTH = 64

    /** 输出尺寸与主图层声明尺寸相差不超过该像素数时直接吸附，避免莫名多出 1px */
    private const val CANVAS_SNAP = 2

    /** 画布像素上限（布局异常时的兜底，正常立绘远小于此值） */
    private const val MAX_RENDER_PIXELS = 36_000_000.0

    data class Vec(val x: Float, val y: Float) {
        operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)
        operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)
        operator fun times(other: Vec) = Vec(x * other.x, y * other.y)
        operator fun times(value: Float) = Vec(x * value, y * value)
    }

    /**
     * 一个可叠放的图层。
     *
     * @param spritePathId `m_Sprite.m_PathID`（0 = 空槽，如表情位）
     * @param meshPathId   `mMesh.m_PathID`（0 = 整张图直接用）
     * @param rawWidth/rawHeight `mRawSpriteSize`：贴图原始像素尺寸（0 = 未声明）
     * @param pasteX/pasteY 图层左下角在画布中的位置（UI 单位，y 向上）
     */
    data class Layer(
        val name: String,
        val spritePathId: Long,
        val meshPathId: Long,
        val rawWidth: Float,
        val rawHeight: Float,
        val pasteX: Float,
        val pasteY: Float,
        val width: Float,
        val height: Float
    ) {
        val area: Float get() = width * height
    }

    /**
     * 输出帧：像素/UI 单位的缩放、画布尺寸、以及裁剪原点（UI 坐标）。
     *
     * [left] / [bottom] 是全部图层包围盒的左下角；绘制时
     * `x = (pasteX - left) * scale`、`y = height - (pasteY - bottom) * scale - drawHeight`。
     */
    data class Frame(val scale: Float, val width: Int, val height: Int, val left: Float, val bottom: Float)

    class Plan(
        val rootName: String,
        val layers: List<Layer>,
        val canvasWidth: Int,
        val canvasHeight: Int
    ) {
        val left: Float = layers.minOf { it.pasteX }
        val bottom: Float = layers.minOf { it.pasteY }
        val right: Float = layers.maxOf { it.pasteX + it.width }
        val top: Float = layers.maxOf { it.pasteY + it.height }

        /**
         * 「原生尺寸」输出帧：画布宽 = 主图层声明的贴图宽度，其余图层按布局比例缩放。
         *
         * 主图层 = 与 prefab 根节点同名的那个（碧蓝的惯例：根节点就是背景/整图），
         * 没有同名图层时退化为面积最大的图层。
         */
        fun frame(): Frame {
            val rawWidth = maxOf(1f, right - left)
            val rawHeight = maxOf(1f, top - bottom)
            val primary = layers.firstOrNull { it.name == rootName }
                ?: layers.maxByOrNull { it.area }
            val scale = when {
                primary != null && primary.width > 0f && canvasWidth > 0 ->
                    canvasWidth / primary.width
                else -> 1f
            }
            var width = Math.round(rawWidth * scale).coerceAtLeast(1)
            var height = Math.round(rawHeight * scale).coerceAtLeast(1)
            if (canvasWidth > 0 && Math.abs(width - canvasWidth) <= CANVAS_SNAP) width = canvasWidth
            if (canvasHeight > 0 && Math.abs(height - canvasHeight) <= CANVAS_SNAP) height = canvasHeight
            var fixedScale = scale
            val pixels = width.toDouble() * height
            if (pixels > MAX_RENDER_PIXELS) {
                val k = Math.sqrt(MAX_RENDER_PIXELS / pixels)
                fixedScale *= k.toFloat()
                width = Math.round(width * k).toInt().coerceAtLeast(1)
                height = Math.round(height * k).toInt().coerceAtLeast(1)
            }
            return Frame(fixedScale, width, height, left, bottom)
        }
    }

    /** 图层在输出画布上的像素矩形（左上角原点，y 向下） */
    data class Placement(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * 计算单个图层的落点。
     *
     * 尺寸先按「布局矩形 × 帧缩放」换算，再吸附回贴图声明尺寸（`mRawSpriteSize`）——
     * prefab 的 RectTransform 与贴图尺寸常差零点几像素，不吸附会让整层被重采样一遍；
     * 只有真正需要缩放的图层（如 `_rw` 人物层 1730 → 770）才会重采样。
     *
     * @param outScale 输出画布相对「原生帧」的额外缩放（受最长边上限约束）
     */
    fun place(layer: Layer, frame: Frame, outScale: Float = 1f): Placement {
        var width = Math.round(layer.width * frame.scale)
        var height = Math.round(layer.height * frame.scale)
        val rawWidth = Math.round(layer.rawWidth)
        val rawHeight = Math.round(layer.rawHeight)
        if (rawWidth > 0 && Math.abs(width - rawWidth) <= CANVAS_SNAP) width = rawWidth
        if (rawHeight > 0 && Math.abs(height - rawHeight) <= CANVAS_SNAP) height = rawHeight
        width = Math.round(width * outScale).coerceAtLeast(1)
        height = Math.round(height * outScale).coerceAtLeast(1)

        val canvasHeight = Math.round(frame.height * outScale)
        val x = Math.round((layer.pasteX - frame.left) * frame.scale * outScale)
        val bottom = Math.round((layer.pasteY - frame.bottom) * frame.scale * outScale)
        return Placement(x, canvasHeight - bottom - height, width, height)
    }

    /**
     * 解析 prefab 的图层树；不是 prefab（没有 GameObject）或没有可叠放图层时返回 null。
     *
     * 只读 `GameObject / Transform / RectTransform / MonoBehaviour` 四类对象，
     * 因此对 `_tex` 包（只有贴图+Sprite+Mesh）会安全地返回 null。
     */
    fun parse(sf: SerializedFile): Plan? = runCatching { parseOrThrow(sf) }
        .onFailure { Log.w(TAG, "立绘布局解析失败", it) }
        .getOrNull()

    private fun parseOrThrow(sf: SerializedFile): Plan? {
        val gameObjects = sf.objectsOfClass(SerializedFile.CLASS_GAME_OBJECT)
        if (gameObjects.isEmpty()) return null

        val transforms = LinkedHashMap<Long, TransformValues>()
        sf.objectsOfClass(SerializedFile.CLASS_TRANSFORM).forEach { def ->
            readTransform(sf, def, isRect = false)?.let { transforms[def.pathID] = it }
        }
        sf.objectsOfClass(SerializedFile.CLASS_RECT_TRANSFORM).forEach { def ->
            readTransform(sf, def, isRect = true)?.let { transforms[def.pathID] = it }
        }
        if (transforms.isEmpty()) return null

        val names = HashMap<Long, String>(gameObjects.size)
        gameObjects.forEach { def ->
            runCatching {
                names[def.pathID] = sf.readValues(def)["m_Name"] as? String ?: ""
            }
        }

        // 根节点：优先用 AssetBundle 容器里登记的那个预制体；
        // 容器读不出来时退回「没有父节点的 Transform」（prefab 的唯一根）
        val containerId = findRootGameObject(sf, names)
        val rootTransformId = transforms.entries.firstOrNull { it.value.gameObjectPathId == containerId }?.key
            ?: transforms.entries.firstOrNull { it.value.fatherPathId == 0L }?.key
            ?: return null
        val rootName = names[transforms[rootTransformId]?.gameObjectPathId ?: 0L] ?: ""

        val layers = ArrayList<Layer>()
        val visited = HashSet<Long>()

        fun walk(transformId: Long, parent: Node?, depth: Int) {
            if (depth > MAX_DEPTH) return
            val values = transforms[transformId] ?: return
            if (!visited.add(transformId)) return
            val node = Node(values, parent)
            val gameObjectId = values.gameObjectPathId
            val name = names[gameObjectId] ?: ""
            if (!isOverlayLayer(name)) {
                readMeshImage(sf, gameObjectId)?.let { image ->
                    if (image.spritePathId != 0L) {
                        layers += Layer(
                            name = name,
                            spritePathId = image.spritePathId,
                            meshPathId = image.meshPathId,
                            rawWidth = image.rawWidth,
                            rawHeight = image.rawHeight,
                            pasteX = node.origin.x,
                            pasteY = node.origin.y,
                            width = node.size.x,
                            height = node.size.y
                        )
                    }
                }
            }
            values.children.forEach { childId -> walk(childId, node, depth + 1) }
        }

        walk(rootTransformId, null, 0)
        if (layers.isEmpty()) return null

        val primary = layers.firstOrNull { it.name == rootName } ?: layers.maxByOrNull { it.area }
        val canvasWidth = Math.round(primary?.rawWidth ?: 0f)
        val canvasHeight = Math.round(primary?.rawHeight ?: 0f)
        val plan = Plan(rootName, layers, canvasWidth, canvasHeight)
        Log.i(
            TAG,
            "布局就绪：${layers.size} 层，画布 ${canvasWidth}x$canvasHeight，根=$rootName"
        )
        return plan
    }

    // ------------------------------------------------------------------ 内部

    private class MeshImage(
        val spritePathId: Long,
        val meshPathId: Long,
        val rawWidth: Float,
        val rawHeight: Float
    )

    /** 只保留布局需要的字段，避免把整棵类型树留在内存里 */
    private class TransformValues(
        val gameObjectPathId: Long,
        val fatherPathId: Long,
        val children: List<Long>,
        val isRect: Boolean,
        val anchorMin: Vec,
        val anchorMax: Vec,
        val anchoredPosition: Vec,
        val sizeDelta: Vec,
        val pivot: Vec,
        val localScale: Vec,
        val localPosition: Vec
    )

    private fun readTransform(
        sf: SerializedFile,
        def: SerializedFile.ObjectDef,
        isRect: Boolean
    ): TransformValues? =
        runCatching {
            val values = sf.readValues(def)
            TransformValues(
                gameObjectPathId = pptrPathId(values["m_GameObject"]),
                fatherPathId = pptrPathId(values["m_Father"]),
                children = (values["m_Children"] as? List<*>).orEmpty()
                    .map { pptrPathId(it) }
                    .filter { it != 0L },
                isRect = isRect,
                anchorMin = vec(values["m_AnchorMin"]),
                anchorMax = vec(values["m_AnchorMax"]),
                anchoredPosition = vec(values["m_AnchoredPosition"]),
                sizeDelta = vec(values["m_SizeDelta"]),
                pivot = vec(values["m_Pivot"]),
                localScale = vec(values["m_LocalScale"], 1f),
                localPosition = vec(values["m_LocalPosition"])
            )
        }.getOrNull()

    private fun readMeshImage(sf: SerializedFile, gameObjectId: Long): MeshImage? {
        if (gameObjectId == 0L) return null
        val goDef = sf.objectsOfClass(SerializedFile.CLASS_GAME_OBJECT)
            .firstOrNull { it.pathID == gameObjectId } ?: return null
        val components = runCatching {
            (sf.readValues(goDef)["m_Component"] as? List<*>).orEmpty()
        }.getOrDefault(emptyList())

        val monoById = sf.objectsOfClass(SerializedFile.CLASS_MONO_BEHAVIOUR).associateBy { it.pathID }
        for (entry in components) {
            val componentId = pptrPathId((entry as? Map<*, *>)?.get("component"))
            val def = monoById[componentId] ?: continue
            val values = runCatching { sf.readValues(def) }.getOrNull() ?: continue
            if (!values.containsKey("m_Sprite")) continue
            val raw = values["mRawSpriteSize"] as? Map<*, *>
            return MeshImage(
                spritePathId = pptrPathId(values["m_Sprite"]),
                meshPathId = pptrPathId(values["mMesh"]),
                rawWidth = UnityValues.float(raw?.get("x")) ?: 0f,
                rawHeight = UnityValues.float(raw?.get("y")) ?: 0f
            )
        }
        return null
    }

    /** 优先取 AssetBundle 容器里的那个预制体；退回层级树里唯一的根 */
    private fun findRootGameObject(sf: SerializedFile, names: Map<Long, String>): Long {
        val container = sf.objectsOfClass(SerializedFile.CLASS_ASSET_BUNDLE).firstOrNull()
        if (container != null) {
            runCatching {
                val values = sf.readValues(container)
                val entries = values["m_Container"] as? List<*> ?: return@runCatching
                for (entry in entries) {
                    val pair = entry as? Map<*, *> ?: continue
                    val ptr = pair["second"] as? Map<*, *> ?: continue
                    val id = pptrPathId(ptr)
                    if (id != 0L && names.containsKey(id)) return id
                }
            }
        }
        return 0L
    }

    private fun pptrPathId(raw: Any?): Long {
        val map = raw as? Map<*, *> ?: return 0L
        return UnityValues.number(map["m_PathID"]) ?: 0L
    }

    /**
     * 商店贴纸 / 和谐遮挡层：游戏只在商店、建造预览里才叠上去，
     * 正常立绘界面不显示（ALPA 默认也跳过）。按 GameObject 名判定。
     */
    private fun isOverlayLayer(name: String): Boolean {
        val lower = name.lowercase()
        if (lower == "shadow" || lower == "shop_hx") return true
        return lower.startsWith("shop_hx") || lower.endsWith("_shophx") || lower.endsWith("_buildhx")
    }

    private fun vec(raw: Any?, default: Float = 0f): Vec {
        val map = raw as? Map<*, *> ?: return Vec(default, default)
        return Vec(
            UnityValues.float(map["x"]) ?: default,
            UnityValues.float(map["y"]) ?: default
        )
    }

    /** ALPA `ExtendedTransform` 的逐条移植 */
    private class Node(val values: TransformValues, val parent: Node?) {

        /** 是否 RectTransform（只有 UI 节点才有锚点/轴心/尺寸） */
        val isRect: Boolean get() = values.isRect

        /** 本地缩放：根节点的缩放不参与布局（否则整张图被 0.53 缩掉一半） */
        val localScale: Vec = if (parent == null) Vec(1f, 1f) else values.localScale

        /** ALPA 的「累积缩放」= 祖父链上的值（不含父节点自身缩放） */
        val cumulative: Vec = parent?.cumulative ?: Vec(1f, 1f)

        val overall: Vec = localScale * cumulative

        val unscaledSize: Vec = when {
            !values.isRect -> Vec(0f, 0f)
            parent == null -> values.sizeDelta
            else -> (values.anchorMax - values.anchorMin) * parent.size + values.sizeDelta
        }

        val size: Vec = unscaledSize * overall

        val pivotPosition: Vec = when {
            parent == null -> Vec(0f, 0f)
            // 矩形套矩形：按锚点/轴心换算到父节点的原点
            isRect && parent.isRect -> {
                val anchors = values.anchorMax - values.anchorMin
                anchors * parent.size * values.pivot +
                    values.anchorMin * parent.size +
                    parent.origin +
                    values.anchoredPosition * cumulative
            }
            values.isRect -> parent.pivotPosition + values.anchoredPosition * cumulative
            else -> parent.pivotPosition + values.localPosition * cumulative
        }

        val origin: Vec = when {
            parent == null -> Vec(0f, 0f)
            !values.isRect -> pivotPosition
            else -> pivotPosition - values.pivot * size
        }
    }
}
