package com.azurlane.blyy.util

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 舰娘头像本地资源解析器
 *
 * 将舰娘名称映射到打包在 APK assets 中的本地高清头像，优先使用本地图片，
 * 匹配不到时由调用方回退到网络 URL。
 *
 * 资源约定：
 * - 头像文件位于 `app/src/main/assets/blhx_avatar/`，这是 Android 标准的 assets
 *   子目录，无需额外 build.gradle.kts 配置即可随 APK 打包。
 * - 支持的图片扩展名：`.png` / `.jpg` / `.jpeg` / `.webp`（不区分大小写）。
 * - 文件名 = 舰娘名称的**无声调全拼** + 可选皮肤后缀 + 扩展名
 *   （例如 `boge.webp` 对应"博格"，`z23_h.webp` 对应"Z23"的誓约婚皮）。
 * - 皮肤后缀约定：`_g` = 改造，`_h` = 誓约婚皮，`_2`/`_3` = 换装等。
 * - 新增舰娘头像只需将图片放入上述目录，重新构建即可自动匹配。
 *
 * 匹配策略：
 * 1. 中文名 → [PinyinHelper.toPinyin] 转换为无声调全拼后与文件名（小写）匹配
 * 2. 去除 `.改` / `·META` / `(μ兵装)` / `II` 等后缀后再次拼音匹配
 * 3. 誓约状态下优先匹配 `_h` 婚皮变体，无婚皮则回退默认头像
 *
 * 性能策略：
 * - 首次调用时通过 `AssetManager.list("blhx_avatar")` 构建文件名索引，O(n) 一次性构建。
 * - 后续查询为 O(1) HashMap 查找。
 * - 索引在进程生命周期内缓存（头像随 APK 打包，运行期不变）。
 */
object LocalAvatarResolver {

    private const val TAG = "LocalAvatarResolver"
    private const val ASSET_URI_SCHEME = "file"
    private const val AVATAR_DIR = "blhx_avatar"
    private const val ASSET_PATH_PREFIX = "/android_asset/$AVATAR_DIR/"

    /** 支持的图片扩展名（小写，按匹配优先级排列） */
    private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")

    /** 誓约婚皮文件名后缀 */
    private const val OATH_SKIN_SUFFIX = "_h"

    /** 改造形态文件名后缀，如 changchun_g.webp ← "长春.改" */
    private const val REMODEL_SKIN_SUFFIX = "_g"

    /** META 舰娘（余烬）头像文件名后缀，如 dafeng_alter.webp ← "大凤·META" */
    private const val ALTER_SKIN_SUFFIX = "_alter"

    /** μ兵装舰娘头像文件名后缀，如 chicheng_idol.webp ← "赤城(μ兵装)" */
    private const val IDOL_SKIN_SUFFIX = "_idol"

    /** 幼女/小船形态舰娘头像文件名后缀，如 chicheng_younv.webp ← "小赤城" */
    private const val YOUNV_SKIN_SUFFIX = "_younv"

    /**
     * 手动映射表：舰娘中文名 → assets 头像文件名（不含扩展名）。
     *
     * 用于文件名拼写与舰娘拼音差异较大、模糊匹配无法覆盖的情况：
     * - 日文舰名转拼音后拼写不同（如 "滨风"→bangfeng 而非 binfeng）
     * - 联动舰娘使用英文/日文读音命名（如 "贝露"→peineiluopo）
     * - 资源文件取舰名部分字符命名（如 "葛兹·冯·伯利欣根"→gezi）
     * - 纯数字/英文舰名（如 "22"→22、"2B"→2b）
     */
    private val MANUAL_MAP: Map<String, String> = mapOf(
        "滨风" to "bangfeng",
        "库珀" to "kubo",
        "贝露" to "peineiluopo",
        "哈里森" to "molisen",
        "第二代" to "erdaimu",
        "双海亚美" to "yamei",
        "八舞耶倶矢·八舞夕弦" to "bawu",
        "卡菈·伊迪亚斯" to "kala",
        "莉拉·德西亚斯" to "lila",
        "艾菈·冯·杜勒" to "aila",
        "乌戈里诺·维瓦尔迪" to "linuo",
        "葛兹·冯·伯利欣根" to "gezi",
        "新条茜" to "qian",
        "三浦梓" to "zi",
        "22" to "22",
        "33" to "33",
        "2B" to "2b",
        "苏维埃同盟" to "suweiaitongmengnew",
        "拉·加利索尼埃" to "jialisuoniye",
        "拉·加利索尼埃·META" to "jialisuoniye_alter",
	    "特装型布里MKIII" to "buli_super",
        "试作型布里MKII" to "kin",
        "泛用型布里" to "gin",
        "玛丽·西莱斯特号" to "mali",

        "威廉·D·波特" to "bote",

        "小腓特烈" to "feiteliedadi_younv",
        "小斯佩" to "speibojue_younv",
        "小贝法" to "beifashen_younv",

        "朝凪" to "zhaozhi",
        "酒匂" to "jiuyun",
        "雫" to "na_doa",

        "曾克海军上将" to "zengkehaijunshangjiang",
        "BLACK★ROCK" to "heiyansheshou",
        "天城CV" to "tiancheng_cv",
        // META 特殊命名（mid 前缀，区别于普通赤城）
        "赤城·META" to "midchicheng_alter"
    )

