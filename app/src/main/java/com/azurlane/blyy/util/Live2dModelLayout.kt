package com.azurlane.blyy.util

/**
 * Live2D 模型目录布局的纯逻辑（无 Android / 无 IO 依赖 → JVM 单测可完整覆盖）。
 *
 * 导入侧（[Live2dImporter]）与扫描侧（[Live2dLibrary]）共用同一套命名与归属规则，
 * 避免"导入时判成功、扫描时却认不出"这类两边口径不一致的隐性 bug。
 */
object Live2dModelLayout {

    const val MODEL3_EXT = ".model3.json"

    /** 目录名非法字符（路径分隔符 + Windows 保留符） */
    private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")

    /** 控制字符（含 0x00），文件系统不接受；用 code 判定，避免 raw string 里的 \u 转义歧义 */
    private fun stripControl(raw: String): String = buildString(raw.length) {
        for (c in raw) if (c.code >= 0x20 && c.code != 0x7F) append(c)
    }

    /** 相对路径最后一段 */
    fun baseName(relPath: String): String = relPath.substringAfterLast('/')

    /**
     * `haitun_2.model3.json` → `haitun_2`（大小写不敏感）。
     *
     * 注意不能用 [java.io.File.nameWithoutExtension]：它只剥掉最后一个扩展名，
     * 会得到 `haitun_2.model3`，与目录名永远对不上。
     */
    fun stripModel3Ext(fileName: String): String =
        if (fileName.endsWith(MODEL3_EXT, ignoreCase = true)) {
            fileName.substring(0, fileName.length - MODEL3_EXT.length)
        } else {
            fileName
        }

    /**
     * 目录名净化：非法字符换成 `_`，去控制字符，剔除 `.` / `..`，最长 64 字符。
     * 返回空串表示"没有可用的名字"。
     */
    fun sanitizeFolderName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val cleaned = stripControl(ILLEGAL_CHARS.replace(trimmed, "_")).trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return ""
        return cleaned.take(64).trim()
    }

    /**
     * 推导模型 id（= 库内目录名）。
     *
     * 优先级：
     * 1. 模型根目录名（`xxx/haitun_2/haitun_2.model3.json` → `haitun_2`）
     * 2. **model3.json 文件名去扩展名** —— 模型文件直接躺在导入根顶层时
     *    （用户直接选中模型文件夹本身 / zip 内模型就在包根），模型根相对路径是空串，
     *    旧实现只取根目录名 → 空 → 整模型被判「目录名无效」直接丢弃，
     *    表现为「手动导入后库里什么都没有」。
     * 3. 导入源名称（SAF 目录显示名 / zip 文件名），同上为空时兜底
     */
    fun modelIdFor(rootRel: String, model3Rel: String, fallback: String? = null): String {
        sanitizeFolderName(baseName(rootRel)).let { if (it.isNotEmpty()) return it }
        sanitizeFolderName(stripModel3Ext(baseName(model3Rel))).let { if (it.isNotEmpty()) return it }
        return sanitizeFolderName(fallback)
    }

    /**
     * 文件归属的模型根：取"能匹配的最长根"。
     *
     * 空根表示**导入根本身就是模型目录**，此时导入根下的一切（含 `textures/`、
     * `motions/` 等子目录）都属于该模型。旧实现要求空根只匹配"不含 '/' 的路径"，
     * 于是选中模型文件夹本身导入时贴图/动作全部被丢弃，即使目录名侥幸合法，
     * 落地后的模型也必然缺贴图 → 扫描判定为坏模型而不显示。
     */
    fun ownerRoot(relPath: String, roots: List<String>): String? {
        var best: String? = null
        for (root in roots) {
            val matched = if (root.isEmpty()) true else relPath == root || relPath.startsWith("$root/")
            if (matched && (best == null || root.length > best!!.length)) best = root
        }
        return best
    }

    /**
     * zip 条目名归一化：反斜杠 → `/`，去掉前导 `/` 与 `./`。
     * 返回 null 表示该条目应跳过（空名或含 `..` 的穿越路径）。
     */
    fun normalizeEntryPath(name: String): String? {
        val stripped = name.replace('\\', '/').trimStart('/').removePrefix("./").trim()
        if (stripped.isEmpty()) return null
        if (stripped.split('/').any { it == ".." }) return null
        return stripped
    }
}
