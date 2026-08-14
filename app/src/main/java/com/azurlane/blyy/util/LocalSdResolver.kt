package com.azurlane.blyy.util

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * SD 小人资源信息。
 *
 * @param dirPath 资源目录绝对路径（如 /sdcard/Download/BLYY/blhx_sd/boge/default）
 * @param assetName 三件套主名（如 boge 或 boge_g），.skel 文件名为 $assetName.skel
 * @param skins 该舰娘可用的皮肤名列表（如 ["default", "gai", "skin2"]）
 */
data class SdAssetInfo(
    val dirPath: String,
    val assetName: String,
    val skins: List<String> = emptyList()
)

/**
 * 舰娘 SD 小人资源解析器（外部存储版）。
 *
 * 从用户导入到手机的 Spine 三件套资源中，按舰娘中文名匹配对应目录，返回资源绝对路径
 * 供 [com.azurlane.blyy.ui.components.SpineSdView] 加载。
 *
 * v2 起主路径改为应用专属外部存储 `Android/data/<pkg>/files/blhx_sd/`（免权限），
 * 由 [SdResourceOrganizer] 从用户通过 SAF 选择的源目录整理而来。
 * 兼容老版本的公共 Download 目录 `Download/BLYY/blhx_sd/`（需旧版 MANAGE_EXTERNAL_STORAGE 权限）。
 *
 * 支持两种目录结构:
 *
 * **新结构（v2，organize_sd.py 生成，推荐）:**
 * ```
 * blhx_sd/
 * ├── boge/                       # 舰娘拼音主名
 * │   ├── default/                # 皮肤子目录
 * │   │   ├── boge.skel
 * │   │   ├── boge.atlas
 * │   │   └── boge.png
 * │   ├── gai/                    # 改造皮肤
 * │   │   ├── boge_g.skel
 * │   │   ├── boge_g.atlas        # 无独立 atlas 时由脚本从 default 复制并改写
 * │   │   └── boge_g.png
 * │   └── skin2/
 * │       └── ...
 * └── manifest.json
 * ```
 *
 * **旧结构（v1，向后兼容）:**
 * ```
 * blhx_sd/
 * ├── boge/                       # 所有文件平铺
 * │   ├── boge.skel
 * │   ├── boge.atlas
 * │   ├── boge.png
 * │   └── boge_g.skel
 * └── manifest.json
 * ```
 *
 * 匹配策略（按优先级）:
 * 1. [MANUAL_MAP] 手动映射表精确匹配（覆盖拼音转换不准确的情况）
 * 2. [PinyinHelper.toPinyin] 中文名→无声调全拼转换后匹配目录名
 * 3. 去除 `.改`/`·META`/`(μ兵装)` 等后缀后再次拼音转换匹配
 *
 * 性能策略:
 * - 首次调用扫描资源目录构建索引，O(n) 一次性构建
 * - 后续查询 O(1)：拼音转换 + HashSet 查找
 * - 索引在进程生命周期内缓存；调用 [refreshIndex] 可强制重建（导入新资源后）
 */
object LocalSdResolver {

    private const val TAG = "LocalSdResolver"
    private const val SD_ROOT_DIR = "blhx_sd"
    private const val DOWNLOAD_SUBDIR = "BLYY/blhx_sd"
    private const val SKEL_EXT = ".skel"
    private const val ATLAS_EXT = ".atlas"
    private const val PNG_EXT = ".png"

    /** META 舰娘（余烬）SD 资源目录名后缀，如 dafeng_alter/ ← "大凤·META" */
    private const val ALTER_SKIN_SUFFIX = "_alter"

    /** μ兵装舰娘 SD 资源目录名后缀，如 chicheng_idol/ ← "赤城(μ兵装)" */
    private const val IDOL_SKIN_SUFFIX = "_idol"

    /** 幼女/小船形态舰娘 SD 资源目录名后缀，如 chicheng_younv/ ← "小赤城" */
    private const val YOUNV_SKIN_SUFFIX = "_younv"

    /** 改造形态 SD 资源目录名后缀，如 changchun_g/ ← "长春.改" */
    private const val REMODEL_SKIN_SUFFIX = "_g"