    /**
     * 进程级缓存：文件名（不含扩展名，小写） → assets 中的完整文件名（含扩展名，原始大小写）
     *
     * 使用小写键以支持大小写不敏感匹配（如文件 `Z23.webp` 的键为 `z23`，
     * 舰娘 "Z23" 的拼音 `z23` 可直接匹配）。
     */
    @Volatile
    private var avatarFiles: Map<String, String>? = null

    private val lock = Any()

    /**
     * 解析舰娘头像，返回本地 assets URI。
     *
     * 匹配优先级：
     * 0. 特殊变体优先匹配（META→`_alter`、μ兵装→`_idol`、小前缀→`_younv`），
     *    避免去除后缀后错误回退到默认头像
     * 1. 原始舰娘名小写精确匹配（覆盖文件名直接用中文/英文命名的情况）
     * 2. 拼音全拼匹配（"博格" → "boge" == 文件名 "boge"）
     * 3. 去后缀变体的拼音匹配（去除 `.改` / `·META` / `(μ兵装)` / `II` 等）
     *
     * @param context 任意 Context（内部使用 ApplicationContext 避免泄漏）
     * @param shipName 舰娘名称（与 biligame wiki 图鉴列表一致）
     * @return 本地头像 URI（如 `file:///android_asset/blhx_avatar/boge.webp`），匹配不到返回 null
     */
    fun resolve(context: Context, shipName: String): String? {
        if (shipName.isBlank()) return null

        val index = ensureIndex(context)
        if (index.isEmpty()) return null

        // -1. 手动映射表优先匹配（文件名拼写与拼音差异大的舰娘）
        MANUAL_MAP[shipName]?.let { mappedName ->
            index[mappedName.lowercase()]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> manual map: $it")
                return buildAssetUri(it)
            }
        }

        // 0. 特殊变体优先匹配（META/μ兵装/幼女），避免错误回退到默认头像
        resolveSpecialVariant(context, shipName)?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> special variant: $it")
            return it
        }

        // 0.5 改造形态优先匹配：舰娘名含 ".改" → 优先匹配 _g 后缀文件
        if (shipName.contains(".改") || shipName.contains("改")) {
            val baseName = shipName.replace(".改", "").replace("改", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(context, baseName, REMODEL_SKIN_SUFFIX)?.let {
                    Log.i(TAG, "[MATCH] '$shipName' -> remodel variant: $it")
                    return it
                }
            }
        }

        // 0.7 尾部标记变体优先匹配：舰娘名尾部带 CV/DOA/JP 等英文标记 → 匹配 `拼音_标记` 文件
        // 避免 "天城CV" 错误回退到默认天城 tiancheng，"霞(DOA)" 错配重樱霞 xia
        resolveTailMarkVariant(context, shipName)?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> tail mark variant: $it")
            return it
        }

        // 1. 原始舰娘名小写精确匹配
        index[shipName.lowercase()]?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> raw lowercase: $it")
            return buildAssetUri(it)
        }

        // 2. 拼音全拼匹配
        val pinyin = PinyinHelper.toPinyin(shipName)
        if (pinyin.isNotEmpty()) {
            index[pinyin]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> pinyin($pinyin): $it")
                return buildAssetUri(it)
            }
        }

        // 3. 去后缀变体的原始名 / 拼音匹配
        for (variant in buildVariants(shipName)) {
            index[variant.lowercase()]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> variant($variant) raw: $it")
                return buildAssetUri(it)
            }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index[variantPinyin]?.let {
                    Log.i(TAG, "[MATCH] '$shipName' -> variant($variant) pinyin($variantPinyin): $it")
                    return buildAssetUri(it)
                }
            }
        }

        // 4. 包含匹配兜底
        if (pinyin.isNotEmpty()) {
            resolveContainingVariant(index, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> containing($pinyin): $it")
                return buildAssetUri(it)
            }
        }

        // 5. 反向包含匹配
        if (pinyin.isNotEmpty()) {
            resolveReverseContaining(index, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> reverse containing($pinyin): $it")
                return buildAssetUri(it)
            }
        }

        // 6. 模糊子串匹配
        if (pinyin.isNotEmpty()) {
            resolveFuzzySubstring(index, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> fuzzy substring($pinyin): $it")
                return buildAssetUri(it)
            }
        }

        Log.w(TAG, "[MISS] '$shipName' (pinyin=$pinyin)")
        return null
    }

    /**
     * 解析舰娘头像（区分档案类型，避免同名不同类型舰娘误匹配）。
     *
     * 匹配策略：
     * 1. 优先按 `shipName@archiveType` 小写精确匹配
     * 2. 仅当 archiveType 为 "DOCK" 时，回退到不区分类型的 [resolve]（兼容现有 assets 命名，
     *    assets 中的文件默认就是 DOCK 类型）
     * 3. 对于其他类型（如 "STUDENT"），不回退，返回 null（学生档案有不同立绘，
     *    不应复用 DOCK 头像，避免同名误匹配）
     *
     * @param archiveType 档案类型（"DOCK" 或 "STUDENT"），null/空字符串表示不区分
     */
    fun resolve(context: Context, shipName: String, archiveType: String?): String? {
        if (shipName.isBlank()) return null
        if (archiveType.isNullOrBlank()) return resolve(context, shipName)

        val index = ensureIndex(context)
        if (index.isEmpty()) return null

        // 1. 优先按 "舰娘名@档案类型" 小写精确匹配（避免同名误匹配）
        val typeKey = "${shipName}@$archiveType".lowercase()
        index[typeKey]?.let { return buildAssetUri(it) }

        // 2. 仅 DOCK 类型回退到不区分类型的匹配（assets 默认就是 DOCK 头像）
        // STUDENT 等其他类型不回退，避免学生档案错误显示同名 DOCK 舰娘的本地头像
        if (archiveType == "DOCK") {
            return resolve(context, shipName)
        }

        return null
    }

    /**
     * 解析舰娘头像（区分档案类型 + 誓约状态）。
     *
     * 誓约状态下优先匹配 `_h` 婚皮变体：
     * - 先尝试 `<拼音>_h` 文件（如 `boge_h.webp`）
     * - 若婚皮不存在，回退到默认头像（[resolve]）
     * - 若默认头像也不存在，返回 null（由调用方回退到网络 URL）
     *
     * @param archiveType 档案类型（"DOCK" 或 "STUDENT"），null/空字符串表示不区分
     * @param isOathed 是否已誓约（true 时优先匹配 `_h` 婚皮）
     */
    fun resolve(context: Context, shipName: String, archiveType: String?, isOathed: Boolean): String? {
        if (!isOathed) return resolve(context, shipName, archiveType)

        // 誓约状态：仅 DOCK 类型尝试婚皮（STUDENT 无婚皮概念）
        if (archiveType != null && archiveType != "DOCK") {
            return resolve(context, shipName, archiveType)
        }

        // 特殊变体优先（META/μ兵装/幼女无婚皮，誓约后仍显示特殊变体头像）
        resolveSpecialVariant(context, shipName)?.let { return it }

        // 改造形态优先（改造舰誓约后仍应显示改造头像 _g，而非基础婚皮 _h）
        if (shipName.contains(".改") || shipName.contains("改")) {
            val baseName = shipName.replace(".改", "").replace("改", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(context, baseName, REMODEL_SKIN_SUFFIX)?.let { return it }
            }
        }

        // 尝试婚皮变体
        resolveOathVariant(context, shipName)?.let { return it }

        // 婚皮不存在，回退默认头像
        return resolve(context, shipName, archiveType)
    }

    /**
     * 返回有效的头像 URI：优先本地，回退网络 URL。
     * 便于调用方一行完成 "本地优先 → 网络兜底" 逻辑。
     */
    fun resolveOrDefault(context: Context, shipName: String, networkUrl: String): String {
        return resolve(context, shipName) ?: networkUrl
    }

    /**
     * 返回有效的头像 URI（区分档案类型）：优先本地，回退网络 URL。
     */
    fun resolveOrDefault(context: Context, shipName: String, archiveType: String?, networkUrl: String): String {
        return resolve(context, shipName, archiveType) ?: networkUrl
    }

    /**
     * 返回有效的头像 URI（区分档案类型 + 誓约状态）：优先本地婚皮，回退默认/网络 URL。
     *
     * @param isOathed 是否已誓约（true 时优先匹配 `_h` 婚皮）
     */
    fun resolveOrDefault(context: Context, shipName: String, archiveType: String?, isOathed: Boolean, networkUrl: String): String {
        return resolve(context, shipName, archiveType, isOathed) ?: networkUrl
    }

    /** 懒加载构建 assets 文件名索引（线程安全双重检查锁） */
    private fun ensureIndex(context: Context): Map<String, String> {
        avatarFiles?.let { return it }
        synchronized(lock) {
            avatarFiles?.let { return it }
            val index = buildIndex(context.applicationContext)
            avatarFiles = index
            Log.i(TAG, "Local avatar index built: ${index.size} files")
            return index
        }
    }

    /**
     * 扫描 assets/blhx_avatar 目录，构建"文件名(不含扩展名，小写) → 完整文件名(含扩展名)"映射。
     *
     * 使用小写键以支持大小写不敏感匹配。同名不同扩展名时按 [SUPPORTED_EXTENSIONS] 优先级保留最优
     * （例如同时存在 `Z23.png` 和 `Z23.jpg` 时优先保留 `.png`）。
     */
    private fun buildIndex(appContext: Context): Map<String, String> {
        return try {
            val files = appContext.assets.list(AVATAR_DIR) ?: emptyArray()
            val index = HashMap<String, String>(files.size)
            for (file in files) {
                val lower = file.lowercase()
                val ext = SUPPORTED_EXTENSIONS.firstOrNull { lower.endsWith(it) } ?: continue
                val name = file.dropLast(ext.length)
                val key = name.lowercase()
                val extPriority = SUPPORTED_EXTENSIONS.indexOf(ext)
                // 同名文件保留扩展名优先级更高的（数值更小 = 更优先）
                val existing = index[key]
                if (existing == null || extPriority < extensionPriority(existing)) {
                    index[key] = file
                }
            }
            index
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list avatar assets", e)
            emptyMap()
        }
    }

    /** 返回文件扩展名在 SUPPORTED_EXTENSIONS 中的优先级（越小越优先），不支持返回 Int.MAX_VALUE */
    private fun extensionPriority(fileName: String): Int {
        val lower = fileName.lowercase()
        return SUPPORTED_EXTENSIONS.indexOfFirst { lower.endsWith(it) }.let { if (it < 0) Int.MAX_VALUE else it }
    }

    /**
     * 尝试匹配誓约婚皮变体（`_h` 后缀）。
     *
     * 匹配策略与 [resolve] 一致，但在拼音后追加 `_h` 后缀：
     * 1. 原始舰娘名 + `_h` 小写匹配
     * 2. 拼音 + `_h` 匹配
     * 3. 去后缀变体的原始名/拼音 + `_h` 匹配
     *
     * @return 婚皮头像 URI，不存在返回 null
     */
    private fun resolveOathVariant(context: Context, shipName: String): String? {
        val index = ensureIndex(context)
        if (index.isEmpty()) return null

        // 0. MANUAL_MAP 优先：舰娘名在手动映射表中时，用映射值 + _h
        // 典型案例："威廉·D·波特" → bote_h、"苏维埃同盟" → suweiaitongmengnew_h
        MANUAL_MAP[shipName]?.let { mapped ->
            index["$mapped$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }
        }

        // 1. 原始舰娘名 + _h
        index["${shipName.lowercase()}$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }

        // 2. 拼音 + _h
        val pinyin = PinyinHelper.toPinyin(shipName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }
        }

        // 3. 去后缀变体 + _h
        for (variant in buildVariants(shipName)) {
            MANUAL_MAP[variant]?.let { mapped ->
                index["$mapped$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }
            }
            index["${variant.lowercase()}$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$OATH_SKIN_SUFFIX"]?.let { return buildAssetUri(it) }
            }
        }

        return null
    }

    /**
     * 特殊变体优先匹配：根据舰娘名的特殊标记，映射到对应的头像文件后缀。
     *
     * 映射规则（参考 D:\Android\Project\win 项目舰娘匹配方法）：
     * - 舰娘名含 `·META` / `.META` → 匹配 `_alter` 后缀文件（如 "大凤·META" → `dafeng_alter`）
     * - 舰娘名含 `(μ兵装)` / `μ兵装` → 匹配 `_idol` 后缀文件（如 "赤城(μ兵装)" → `chicheng_idol`）
     * - 舰娘名以 `小` 开头（幼女/小船形态）→ 匹配 `_younv` 后缀文件（如 "小赤城" → `chicheng_younv`）
     *
     * 优先于 [buildVariants] 的去除后缀逻辑执行，避免 META/μ兵装/幼女舰娘
     * 在去除标记后错误回退到基础舰娘的默认头像。
     *
     * @return 特殊变体头像 URI，不匹配返回 null
     */
    private fun resolveSpecialVariant(context: Context, shipName: String): String? {
        // META 舰娘（余烬）：舰娘名含 "·META" → 匹配 _alter 后缀
        if (shipName.contains("·META") || shipName.contains(".META")) {
            val baseName = shipName.replace("·META", "").replace(".META", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(context, baseName, ALTER_SKIN_SUFFIX)?.let { return it }
            }
        }

        // μ兵装舰娘：舰娘名含 "(μ兵装)" → 匹配 _idol 后缀
        if (shipName.contains("(μ兵装)") || shipName.contains("μ兵装")) {
            val baseName = shipName.replace("(μ兵装)", "").replace("μ兵装", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(context, baseName, IDOL_SKIN_SUFFIX)?.let { return it }
            }
        }

        // 幼女/小船形态：舰娘名以 "小" 开头 → 匹配 _younv 后缀
        // 注意：仅当"小"后还有内容时才处理，避免误匹配"小"字单独成名的舰娘
        if (shipName.startsWith("小") && shipName.length > 1) {
            val baseName = shipName.removePrefix("小").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(context, baseName, YOUNV_SKIN_SUFFIX)?.let { return it }
            }
        }

        return null
    }

    /**
     * 尾部标记变体优先匹配：舰娘名尾部带英文/数字标记，资源文件用 `拼音_标记小写` 命名。
     *
     * 典型案例（实际文件名验证）：
     * - "天城CV" → tiancheng_cv（CV 联动天城，区别于默认天城 tiancheng）
     * - "霞(DOA)" / "霞DOA" → xia_doa（DOA 联动霞，区别于重樱霞 xia）
     * - "新月JP" → xinyue_jp
     *
     * 优先于拼音精确匹配执行，避免带标记舰娘错误回退到同名默认舰娘。
     *
     * @return 尾部标记变体头像 URI，不匹配返回 null
     */
    private fun resolveTailMarkVariant(context: Context, shipName: String): String? {
        val mark = extractTailMark(shipName) ?: return null
        // 去除尾部标记（含可选括号）："霞(DOA)" → "霞"、"天城CV" → "天城"
        val tailPattern = Regex("[(（]?${Regex.escape(mark)}[)）]?$")
        val baseName = shipName.replaceFirst(tailPattern, "").trim()
        if (baseName.isBlank() || baseName == shipName) return null

        val index = ensureIndex(context)
        if (index.isEmpty()) return null
        val markSuffix = "_${mark.lowercase()}"

        // 1. MANUAL_MAP 基础名 + _标记
        MANUAL_MAP[baseName]?.let { mapped ->
            index["$mapped$markSuffix"]?.let { return buildAssetUri(it) }
        }

        // 2. 基础名拼音 + _标记
        val pinyin = PinyinHelper.toPinyin(baseName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$markSuffix"]?.let { return buildAssetUri(it) }
        }

        // 3. 变体拼音 + _标记
        for (variant in buildVariants(baseName)) {
            MANUAL_MAP[variant]?.let { mapped ->
                index["$mapped$markSuffix"]?.let { return buildAssetUri(it) }
            }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$markSuffix"]?.let { return buildAssetUri(it) }
            }
        }

        return null
    }

    /**
     * 提取舰娘名尾部的英文标记（2-6 个字母，可带数字）。
     *
     * 支持两种形式：
     * - 括号形式："霞(DOA)" / "霞（DOA）" → "DOA"
     * - 直接拼接形式（标记前必须有非英文字符，避免吞掉纯英文名）："天城CV" → "CV"、"霞DOA" → "DOA"
     *
     * 返回 null 的情况：
     * - 纯英文数字舰名（如 "Z23"、"HDN101"）—— 整体是舰名而非标记
     * - 尾部无英文标记（如 "博格"、"滨风.改"）
     */
    private fun extractTailMark(shipName: String): String? {
        // 1. 括号形式："霞(DOA)" / "霞（DOA）"
        Regex("[(（]([A-Za-z]{2,6}[0-9]*)[)）]$").find(shipName)?.let {
            return it.groupValues[1]
        }
        // 2. 直接拼接形式：英文尾部前必须有中文/·/. 等非英文字符，
        // 避免 "Z23"、"HDN101" 这类纯英文数字舰名被误拆
        val m = Regex("^(.*?[\\u4e00-\\u9fff·.])([A-Za-z]{2,6}[0-9]*)$").find(shipName)
        return m?.groupValues?.get(2)
    }

    /**
     * 用指定后缀在索引中查找头像文件。
     *
     * 匹配策略与 [resolve] 一致，但在拼音/变体后追加指定的皮肤后缀：
     * 1. 原始名 + 后缀 小写匹配
     * 2. 拼音 + 后缀 匹配
     * 3. 去后缀变体的原始名/拼音 + 后缀 匹配
     *
     * @param baseName 去除特殊标记后的基础舰娘名（如 "大凤" / "赤城"）
     * @param suffix 文件名后缀（如 `_alter` / `_idol` / `_younv`）
     * @return 匹配到的头像 URI，不存在返回 null
     */
    private fun resolveWithSuffix(context: Context, baseName: String, suffix: String): String? {
        val index = ensureIndex(context)
        if (index.isEmpty()) return null

        // 0. MANUAL_MAP 优先：基础名在手动映射表中时，用映射值 + 后缀
        // 解决拼音转换与实际文件名拼写不一致的改造/META/μ兵装/幼女舰娘匹配
        // 典型案例："滨风.改" → baseName="滨风" → MANUAL_MAP["滨风"]="bangfeng" → "bangfeng_g"
        MANUAL_MAP[baseName]?.let { mappedName ->
            index["$mappedName$suffix"]?.let { return buildAssetUri(it) }
        }

        // 1. 原始名 + 后缀
        index["${baseName.lowercase()}$suffix"]?.let { return buildAssetUri(it) }

        // 2. 拼音 + 后缀
        val pinyin = PinyinHelper.toPinyin(baseName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$suffix"]?.let { return buildAssetUri(it) }
        }

        // 3. 去后缀变体 + 后缀
        for (variant in buildVariants(baseName)) {
            MANUAL_MAP[variant]?.let { mappedName ->
                index["$mappedName$suffix"]?.let { return buildAssetUri(it) }
            }
            index["${variant.lowercase()}$suffix"]?.let { return buildAssetUri(it) }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$suffix"]?.let { return buildAssetUri(it) }
            }
        }

        // 4. 模糊子串匹配兜底：文件名核心部分是拼音的子串 + 后缀
        // 覆盖 "朱利奥·凯撒·META" → kaisa_alter（kaisa 是 zhuliaokaisa 的子串）
        if (pinyin.isNotEmpty() && pinyin.length >= 5) {
            val bestMatch = index.entries
                .filter { (key, _) ->
                    key.endsWith(suffix) &&
                    key.length > suffix.length + 3 &&
                    pinyin.contains(key.removeSuffix(suffix))
                }
                .maxByOrNull { it.key.length }
            bestMatch?.let { return buildAssetUri(it.value) }
        }

        return null
    }

    /**
     * 包含匹配兜底：查找以舰娘拼音开头且带下划线后缀的文件。
     *
     * 当标准匹配策略全部失败时启用，覆盖联动皮肤等非标准后缀命名：
     * - `suixiang_doa` ← "穗香"（拼音 `suixiang` + `_doa` 后缀）
     * - `lala_tolove` ← "拉拉"（拼音 `lala` + `_tolove` 后缀）
     * - `gaoxiong_dark` ← "高雄"（拼音 `gaoxiong` + `_dark` 后缀）
     *
     * 匹配规则：文件名（小写）以 `<拼音>_` 开头，且拼音长度 ≥ 3（避免过短前缀误匹配）。
     * 若有多个匹配，优先选择无后缀的（即精确匹配），其次选第一个匹配项。
     *
     * @param index 文件名索引
     * @param pinyin 舰娘名的无声调全拼
     * @return 匹配到的文件名（含扩展名），无匹配返回 null
     */
    private fun resolveContainingVariant(index: Map<String, String>, pinyin: String): String? {
        if (pinyin.length < 3) return null
        val prefix = "${pinyin}_"
        return index.entries
            .firstOrNull { (key, _) ->
                // 排除皮肤后缀文件（_h/_g/_alter/_数字等），
                // 默认形态不应误匹配改造/婚皮/换装文件
                // 典型案例：舰娘"霞"不应匹配 xia_g（改造）/ xia_alter（META）
                key.startsWith(prefix) && !isSkinSuffix(key.substringAfter('_').lowercase())
            }
            ?.value
    }

    /**
     * 反向包含匹配：查找是拼音前缀的文件（assets 缩短命名兜底）。
     *
     * 当 [resolveContainingVariant]（文件名以拼音开头）也失败时启用，处理**反向**情况：
     * assets 文件使用缩短命名，文件名是舰娘拼音的**前缀**。
     *
     * 典型案例（长名舰娘缩短命名）：
     * - `abuluqi` ← "阿布鲁齐公爵"（pinyin=abuluqigongjue）
     * - `wuerlixi` ← "乌尔里希·冯·胡滕"（pinyin=wuerlixifenghuteng）
     * - `ougen` ← "欧根亲王"（pinyin=ougenqinwang）
     * - `yin diannabolisi` ← "印第安纳波利斯"（pinyin=yindiannabolisi）
     *
     * 匹配规则：
     * - 文件名（小写）长度 >= 5（避免过短前缀误匹配）
     * - 拼音以文件名开头（文件名是拼音的前缀）
     * - 多个匹配时选最长（最精确）的文件名
     *
     * @param index 文件名索引
     * @param pinyin 舰娘名的无声调全拼
     * @return 匹配到的文件名（含扩展名），无匹配返回 null
     */
    private fun resolveReverseContaining(index: Map<String, String>, pinyin: String): String? {
        if (pinyin.length < 5) return null
        return index.entries
            .mapNotNull { (key, value) ->
                // 1. 文件名无下划线：直接检查文件名是否为拼音前缀
                // 阈值 >= 4 支持 gezi/aila/kala/lila/bawu 等短文件名
                if (!key.contains('_')) {
                    if (key.length >= 4 && pinyin.startsWith(key)) {
                        key to value
                    } else null
                } else {
                    // 2. 文件名含下划线（如 nana_tolove）：取下划线前部分检查
                    // 覆盖联动舰娘的缩短命名（如 nana ← "娜娜·阿丝达·戴比路克"）
                    // 排除已知皮肤后缀文件（_h/_g/_y/_alter/_idol/_younv/_hx/_R/_数字），
                    // 避免长名舰娘误匹配到婚皮/改造等皮肤文件
                    // 典型案例：wuerlixi_h（婚皮）不应被 "乌尔里希·冯·胡滕" 反向包含匹配
                    val suffix = key.substringAfter('_').lowercase()
                    if (isSkinSuffix(suffix)) return@mapNotNull null
                    val pre = key.substringBefore('_')
                    // 阈值 >= 4 避免过短前缀误匹配（如 xia_DOA 误匹配"小"系列舰娘）
                    if (pre.length >= 4 && pinyin.startsWith(pre)) {
                        pre to value
                    } else null
                }
            }
            .maxByOrNull { it.first.length }
            ?.second
    }

    /**
     * 判断下划线后的部分是否为已知皮肤后缀。
     *
     * 用于 [resolveReverseContaining] 和 [resolveFuzzySubstring] 等模糊匹配时
     * 排除皮肤文件，避免长名舰娘误匹配到婚皮/改造/META/μ兵装/幼女/换装等皮肤。
     *
     * 皮肤后缀清单：
     * - `_h` 誓约婚皮、`_g` 改造、`_y` 特约、`_hx` 幻象、`_R` 镜像
     * - `_alter` META、`_idol` μ兵装、`_younv` 幼女、`_super` 布里
     * - `_2`/`_3`/`_4`/`_5` 换装编号
     * - `_new` 新版资源（部分舰娘文件名带 new 后缀，由 [MANUAL_MAP] 单独处理）
     */
    private fun isSkinSuffix(suffix: String): Boolean {
        if (suffix.isEmpty()) return false
        // 纯数字（换装编号 _2/_3/_12 等）
        if (suffix.all { it.isDigit() }) return true
        return when (suffix) {
            "h", "g", "y", "r", "hx", "alter", "idol", "younv", "super" -> true
            else -> false
        }
    }

    /**
     * 模糊子串匹配：文件名是舰娘拼音的任意位置连续子串（最后兜底策略）。
     *
     * 当所有精确匹配、包含匹配、反向包含匹配都失败后启用，处理：
     * - 联动舰娘文件名取舰娘名中部分字符（如 qinli ← "五河琴里" pinyin=wuheqinli）
     * - 资源文件命名取舰娘名核心字（如 zaoshen ← "女灶神" pinyin=nvzaoshen，但已由反向包含处理）
     *
     * 匹配规则：
     * - 仅处理无下划线的纯拼音文件名（带后缀的 _alter/_idol 等已有专门策略）
     * - 文件名长度 >= 4（避免过短文件名造成大面积误匹配）
     * - 文件名是拼音的连续子串（pinyin.contains(fileName)）
     * - 相似度阈值：文件名长度 / 拼音长度 >= 0.4（确保文件名足够显著，避免偶然子串匹配）
     *   特例：文件名长度 >= 6 时跳过比例检查（长文件名本身已足够特异）
     * - 多候选取最长文件名（最精确）
     *
     * @param index 文件名索引（key=无扩展名小写，value=实际文件名）
     * @param pinyin 舰娘名的无声调全拼
     * @return 匹配到的文件名（含扩展名），无匹配返回 null
     */
    private fun resolveFuzzySubstring(index: Map<String, String>, pinyin: String): String? {
        if (pinyin.length < 5) return null
        return index.entries
            .filter { (key, _) ->
                // 仅处理无下划线的纯拼音文件名
                !key.contains('_') &&
                key.length >= 4 &&
                // 文件名是拼音的连续子串
                pinyin.contains(key) &&
                // 相似度阈值：长文件名(>=6)直接通过，短文件名需比例>=0.4
                (key.length >= 6 || key.length.toFloat() / pinyin.length >= 0.4f)
            }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * 生成舰娘名称的变体列表，用于模糊匹配。
     *
     * 变体生成规则按优先级排列：
     * - 去除 `.改` → 去除 `改` → 去除 `Kai`
     * - 去除 `·META`
     * - 去除 `(μ兵装)`
     * - 去除 `II` 后缀
     * - 提取英文/数字前缀（如 "Z1莉泽洛特" → "Z1"，"伊13十纱" → "I13"）
     * - 组合去除（如 `.改` + `·META`）
     */
    private fun buildVariants(shipName: String): List<String> {
        val variants = LinkedHashSet<String>()

        // 单一后缀去除
        val noRemodel = when {
            shipName.contains(".改") -> shipName.replace(".改", "")
            shipName.contains("改") -> shipName.replace("改", "")
            shipName.contains("Kai") -> shipName.replace("Kai", "")
            else -> null
        }
        noRemodel?.let { variants.add(it) }

        if (shipName.contains("·META")) {
            variants.add(shipName.replace("·META", ""))
        }
        if (shipName.contains("(μ兵装)")) {
            variants.add(shipName.replace("(μ兵装)", ""))
        }
        if (shipName.endsWith("II")) {
            variants.add(shipName.removeSuffix("II"))
        }

        // 去除联动后缀（DOA/SSSS/BB/CV/DE/JP 等），生成基础舰娘名变体
        // 联动皮肤资源文件命名格式为 "<拼音>_<联动后缀小写>"（如 xia_doa ← "霞DOA"）
        // 去除后缀后通过包含匹配 `文件.startsWith("拼音_")` 即可匹配
        val collabSuffixes = listOf("DOA", "SSSS", "BB", "CV", "DE", "JP")
        for (suffix in collabSuffixes) {
            if (shipName.endsWith(suffix)) {
                val base = shipName.removeSuffix(suffix).trim()
                if (base.isNotBlank()) variants.add(base)
            }
        }

        // 提取英文/数字前缀：舰娘名以字母+数字开头后跟中文（如 "Z1莉泽洛特" → "Z1"）
        // 头像文件名通常只用前缀部分（z1.webp / z1_g.webp）
        extractAlphanumericPrefix(shipName)?.let { variants.add(it) }

        // "伊" 前缀转 "I"：伊系列潜艇舰娘名以 "伊" 开头后跟数字（如 "伊13十纱" → "I13"）
        // 头像文件名用 I+数字 命名（I13.webp / I168.webp）
        if (shipName.startsWith("伊")) {
            val rest = shipName.removePrefix("伊")
            extractAlphanumericPrefix("I$rest")?.let { variants.add(it) }
        }

        // 组合去除：改造 + META
        if (shipName.contains(".改") && shipName.contains("·META")) {
            variants.add(shipName.replace(".改", "").replace("·META", ""))
        }

        return variants.toList()
    }

    /**
     * 从舰娘名中提取开头的英文+数字前缀。
     *
     * 匹配模式：以 1 个或多个英文字母开头，后跟 0 个或多个数字，再后跟中文字符。
     * 提取前缀部分（字母+数字），用于匹配以编号命名的头像文件。
     *
     * 例：
     *   "Z1莉泽洛特" → "Z1"
     *   "I13十纱" → "I13"
     *   "HDN101" → null（纯英文+数字无中文，无需提取）
     *   "博格" → null（不以英文字母开头）
     */
    private fun extractAlphanumericPrefix(shipName: String): String? {
        // 1. 字母+数字后跟中文（如 "Z1莉泽洛特" → "Z1"）
        val regex = Regex("^([A-Za-z]+[0-9]*)[\\u4e00-\\u9fff]")
        val match = regex.find(shipName)
        if (match != null) {
            val prefix = match.groupValues[1]
            // 前缀必须包含至少1个字母（纯数字不算）
            return if (prefix.any { it.isLetter() }) prefix else null
        }
        // 2. 纯字母+数字无中文后缀（如 "I13" ← "伊13" 转换后，"Z1" ← "Z1"）
        val pureRegex = Regex("^([A-Za-z]+[0-9]+)$")
        val pureMatch = pureRegex.find(shipName)
        if (pureMatch != null) {
            return pureMatch.groupValues[1]
        }
        return null
    }

    /**
     * 构建 assets URI。
     *
     * 使用 Uri.Builder 构造，确保特殊字符（★、·、(、)、μ 等）正确处理。
     * Coil 的 AssetUriFetcher 会从 uri.path 中提取 /android_asset/ 之后的部分
     * 作为 asset 路径传给 AssetManager.open()，因此路径不能被 percent-encode。
     *
     * @param fileName assets 中的完整文件名（含扩展名），如 "boge.webp"
     */
    private fun buildAssetUri(fileName: String): String {
        // 直接拼接字符串而非 Uri.encode，因为：
        // 1. 舰娘文件名不含 / ? # % & + 等需要 encode 的字符
        // 2. AssetManager.open() 需要原始文件名，percent-encoding 会导致找不到文件
        // 3. Android Uri.parse 对 path 中的非 ASCII 字符（★、·、中文字符）保持原样
        val path = "$ASSET_PATH_PREFIX$fileName"
        return Uri.Builder()
            .scheme(ASSET_URI_SCHEME)
            .path(path)
            .build()
            .toString()
    }
}