    /**
     * 手动映射表：舰娘中文名 → SD 资源拼音名。
     *
     * 用于覆盖自动拼音转换不准确的情况（如多音字、特殊简称）。
     * 大部分舰娘可通过 [PinyinHelper.toPinyin] 自动匹配，无需在此登记。
     */
    private val MANUAL_MAP: Map<String, String> = mapOf(
        "博格" to "boge",
        // 日文舰名拼写差异（与 LocalAvatarResolver.MANUAL_MAP 保持一致）
        "滨风" to "bangfeng",
        "库珀" to "kubo",
        "贝露" to "peineiluopo",
        "哈里森" to "molisen",
        "第二代" to "erdaimu",
        // 联动舰取部分字符命名
        "双海亚美" to "yamei",
        "八舞耶倶矢·八舞夕弦" to "bawu",
        "卡菈·伊迪亚斯" to "kala",
        "莉拉·德西亚斯" to "lila",
        "艾菈·冯·杜勒" to "aila",
        "乌戈里诺·维瓦尔迪" to "linuo",
        "葛兹·冯·伯利欣根" to "gezi",
        "新条茜" to "qian",
        "三浦梓" to "zi",
        // 纯数字/英文舰名
        "22" to "22",
        "33" to "33",
        "2B" to "2b",
        // 文件名带 new 后缀
        "苏维埃同盟" to "suweiaitongmengnew",
        // 去掉"拉·"前缀的法国舰名
        "拉·加利索尼埃" to "jialisuoniye",
        "拉·加利索尼埃·META" to "jialisuoniye_alter",
        // 布里系列
        "试作型布里MKII" to "buli_super",
        "泛用型布里" to "buli_super",
        // "号"结尾去掉
        "玛丽·西莱斯特号" to "mali",
        // 威廉·D·波特 → bote
        "威廉·D·波特" to "bote",
        // 小系列
        "小腓特烈" to "feiteliedadi_younv",
        "小斯佩" to "speibojue_younv",
        "小贝法" to "beifashen_younv",
        // 日文舰名特殊文件名
        "朝凪" to "zhaozhi",
        "酒匂" to "jiuyun",
        "雫" to "na_doa",
        // 资源文件取全称命名
        "曾克海军上将" to "zengkehaijunshangjiang",
        "BLACK★ROCK" to "heiyansheshou",
        // 联动后缀保留命名（CV 联动天城）
        "天城CV" to "tiancheng_cv"
    )

    /** 进程级缓存：资源根目录下所有舰娘目录名（拼音） */
    @Volatile
    private var availableAssets: Set<String>? = null

    /** 进程级缓存：资源根目录 */
    @Volatile
    private var resourceDir: File? = null

    /** 进程级缓存：每个舰娘目录下的皮肤列表 { "boge" -> ["default", "gai"] } */
    @Volatile
    private var skinIndex: Map<String, List<String>>? = null

    /**
     * 资源索引修订号（可观察）。
     *
     * 每次 [refreshIndex] 时递增。Composable 将其作为 `remember` 的 key，
     * 整理/清理资源后能自动失效缓存并重新解析 SD 资源，
     * 避免整理后仍使用旧的 null 结果导致 SD 小人不可动。
     */
    val revision = mutableStateOf(0L)

    private val lock = Any()

    /**
     * 获取 SD 资源根目录。
     *
     * 优先级（v2 起）：
     * 1. **应用专属外部存储** `Android/data/<pkg>/files/blhx_sd/`（免权限，主路径）
     *    由 [SdResourceOrganizer] 整理后的资源存放于此。
     * 2. **公共 Download 目录** `Download/BLYY/blhx_sd/`（兼容旧版，需 MANAGE_EXTERNAL_STORAGE）
     *    老版本用户已导入的资源仍可使用。
     *
     * 选择逻辑：应用专属目录存在且含至少 1 个有效舰娘子目录 → 用它；
     * 否则若公共 Download 目录存在且有效 → 回退到 Download；都没有时返回应用专属目录（用于"未导入"提示）。
     */
    fun getResourceDir(context: Context): File {
        // 1. 应用专属外部存储（免权限，主路径）
        val appDir = File(context.getExternalFilesDir(null), SD_ROOT_DIR)
        if (appDir.exists() && appDir.isDirectory) {
            val hasValidShip = appDir.listFiles()?.any { shipDir ->
                shipDir.isDirectory && hasSkelFiles(shipDir)
            } == true
            if (hasValidShip) return appDir
        }

        // 2. 公共 Download 目录（兼容旧版，需 MANAGE_EXTERNAL_STORAGE 权限）
        // Environment.DIRECTORY_DOWNLOAD 在 compileSdk 36 中不可用，使用等价字符串常量
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory("Download"),
            DOWNLOAD_SUBDIR
        )
        if (downloadDir.exists() && downloadDir.isDirectory) {
            val hasValidShip = downloadDir.listFiles()?.any { shipDir ->
                shipDir.isDirectory && hasSkelFiles(shipDir)
            } == true
            if (hasValidShip) return downloadDir
        }

        // 都没有时返回应用专属目录（用于"未导入资源"提示与整理输出位置）
        return appDir
    }

    /**
     * 检查目录是否包含 .skel 文件（直接或子目录中）。
     * 用于判断舰娘目录是否有效。
     */
    private fun hasSkelFiles(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // 直接检查
        dir.listFiles()?.let { files ->
            if (files.any { it.name.endsWith(SKEL_EXT) }) return true
            // 检查子目录（新结构 <ship>/<skin>/xxx.skel）
            if (files.any { it.isDirectory && hasSkelInDir(it) }) return true
        }
        return false
    }

    private fun hasSkelInDir(dir: File): Boolean =
        dir.listFiles()?.any { it.name.endsWith(SKEL_EXT) } == true

    /**
     * 检查 SD 资源是否可用（资源目录存在且非空）。
     */
    fun isResourceAvailable(context: Context): Boolean {
        val dir = getResourceDir(context)
        return dir.exists() && dir.listFiles()?.any { it.isDirectory } == true
    }

    /**
     * 解析舰娘 SD 资源。
     *
     * 匹配策略（按优先级）:
     * 0. **特殊变体优先匹配**（META→`_alter`、μ兵装→`_idol`、小前缀→`_younv`），
     *    避免去除标记后错误回退到基础舰娘的 SD 资源目录
     * 1. [MANUAL_MAP] 手动映射表精确匹配（覆盖拼音转换不准确的情况）
     * 2. 拼音全拼精确匹配（"博格" → "boge" == 目录 "boge"）
     * 3. 拼音首字母缩写精确匹配（"乌尔里希冯胡滕" → "welxfht" == 目录 "welxfht"）
     * 4. **资源目录名是舰娘全拼的前缀**（舰娘全拼 "wuerlixifenghuteng" 以目录 "wuerlixi" 为前缀）
     *    —— 解决资源文件夹使用缩写/简写命名无法匹配的问题
     * 5. **舰娘全拼是资源目录名的前缀**（目录 "wuerlixifenghuteng" 以舰娘全拼 "wuerlixi" 为前缀）
     *    —— 解决资源文件夹使用完整命名但舰娘名简写的情况
     * 6. 资源目录名是舰娘逐字拼音 segments 的前 N 个 segment 拼接
     * 7. 去除常见后缀后再次执行 1-6 匹配
     *
     * 策略 4-6 为模糊匹配，覆盖用户资源目录命名不规范的情况，
     * 例如"乌尔里希冯胡滕"只能匹配"wuerlixifenghuteng"全拼，
     * 但实际资源文件夹"wuerlixi"通过策略 4 可正确匹配。
     *
     * @param context 任意 Context
     * @param shipName 舰娘中文名（与 biligame wiki 一致）
     * @return SD 资源信息，匹配不到返回 null
     */
    fun resolve(context: Context, shipName: String): SdAssetInfo? {
        if (shipName.isBlank()) return null
        val index = ensureIndex(context) ?: return null
        val dir = resourceDir ?: return null
        if (index.isEmpty()) return null

        // 0. 特殊变体优先匹配（META/μ兵装/幼女），避免错误回退到基础舰娘 SD 资源目录
        resolveSpecialVariant(shipName, index, dir)?.let { return it }

        // 0.5 改造形态优先匹配：舰娘名含 ".改" → 优先匹配 _g 后缀目录
        // 避免改造舰错误显示基础形态 SD（如 "长春.改" 应匹配 changchun_g 而非 changchun）
        if (shipName.contains(".改") || shipName.contains("改")) {
            val baseName = shipName.replace(".改", "").replace("改", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, REMODEL_SKIN_SUFFIX, index, dir)?.let { return it }
            }
        }

        // 尝试原始舰娘名匹配
        resolveByName(shipName, index, dir)?.let { return it }

        // 去除常见后缀后再次匹配（.改 / ·META / (μ兵装) / II 等）
        for (variant in buildNameVariants(shipName)) {
            resolveByName(variant, index, dir)?.let { return it }
        }

        Log.w(TAG, "No SD asset for $shipName")
        return null
    }

    /**
     * 对单个舰娘名执行多策略匹配。
     */
    private fun resolveByName(shipName: String, index: Set<String>, dir: File): SdAssetInfo? {
        // 1. 手动映射表精确匹配
        val manualCandidate = MANUAL_MAP[shipName]
        if (manualCandidate != null && manualCandidate in index) {
            return buildAssetInfo(dir, manualCandidate)
        }

        val fullPinyin = PinyinHelper.toPinyin(shipName)
        if (fullPinyin.isNotEmpty()) {
            // 2. 拼音全拼精确匹配
            if (fullPinyin in index) {
                return buildAssetInfo(dir, fullPinyin)
            }

            // 4. 资源目录名是舰娘全拼的前缀（资源用简写命名）
            // 例：舰娘 "乌尔里希冯胡滕" 全拼 "wuerlixifenghuteng"，
            // 资源目录 "wuerlixi" 是其前缀 → 匹配
            // 要求前缀至少覆盖舰娘全拼的 50%，避免过短前缀误匹配
            val minPrefixLen = (fullPinyin.length * 0.5f).toInt().coerceAtLeast(3)
            val prefixMatch = index.firstOrNull { dirName ->
                dirName.length >= minPrefixLen &&
                    fullPinyin.startsWith(dirName)
            }
            if (prefixMatch != null) {
                Log.i(TAG, "Prefix match: $shipName (pinyin=$fullPinyin) → dir=$prefixMatch")
                return buildAssetInfo(dir, prefixMatch)
            }

            // 5. 舰娘全拼是资源目录名的前缀（资源用完整命名，舰娘名是简称）
            val reversePrefixMatch = index.firstOrNull { dirName ->
                dirName.length >= fullPinyin.length &&
                    dirName.startsWith(fullPinyin)
            }
            if (reversePrefixMatch != null) {
                Log.i(TAG, "Reverse prefix match: $shipName (pinyin=$fullPinyin) → dir=$reversePrefixMatch")
                return buildAssetInfo(dir, reversePrefixMatch)
            }

            // 6. 资源目录名是逐字拼音 segments 的前 N 个拼接
            // 例：舰娘 segments ["wuer","li","xi","feng","hu","ten"]，
            // 资源目录 "wuerlixi" = 前 3 个 segment 拼接 → 匹配
            val segments = PinyinHelper.toPinyinSegments(shipName)
            if (segments.size >= 2) {
                val segMatch = matchBySegments(segments, index)
                if (segMatch != null) {
                    Log.i(TAG, "Segment match: $shipName (segments=$segments) → dir=$segMatch")
                    return buildAssetInfo(dir, segMatch)
                }
            }
        }

        // 3. 拼音首字母缩写精确匹配（覆盖资源目录用首字母缩写命名）
        val initials = PinyinHelper.toPinyinInitials(shipName)
        if (initials.isNotEmpty() && initials.length >= 3 && initials in index) {
            Log.i(TAG, "Initials match: $shipName (initials=$initials) → dir=$initials")
            return buildAssetInfo(dir, initials)
        }

        // 7. 包含匹配兜底：查找以舰娘拼音开头且带下划线后缀的资源目录
        // 覆盖联动皮肤等非标准后缀命名（如 suixiang_doa / lala_tolove）
        if (fullPinyin.isNotEmpty()) {
            val containingMatch = resolveContainingVariant(fullPinyin, index)
            if (containingMatch != null) {
                Log.i(TAG, "Containing match: $shipName (pinyin=$fullPinyin) → dir=$containingMatch")
                return buildAssetInfo(dir, containingMatch)
            }
        }

        // 8. 模糊子串匹配：资源目录名是舰娘拼音的任意位置连续子串（最后兜底）
        // 覆盖联动舰娘等部分命名情况（如 qinli ← "五河琴里" pinyin=wuheqinli）
        if (fullPinyin.isNotEmpty()) {
            val fuzzyMatch = resolveFuzzySubstring(fullPinyin, index)
            if (fuzzyMatch != null) {
                Log.i(TAG, "Fuzzy substring match: $shipName (pinyin=$fullPinyin) → dir=$fuzzyMatch")
                return buildAssetInfo(dir, fuzzyMatch)
            }
        }

        return null
    }

    /**
     * 模糊子串匹配：资源目录名是舰娘拼音的任意位置连续子串（最后兜底策略）。
     *
     * 与 [LocalAvatarResolver.resolveFuzzySubstring] 逻辑一致，处理：
     * - 联动舰娘资源目录取舰娘名中部分字符（如 qinli ← "五河琴里" pinyin=wuheqinli）
     *
     * 匹配规则：
     * - 仅处理无下划线的纯拼音目录名（带后缀的 _alter/_idol 等已有专门策略）
     * - 目录名长度 >= 4（避免过短目录名造成大面积误匹配）
     * - 目录名是拼音的连续子串（pinyin.contains(dirName)）
     * - 相似度阈值：目录名长度 / 拼音长度 >= 0.4（确保目录名足够显著）
     *   特例：目录名长度 >= 6 时跳过比例检查（长目录名本身已足够特异）
     * - 多候选取最长目录名（最精确）
     */
    private fun resolveFuzzySubstring(pinyin: String, index: Set<String>): String? {
        if (pinyin.length < 5) return null
        return index
            .filter { dirName ->
                !dirName.contains('_') &&
                dirName.length >= 4 &&
                pinyin.contains(dirName) &&
                (dirName.length >= 6 || dirName.length.toFloat() / pinyin.length >= 0.4f)
            }
            .maxByOrNull { it.length }
    }

    /**
     * 包含匹配兜底：查找以舰娘拼音开头且带下划线后缀的资源目录。
     *
     * 与 [LocalAvatarResolver.resolveContainingVariant] 逻辑一致，覆盖联动皮肤等
     * 非标准后缀命名的 SD 资源目录（如 `suixiang_doa/` ← "穗香"）。
     */
    private fun resolveContainingVariant(pinyin: String, index: Set<String>): String? {
        if (pinyin.length < 3) return null
        val prefix = "${pinyin}_"
        return index.firstOrNull { it.startsWith(prefix) }
    }

    /**
     * 按逐字拼音 segments 模糊匹配资源目录名。
     *
     * 尝试将前 N 个 segment 拼接（N 从 segments.size 递减到 2），
     * 若拼接结果是某个资源目录名，则匹配成功。
     * 这样可以匹配 "wuerlixi"（前 3 段）作为 "乌尔里希冯胡滕" 的资源目录。
     */
    private fun matchBySegments(segments: List<String>, index: Set<String>): String? {
        for (n in segments.size downTo 2) {
            val combined = segments.take(n).joinToString("")
            if (combined in index) return combined
        }
        return null
    }

    /**
     * 解析舰娘指定皮肤的 SD 资源。
     *
     * @param context 任意 Context
     * @param shipName 舰娘中文名
     * @param skinName 皮肤名（null / "" / "default" 用默认皮肤；
     *                 "gai" / "huan" / "huangxiang" / "doa" / "teyao" / "skin2" 等对应 [scanSkins] 分类）
     * @return 指定皮肤的 SD 资源信息；若皮肤不存在则回退到默认皮肤，仍无则返回 null
     */
    fun resolve(context: Context, shipName: String, skinName: String?): SdAssetInfo? {
        val base = resolve(context, shipName) ?: return null
        if (skinName.isNullOrBlank() || skinName == "default") return base
        if (base.skins.isEmpty() || skinName !in base.skins) return base

        val shipDir = File(base.dirPath).parentFile ?: return base
        // 新结构：皮肤在子目录中
        val skinDir = File(shipDir, skinName)
        val skinSkel = skinDir.listFiles()?.firstOrNull { it.name.endsWith(SKEL_EXT) }
        if (skinSkel != null) {
            return SdAssetInfo(
                dirPath = skinDir.absolutePath,
                assetName = skinSkel.nameWithoutExtension,
                skins = base.skins
            )
        }

        // 旧结构：皮肤文件平铺，按文件名后缀查找
        val suffix = skinNameToFileSuffix(skinName) ?: return base
        val skinAssetName = base.assetName + suffix
        val skelFile = File(base.dirPath, "$skinAssetName$SKEL_EXT")
        if (!skelFile.exists()) {
            Log.w(TAG, "Skin $skinName skel not found: $skelFile, fallback to default")
            return base
        }
        return base.copy(assetName = skinAssetName)
    }

    /**
     * 皮肤分类名 → 文件名后缀（[scanSkins] 的逆映射）。
     * 例："gai" → "_g"，"skin2" → "_2"，"default" → ""。
     */
    private fun skinNameToFileSuffix(skinName: String): String? = when (skinName.lowercase()) {
        "default" -> ""
        "gai" -> "_g"
        "huan" -> "_h"
        "huangxiang" -> "_hx"
        "doa" -> "_doa"
        "teyao" -> "_y"
        "mirror" -> "_R"
        else -> {
            // skin2 / skin3 / ... → _2 / _3
            val num = skinName.removePrefix("skin")
            if (num.isNotEmpty() && num.all { it.isDigit() }) "_$num" else null
        }
    }

    /**
     * 列出指定舰娘的所有可用皮肤。
     *
     * @return 皮肤名列表（如 ["default", "gai", "skin2"]），无资源返回空列表
     */
    fun listSkins(context: Context, shipName: String): List<String> {
        val info = resolve(context, shipName) ?: return emptyList()
        return info.skins
    }

    /**
     * 列出所有可用的 SD 资源舰娘名（拼音）。
     * 用于 SD 资源管理界面展示已导入的资源。
     */
    fun listAllAssets(context: Context): List<String> {
        val index = ensureIndex(context) ?: return emptyList()
        return index.sorted()
    }

    /** 强制重建索引（导入新资源后调用）。递增 revision 以通知 Composable 失效缓存。 */
    fun refreshIndex() {
        synchronized(lock) {
            availableAssets = null
            resourceDir = null
            skinIndex = null
        }
        revision.value++
        Log.i(TAG, "refreshIndex: revision=${revision.value}")
    }

    /** 构建资源信息（含皮肤列表） */
    private fun buildAssetInfo(rootDir: File, assetName: String): SdAssetInfo {
        val shipDir = File(rootDir, assetName)
        val skins = skinIndex?.get(assetName) ?: scanSkins(shipDir, assetName)
        // 确定 dirPath 和 assetName：优先用 default 皮肤子目录（新结构），
        // 回退到舰娘目录平铺（旧结构）
        val defaultSkinDir = File(shipDir, "default")
        return if (defaultSkinDir.exists() && defaultSkinDir.isDirectory) {
            // 新结构：<ship>/default/<files>
            val skelFile = defaultSkinDir.listFiles()?.firstOrNull { it.name.endsWith(SKEL_EXT) }
            if (skelFile != null) {
                SdAssetInfo(
                    dirPath = defaultSkinDir.absolutePath,
                    assetName = skelFile.nameWithoutExtension,
                    skins = skins
                )
            } else {
                // default 子目录存在但无 .skel，回退到平铺
                SdAssetInfo(shipDir.absolutePath, assetName, skins)
            }
        } else {
            // 旧结构：<ship>/<files> 平铺
            SdAssetInfo(shipDir.absolutePath, assetName, skins)
        }
    }

    /**
     * 扫描舰娘目录下的皮肤列表。
     *
     * 自动检测目录结构:
     * - 新结构（v2）: 子目录名为皮肤名，每个子目录含 .skel 文件
     * - 旧结构（v1）: .skel 文件平铺，通过文件名后缀识别皮肤
     */
    private fun scanSkins(shipDir: File, assetName: String): List<String> {
        if (!shipDir.exists() || !shipDir.isDirectory) return listOf("default")

        val files = shipDir.listFiles() ?: return listOf("default")

        // 检测新结构：有子目录且子目录中含 .skel 文件
        val skinDirs = files.filter { it.isDirectory && hasSkelInDir(it) }
        if (skinDirs.isNotEmpty()) {
            return skinDirs.map { it.name }.distinct().sorted().ifEmpty { listOf("default") }
        }

        // 旧结构：扫描 .skel 文件，按文件名后缀分类
        val skelFiles = files.filter { it.name.endsWith(SKEL_EXT) }
        if (skelFiles.isEmpty()) return listOf("default")

        val skins = skelFiles.map { skelFile ->
            val name = skelFile.nameWithoutExtension // boge / boge_g / z23_2
            // 大小写不敏感去除主名前缀（处理 Z19.skel 在 z19/ 目录的情况）
            val suffix = if (name.startsWith(assetName, ignoreCase = true)) {
                name.substring(assetName.length).removePrefix("_")
            } else name
            if (suffix.isEmpty()) "default" else when (suffix.lowercase()) {
                "g" -> "gai"
                "h" -> "huan"
                "hx" -> "huangxiang"
                "doa" -> "doa"
                "y" -> "teyao"
                else -> if (suffix.all { it.isDigit() }) "skin$suffix" else "default"
            }
        }.distinct().sorted()
        return skins.ifEmpty { listOf("default") }
    }

    /** 懒加载构建资源索引（线程安全双重检查锁） */
    private fun ensureIndex(context: Context): Set<String>? {
        availableAssets?.let { return it }
        synchronized(lock) {
            availableAssets?.let { return it }
            val dir = getResourceDir(context.applicationContext)
            resourceDir = dir
            if (!dir.exists()) {
                Log.w(TAG, "SD resource dir not found: $dir")
                availableAssets = emptySet()
                skinIndex = emptyMap()
                return emptySet()
            }
            val shipDirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val index = HashSet<String>(shipDirs.size)
            val skins = HashMap<String, List<String>>(shipDirs.size)
            for (shipDir in shipDirs) {
                // 同时支持新结构（子目录含 .skel）和旧结构（平铺 .skel）
                if (hasSkelFiles(shipDir)) {
                    val name = shipDir.name
                    index.add(name)
                    skins[name] = scanSkins(shipDir, name)
                }
            }
            availableAssets = index
            skinIndex = skins
            Log.i(TAG, "SD asset index built: ${index.size} ships from $dir")
            return index
        }
    }

    /**
     * 生成舰娘名称变体（去除常见后缀），用于模糊匹配。
     * 规则参考 [LocalAvatarResolver.buildVariants]。
     */
    private fun buildNameVariants(shipName: String): List<String> {
        val variants = LinkedHashSet<String>()
        // 去除改造后缀
        when {
            shipName.contains(".改") -> variants.add(shipName.replace(".改", ""))
            shipName.contains("改") -> variants.add(shipName.replace("改", ""))
            shipName.contains("Kai") -> variants.add(shipName.replace("Kai", ""))
        }
        // 去除 META / μ兵装 / II 后缀
        if (shipName.contains("·META")) variants.add(shipName.replace("·META", ""))
        if (shipName.contains("(μ兵装)")) variants.add(shipName.replace("(μ兵装)", ""))
        if (shipName.endsWith("II")) variants.add(shipName.removeSuffix("II"))

        // 去除联动后缀（DOA/SSSS/BB/CV/DE/JP 等），生成基础舰娘名变体
        // 与 LocalAvatarResolver.buildVariants 逻辑一致
        val collabSuffixes = listOf("DOA", "SSSS", "BB", "CV", "DE", "JP")
        for (suffix in collabSuffixes) {
            if (shipName.endsWith(suffix)) {
                val base = shipName.removeSuffix(suffix).trim()
                if (base.isNotBlank()) variants.add(base)
            }
        }

        // 提取英文/数字前缀（如 "Z1莉泽洛特" → "Z1"，"伊13十纱" → "I13"）
        extractAlphanumericPrefix(shipName)?.let { variants.add(it) }
        if (shipName.startsWith("伊")) {
            val rest = shipName.removePrefix("伊")
            extractAlphanumericPrefix("I$rest")?.let { variants.add(it) }
        }

        // 组合去除
        if (shipName.contains(".改") && shipName.contains("·META")) {
            variants.add(shipName.replace(".改", "").replace("·META", ""))
        }
        return variants.toList()
    }

    /**
     * 从舰娘名中提取开头的英文+数字前缀。
     * 与 [LocalAvatarResolver.extractAlphanumericPrefix] 逻辑一致。
     */
    private fun extractAlphanumericPrefix(shipName: String): String? {
        // 1. 字母+数字后跟中文（如 "Z1莉泽洛特" → "Z1"）
        val regex = Regex("^([A-Za-z]+[0-9]*)[\\u4e00-\\u9fff]")
        val match = regex.find(shipName)
        if (match != null) {
            val prefix = match.groupValues[1]
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
     * 特殊变体优先匹配：根据舰娘名的特殊标记，映射到对应的 SD 资源目录后缀。
     *
     * 映射规则（与 [LocalAvatarResolver.resolveSpecialVariant] 一致）：
     * - 舰娘名含 `·META` / `.META` → 匹配 `_alter` 后缀目录（如 "大凤·META" → `dafeng_alter/`）
     * - 舰娘名含 `(μ兵装)` / `μ兵装` → 匹配 `_idol` 后缀目录（如 "赤城(μ兵装)" → `chicheng_idol/`）
     * - 舰娘名以 `小` 开头（幼女/小船形态）→ 匹配 `_younv` 后缀目录（如 "小赤城" → `chicheng_younv/`）
     *
     * 优先于 [buildNameVariants] 的去除后缀逻辑执行，避免 META/μ兵装/幼女舰娘
     * 在去除标记后错误回退到基础舰娘的 SD 资源目录。
     *
     * @return 特殊变体 SD 资源信息，不匹配返回 null
     */
    private fun resolveSpecialVariant(shipName: String, index: Set<String>, dir: File): SdAssetInfo? {
        // META 舰娘（余烬）：舰娘名含 "·META" → 匹配 _alter 后缀目录
        if (shipName.contains("·META") || shipName.contains(".META")) {
            val baseName = shipName.replace("·META", "").replace(".META", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, ALTER_SKIN_SUFFIX, index, dir)?.let { return it }
            }
        }

        // μ兵装舰娘：舰娘名含 "(μ兵装)" → 匹配 _idol 后缀目录
        if (shipName.contains("(μ兵装)") || shipName.contains("μ兵装")) {
            val baseName = shipName.replace("(μ兵装)", "").replace("μ兵装", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, IDOL_SKIN_SUFFIX, index, dir)?.let { return it }
            }
        }

        // 幼女/小船形态：舰娘名以 "小" 开头 → 匹配 _younv 后缀目录
        // 注意：仅当"小"后还有内容时才处理，避免误匹配"小"字单独成名的舰娘
        if (shipName.startsWith("小") && shipName.length > 1) {
            val baseName = shipName.removePrefix("小").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, YOUNV_SKIN_SUFFIX, index, dir)?.let { return it }
            }
        }

        return null
    }

    /**
     * 用指定后缀在资源索引中查找 SD 目录。
     *
     * 匹配策略（与 [LocalAvatarResolver.resolveWithSuffix] 一致）：
     * 1. 原始名 + 后缀 小写匹配
     * 2. 拼音 + 后缀 匹配
     * 3. 去后缀变体的原始名/拼音 + 后缀 匹配
     *
     * @param baseName 去除特殊标记后的基础舰娘名（如 "大凤" / "赤城"）
     * @param suffix 目录名后缀（如 `_alter` / `_idol` / `_younv`）
     * @return 匹配到的 SD 资源信息，不存在返回 null
     */
    private fun resolveWithSuffix(baseName: String, suffix: String, index: Set<String>, dir: File): SdAssetInfo? {
        // 0. MANUAL_MAP 优先：基础名在手动映射表中时，用映射值 + 后缀
        // 解决拼音转换与实际目录名拼写不一致的特殊变体匹配
        // 与 LocalAvatarResolver.resolveWithSuffix 逻辑一致
        MANUAL_MAP[baseName]?.let { mappedName ->
            val key = "$mappedName$suffix"
            if (key in index) {
                return buildAssetInfo(dir, key)
            }
        }

        // 1. 原始名 + 后缀
        val rawKey = "${baseName.lowercase()}$suffix"
        if (rawKey in index) {
            return buildAssetInfo(dir, rawKey)
        }

        // 2. 拼音 + 后缀
        val pinyin = PinyinHelper.toPinyin(baseName)
        if (pinyin.isNotEmpty()) {
            val pinyinKey = "$pinyin$suffix"
            if (pinyinKey in index) {
                return buildAssetInfo(dir, pinyinKey)
            }
        }

        // 3. 去后缀变体 + 后缀
        for (variant in buildNameVariants(baseName)) {
            MANUAL_MAP[variant]?.let { mappedName ->
                val key = "$mappedName$suffix"
                if (key in index) {
                    return buildAssetInfo(dir, key)
                }
            }
            val variantRawKey = "${variant.lowercase()}$suffix"
            if (variantRawKey in index) {
                return buildAssetInfo(dir, variantRawKey)
            }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                val variantPinyinKey = "$variantPinyin$suffix"
                if (variantPinyinKey in index) {
                    return buildAssetInfo(dir, variantPinyinKey)
                }
            }
        }

        return null
    }
}
