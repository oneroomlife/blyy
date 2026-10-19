package com.azurlane.blyy.util

import android.util.Log

/**
 * 舰娘头像纯匹配核心（无 Android Context 依赖，可 JVM 单测）。
 *
 * 输入为文件名索引（键 = 不含扩展名小写文件名，值 = 实际完整文件名），
 * 输出命中的完整文件名；所有匹配策略见 [match] / [matchOath] 的 KDoc。
 *
 * 命名约定（与 assets/blhx_avatar 资源一致）：
 * - 文件名 = 舰娘名称的**无声调全拼** + 可选皮肤后缀 + 扩展名
 *   （例如 `boge.webp` 对应"博格"，`z23_h.webp` 对应"Z23"的誓约婚皮）
 * - 皮肤后缀：`_g` 改造、`_h` 誓约婚皮、`_2`/`_3` 换装、`_alter` META、
 *   `_idol` μ兵装、`_younv` 幼女、`_super` 布里等
 */
object AvatarMatcher {

    private const val TAG = "AvatarMatcher"

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
     * 手动映射表：舰娘中文名 → 头像文件名（不含扩展名）。
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
        // 贝露 = 海王星联动 Vert 的人形形态（HDN401），≠ 皇家舰"佩内洛珀"(peineiluopo)
        "贝露" to "HDN401",
        "第二代" to "erdaimu",
        "双海亚美" to "yamei",
        "八舞耶倶矢·八舞夕弦" to "bawu",
        "卡菈·伊迪亚斯" to "kala",
        "莉拉·德西亚斯" to "lila",
        "艾菈·冯·杜勒" to "aila",
        "乌戈里诺·维瓦尔迪" to "wugelini",
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
        "小斯佩" to "sipeibojue_younv",
        "小贝法" to "beierfasite_younv",

        "朝凪" to "zhaozhi",
        "酒匂" to "jiuyun",
        "雫" to "na_doa",

        "曾克海军上将" to "zengkehaijunshangjiang",
        "BLACK★ROCK" to "heiyansheshou",
        "天城CV" to "tiancheng_cv",
        // META 特殊命名（mid 前缀，区别于普通赤城）
        "赤城·META" to "midchicheng_alter",

        // ---- 以下为全量回归测试（AvatarMatcherTest）中逐项与 wiki 官方头像
        // ---- 比对后确认的条目，删除前请先跑单测确认不产生误配/失配
        // 倔强: pinyin4j "jueqiang"，资源按正确读音 juejiang 命名
        "倔强" to "juejiang",
        // 如月千早: 资源取名"千早"，避免反向包含误配驱逐舰"如月"(ruyue)
        "如月千早" to "qianzao",
        // 英王乔治五世: 资源取名"乔治五世"，避免模糊匹配误配"鹰"(ying)
        "英王乔治五世" to "qiaozhiwushi",
        // 加贺BB: 资源命名"加贺战列"，避免去除BB后缀误配"加贺"(jiahe)
        "加贺BB" to "jiahezhanlie",
        // 约克DE: 铁血形态，资源命名 yueke_ger
        "约克DE" to "yueke_ger",
        // 重剑: 资源命名 zhongjian，避免模糊匹配误配 jian.webp
        "重剑" to "zhongjian",
        // 飞鸟川千濑: 资源取名"千濑"，避免反向包含误配轻巡"飞鸟"(feiniao)
        "飞鸟川千濑" to "qianlai",
        // 朴茨茅斯冒险号: 资源取名"朴茨茅斯"，避免反向包含误配"冒险号"(maoxianhao)
        "朴茨茅斯冒险号" to "pucimaosi",
        // 印第安纳波利斯: 资源取名"波利斯"(bolisi)，避免反向包含误配"印第安纳"(yindianna)
        "印第安纳波利斯" to "bolisi",
        // 紫(闪乱神乐NL): 资源命名 zi_shanluan，避免精确匹配误配"三浦梓"(zi)
        "紫" to "zi_shanluan",
        // DEAD = 死亡主宰，资源按中文名全拼命名
        "DEAD" to "siwangzhuzai",
        // 绊爱系列（Kizuna AI）：资源以"爱酱"(aijiang) + 舰种后缀命名
        "绊爱" to "aijiangDD",
        "绊爱·Elegant" to "aijiangCL",
        "绊爱·Anniversary" to "aijiangCV",
        "绊爱·SuperGamer" to "aijiangBB",
        // 凪咲(DOA联动): 资源按 zhixiao_DOA 命名，避免"凪"无拼音导致"xiao"误配"晓"
        "凪咲" to "zhixiao_DOA",
        // 海王星联动（超次元游戏海王星，HDN 编号命名）：
        // HDNx01 = 人形，HDNx02_1 = 女神形态（编号 1涅普顿 2诺瓦露 3布兰 4贝露/翡绿）
        "涅普顿" to "HDN101",
        "绀紫之心" to "HDN102_1",
        "诺瓦露" to "HDN201",
        "圣黑之心" to "HDN202_1",
        "布兰" to "HDN301",
        "群白之心" to "HDN302_1",
        "翡绿之心" to "HDN402_1"
    )

    /**
     * 禁止本地匹配的舰娘名单：这些舰娘在 assets 中没有自己的头像文件，
     * 且拼音恰好与另一艘舰娘的文件名相同（精确/模糊匹配必然误配他人头像），
     * 必须直接返回 null 由调用方回退网络 URL。
     *
     * 目前为空：原案例"凪咲"已确认自有资源 zhixiao_DOA（MANUAL_MAP）。
     */
    private val BLOCK_NAMES: Set<String> = setOf()

    /**
     * 主匹配级联：舰娘名 → 头像文件名（不含扩展名的索引键所在条目值）。
     *
     * 匹配优先级：
     * 0. 特殊变体优先匹配（META→`_alter`、μ兵装→`_idol`、小前缀→`_younv`），
     *    避免去除后缀后错误回退到默认头像
     * 1. 原始舰娘名小写精确匹配（覆盖文件名直接用中文/英文命名的情况）
     * 2. 拼音全拼匹配（"博格" → "boge" == 文件名 "boge"）
     * 3. 去后缀变体的拼音匹配（去除 `.改` / `·META` / `(μ兵装)` / `II` 等）
     *
     * @param shipName 舰娘名称（与 biligame wiki 图鉴列表一致）
     * @param index 文件名索引（键 = 不含扩展名小写，值 = 实际文件名）
     * @return 命中的完整文件名（含扩展名），匹配不到返回 null
     */
    fun match(shipName: String, index: Map<String, String>): String? {
        if (shipName.isBlank() || index.isEmpty()) return null

        // -2. 禁止本地匹配名单：拼音撞车且无自有资源，强制走网络兜底
        if (shipName in BLOCK_NAMES) {
            Log.w(TAG, "[MISS] '$shipName' (BLOCK_NAMES 拼音撞车保护)")
            return null
        }

        // -1. 手动映射表优先匹配（文件名拼写与拼音差异大的舰娘）
        MANUAL_MAP[shipName]?.let { mappedName ->
            index[mappedName.lowercase()]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> manual map: $it")
                return it
            }
        }

        // 0. 特殊变体优先匹配（META/μ兵装/幼女），避免错误回退到默认头像
        resolveSpecialVariant(shipName, index)?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> special variant: $it")
            return it
        }

        // 0.2 皮肤形态硬性不回退：META/μ兵装舰娘的形象与基础舰娘完全不同，
        // 对应皮肤文件（_alter/_idol）缺失时必须返回 null 由调用方回退网络 URL，
        // 绝不能像下方步骤 3 那样去除标记后回退到基础舰娘头像（会显示错图）。
        // 典型案例：assets 无 salatuojia_alter 时，"萨拉托加·META" 绝不应显示 salatuojia
        if (shipName.contains("META") || shipName.contains("μ兵装")) {
            Log.w(TAG, "[MISS] '$shipName' (皮肤形态文件缺失，拒绝回退基础头像)")
            return null
        }

        // 0.5 改造形态优先匹配：舰娘名含 ".改" → 优先匹配 _g 后缀文件
        if (shipName.contains(".改") || shipName.contains("改")) {
            val baseName = shipName.replace(".改", "").replace("改", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, REMODEL_SKIN_SUFFIX, index)?.let {
                    Log.i(TAG, "[MATCH] '$shipName' -> remodel variant: $it")
                    return it
                }
            }
            // 改造舰的立绘与基础形态不同，_g 文件缺失时同样不回退基础头像（网络兜底）
            Log.w(TAG, "[MISS] '$shipName' (改造文件缺失，拒绝回退基础头像)")
            return null
        }

        // 0.7 尾部标记变体优先匹配：舰娘名尾部带 CV/DOA/JP 等英文标记 → 匹配 `拼音_标记` 文件
        // 避免 "天城CV" 错误回退到默认天城 tiancheng，"霞(DOA)" 错配重樱霞 xia
        resolveTailMarkVariant(shipName, index)?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> tail mark variant: $it")
            return it
        }

        // 1. 原始舰娘名小写精确匹配
        index[shipName.lowercase()]?.let {
            Log.i(TAG, "[MATCH] '$shipName' -> raw lowercase: $it")
            return it
        }

        // 2. 拼音全拼匹配
        val pinyin = PinyinHelper.toPinyin(shipName)
        if (pinyin.isNotEmpty()) {
            index[pinyin]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> pinyin($pinyin): $it")
                return it
            }
        }

        // 3. 去后缀变体的原始名 / 拼音匹配
        for (variant in buildVariants(shipName)) {
            index[variant.lowercase()]?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> variant($variant) raw: $it")
                return it
            }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index[variantPinyin]?.let {
                    Log.i(TAG, "[MATCH] '$shipName' -> variant($variant) pinyin($variantPinyin): $it")
                    return it
                }
            }
        }

        // 4. 包含匹配兜底
        if (pinyin.isNotEmpty()) {
            resolveContainingVariant(index, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> containing($pinyin): $it")
                return it
            }
        }

        // 5. 反向包含匹配
        if (pinyin.isNotEmpty()) {
            resolveReverseContaining(index, shipName, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> reverse containing($pinyin): $it")
                return it
            }
        }

        // 6. 模糊子串匹配
        if (pinyin.isNotEmpty()) {
            resolveFuzzySubstring(index, shipName, pinyin)?.let {
                Log.i(TAG, "[MATCH] '$shipName' -> fuzzy substring($pinyin): $it")
                return it
            }
        }

        Log.w(TAG, "[MISS] '$shipName' (pinyin=$pinyin)")
        return null
    }

    /**
     * 誓约状态匹配：优先 `_h` 婚皮变体，无婚皮回退 [match]。
     *
     * 匹配策略与 [match] 一致，但在拼音后追加 `_h` 后缀：
     * 1. 原始舰娘名 + `_h` 小写匹配
     * 2. 拼音 + `_h` 匹配
     * 3. 去后缀变体的原始名/拼音 + `_h` 匹配
     */
    fun matchOath(shipName: String, index: Map<String, String>): String? {
        if (shipName.isBlank() || index.isEmpty()) return null

        // 特殊变体优先（META/μ兵装/幼女无婚皮，誓约后仍显示特殊变体头像）
        resolveSpecialVariant(shipName, index)?.let { return it }

        // 改造形态优先（改造舰誓约后仍应显示改造头像 _g，而非基础婚皮 _h）
        if (shipName.contains(".改") || shipName.contains("改")) {
            val baseName = shipName.replace(".改", "").replace("改", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, REMODEL_SKIN_SUFFIX, index)?.let { return it }
            }
        }

        // 尝试婚皮变体
        resolveOathVariant(shipName, index)?.let { return it }

        // 婚皮不存在，回退默认头像
        return match(shipName, index)
    }

    /**
     * 尝试匹配誓约婚皮变体（`_h` 后缀）。
     *
     * @return 婚皮头像文件名，不存在返回 null
     */
    private fun resolveOathVariant(shipName: String, index: Map<String, String>): String? {
        // 0. MANUAL_MAP 优先：舰娘名在手动映射表中时，用映射值 + _h
        // 典型案例："威廉·D·波特" → bote_h、"苏维埃同盟" → suweiaitongmengnew_h
        MANUAL_MAP[shipName]?.let { mapped ->
            index["$mapped$OATH_SKIN_SUFFIX"]?.let { return it }
        }

        // 1. 原始舰娘名 + _h
        index["${shipName.lowercase()}$OATH_SKIN_SUFFIX"]?.let { return it }

        // 2. 拼音 + _h
        val pinyin = PinyinHelper.toPinyin(shipName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$OATH_SKIN_SUFFIX"]?.let { return it }
        }

        // 3. 去后缀变体 + _h
        for (variant in buildVariants(shipName)) {
            MANUAL_MAP[variant]?.let { mapped ->
                index["$mapped$OATH_SKIN_SUFFIX"]?.let { return it }
            }
            index["${variant.lowercase()}$OATH_SKIN_SUFFIX"]?.let { return it }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$OATH_SKIN_SUFFIX"]?.let { return it }
            }
        }

        return null
    }

    /**
     * 特殊变体优先匹配：根据舰娘名的特殊标记，映射到对应的头像文件后缀。
     *
     * 映射规则：
     * - 舰娘名含 `·META` / `.META` → 匹配 `_alter` 后缀文件（如 "大凤·META" → `dafeng_alter`）
     * - 舰娘名含 `(μ兵装)` / `μ兵装` → 匹配 `_idol` 后缀文件（如 "赤城(μ兵装)" → `chicheng_idol`）
     * - 舰娘名以 `小` 开头（幼女/小船形态）→ 匹配 `_younv` 后缀文件（如 "小赤城" → `chicheng_younv`）
     *
     * 优先于 [buildVariants] 的去除后缀逻辑执行，避免 META/μ兵装/幼女舰娘
     * 在去除标记后错误回退到基础舰娘的默认头像。
     */
    private fun resolveSpecialVariant(shipName: String, index: Map<String, String>): String? {
        // META 舰娘（余烬）：舰娘名含 "·META" → 匹配 _alter 后缀
        if (shipName.contains("·META") || shipName.contains(".META")) {
            val baseName = shipName.replace("·META", "").replace(".META", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, ALTER_SKIN_SUFFIX, index)?.let { return it }
            }
        }

        // μ兵装舰娘：舰娘名含 "(μ兵装)" → 匹配 _idol 后缀
        if (shipName.contains("(μ兵装)") || shipName.contains("μ兵装")) {
            val baseName = shipName.replace("(μ兵装)", "").replace("μ兵装", "").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, IDOL_SKIN_SUFFIX, index)?.let { return it }
            }
        }

        // 幼女/小船形态：舰娘名以 "小" 开头 → 匹配 _younv 后缀
        // 注意：仅当"小"后还有内容时才处理，避免误匹配"小"字单独成名的舰娘
        if (shipName.startsWith("小") && shipName.length > 1) {
            val baseName = shipName.removePrefix("小").trim()
            if (baseName.isNotBlank()) {
                resolveWithSuffix(baseName, YOUNV_SKIN_SUFFIX, index)?.let { return it }
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
     */
    private fun resolveTailMarkVariant(shipName: String, index: Map<String, String>): String? {
        val mark = extractTailMark(shipName) ?: return null
        // 去除尾部标记（含可选括号）："霞(DOA)" → "霞"、"天城CV" → "天城"
        val tailPattern = Regex("[(（]?${Regex.escape(mark)}[)）]?$")
        val baseName = shipName.replaceFirst(tailPattern, "").trim()
        if (baseName.isBlank() || baseName == shipName) return null
        val markSuffix = "_${mark.lowercase()}"

        // 1. MANUAL_MAP 基础名 + _标记
        MANUAL_MAP[baseName]?.let { mapped ->
            index["$mapped$markSuffix"]?.let { return it }
        }

        // 2. 基础名拼音 + _标记
        val pinyin = PinyinHelper.toPinyin(baseName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$markSuffix"]?.let { return it }
        }

        // 3. 变体拼音 + _标记
        for (variant in buildVariants(baseName)) {
            MANUAL_MAP[variant]?.let { mapped ->
                index["$mapped$markSuffix"]?.let { return it }
            }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$markSuffix"]?.let { return it }
            }
        }

        return null
    }

    /**
     * 提取舰娘名尾部的英文标记（2-6 个字母，可带数字）。
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
     * 匹配策略与 [match] 一致，但在拼音/变体后追加指定的皮肤后缀。
     *
     * @param baseName 去除特殊标记后的基础舰娘名（如 "大凤" / "赤城"）
     * @param suffix 文件名后缀（如 `_alter` / `_idol` / `_younv`）
     */
    private fun resolveWithSuffix(baseName: String, suffix: String, index: Map<String, String>): String? {
        // 0. MANUAL_MAP 优先：基础名在手动映射表中时，用映射值 + 后缀
        // 典型案例："滨风.改" → baseName="滨风" → MANUAL_MAP["滨风"]="bangfeng" → "bangfeng_g"
        MANUAL_MAP[baseName]?.let { mappedName ->
            index["$mappedName$suffix"]?.let { return it }
        }

        // 1. 原始名 + 后缀
        index["${baseName.lowercase()}$suffix"]?.let { return it }

        // 2. 拼音 + 后缀
        val pinyin = PinyinHelper.toPinyin(baseName)
        if (pinyin.isNotEmpty()) {
            index["$pinyin$suffix"]?.let { return it }
        }

        // 3. 去后缀变体 + 后缀
        for (variant in buildVariants(baseName)) {
            MANUAL_MAP[variant]?.let { mappedName ->
                index["$mappedName$suffix"]?.let { return it }
            }
            index["${variant.lowercase()}$suffix"]?.let { return it }
            val variantPinyin = PinyinHelper.toPinyin(variant)
            if (variantPinyin.isNotEmpty() && variantPinyin != pinyin) {
                index["$variantPinyin$suffix"]?.let { return it }
            }
        }

        // 4. 模糊子串匹配兜底：文件名核心部分是拼音的子串 + 后缀
        // 覆盖 "朱利奥·凯撒·META" → kaisa_alter（kaisa 是 zhuliaokaisa 的子串）
        // 子串出现同样必须与音节边界对齐（与 resolveFuzzySubstring 同一防误配闸门）
        if (pinyin.isNotEmpty() && pinyin.length >= 5) {
            val bestMatch = index.entries
                .filter { (key, _) ->
                    key.endsWith(suffix) &&
                        key.length > suffix.length + 3 &&
                        PinyinHelper.isSyllableAligned(
                            baseName,
                            pinyin,
                            key.removeSuffix(suffix)
                        ) &&
                        pinyin.contains(key.removeSuffix(suffix))
                }
                .maxByOrNull { it.key.length }
            bestMatch?.let { return it.value }
        }

        return null
    }

    /**
     * 包含匹配兜底：查找以舰娘拼音开头且带下划线后缀的文件。
     *
     * 覆盖联动皮肤等非标准后缀命名：
     * - `suixiang_doa` ← "穗香"（拼音 `suixiang` + `_doa` 后缀）
     * - `lala_tolove` ← "拉拉"（拼音 `lala` + `_tolove` 后缀）
     * - `gaoxiong_dark` ← "高雄"（拼音 `gaoxiong` + `_dark` 后缀）
     *
     * 匹配规则：文件名（小写）以 `<拼音>_` 开头，且拼音长度 ≥ 3（避免过短前缀误匹配）。
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
     * 处理 assets 文件使用缩短命名、文件名是舰娘拼音**前缀**的情况：
     * - `abuluqi` ← "阿布鲁齐公爵"（pinyin=abuluqigongjue）
     * - `wuerlixi` ← "乌尔里希·冯·胡滕"（pinyin=wuerlixifenghuteng）
     * - `ougen` ← "欧根亲王"（pinyin=ougenqinwang）
     * - `zaoshen` ← "女灶神"（pinyin=nvzaoshen）
     *
     * 多个匹配时选最长（最精确）的文件名。
     * ⚠️ 反向包含是误配重灾区（"印第安纳波利斯"曾误配"印第安纳"的 yindianna），
     * 新发现的撞车舰娘应加入 [BLOCK_NAMES] 而非在此加特例。
     */
    /**
     * 反向包含匹配：查找是拼音前缀的文件（assets 缩短命名兜底）。
     *
     * 处理 assets 文件使用缩短命名、文件名是舰娘拼音**前缀**的情况：
     * - `abuluqi` ← "阿布鲁齐公爵"（pinyin=abuluqigongjue）
     * - `wuerlixi` ← "乌尔里希·冯·胡滕"（pinyin=wuerlixifenghuteng）
     * - `ougen` ← "欧根亲王"（pinyin=ougenqinwang）
     * - `zaoshen` ← "女灶神"（pinyin=nvzaoshen）
     *
     * 多个匹配时选最长（最精确）的文件名。
     * 前缀终点必须落在舰娘名逐字拼音的音节边界上（整字缩写），防止
     * 截断在音节中间的偶然前缀（如 "abulu" 截断 "阿布鲁齐" 的 "qi"）。
     * ⚠️ 反向包含是误配重灾区（"印第安纳波利斯"曾误配"印第安纳"的 yindianna），
     * 新发现的撞车舰娘应加入 [BLOCK_NAMES] 而非在此加特例。
     */
    private fun resolveReverseContaining(index: Map<String, String>, shipName: String, pinyin: String): String? {
        if (pinyin.length < 5) return null
        return index.entries
            .mapNotNull { (key, value) ->
                // 1. 文件名无下划线：直接检查文件名是否为拼音前缀
                // 阈值 >= 4 支持 gezi/aila/kala/lila/bawu 等短文件名
                if (!key.contains('_')) {
                    if (key.length >= 4 && pinyin.startsWith(key) &&
                        PinyinHelper.isSyllableBoundaryAt(shipName, pinyin, key.length)
                    ) {
                        key to value
                    } else null
                } else {
                    // 2. 文件名含下划线（如 nana_tolove）：取下划线前部分检查
                    // 排除已知皮肤后缀文件（_h/_g/_y/_alter/_idol/_younv/_hx/_R/_数字），
                    // 避免长名舰娘误匹配到婚皮/改造等皮肤文件
                    // 典型案例：wuerlixi_h（婚皮）不应被 "乌尔里希·冯·胡滕" 反向包含匹配
                    val suffix = key.substringAfter('_').lowercase()
                    if (isSkinSuffix(suffix)) return@mapNotNull null
                    val pre = key.substringBefore('_')
                    // 阈值 >= 4 避免过短前缀误匹配（如 xia_DOA 误匹配"小"系列舰娘）
                    if (pre.length >= 4 && pinyin.startsWith(pre) &&
                        PinyinHelper.isSyllableBoundaryAt(shipName, pinyin, pre.length)
                    ) {
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
     * 匹配规则：
     * - 仅处理无下划线的纯拼音文件名（带后缀的 _alter/_idol 等已有专门策略）
     * - 文件名长度 >= 4（避免过短文件名造成大面积误匹配）
     * - 文件名是拼音的连续子串（pinyin.contains(fileName)）
     * - **音节边界对齐**：子串出现位置必须落在舰娘名逐字拼音的音节边界上，
     *   防止跨字拼接误配——典型案例 "水星号"(shui|xing|hao) 字符层面包含
     *   "彗星"(huixing)，但 s[huixing]hao 不是音节对齐的出现，必须拒绝
     * - 相似度阈值：文件名长度 / 拼音长度 >= 0.4（确保文件名足够显著，避免偶然子串匹配）
     *   特例：文件名长度 >= 6 时跳过比例检查（长文件名本身已足够特异）
     * - 多候选取最长文件名（最精确）
     */
    private fun resolveFuzzySubstring(index: Map<String, String>, shipName: String, pinyin: String): String? {
        if (pinyin.length < 5) return null
        return index.entries
            .filter { (key, _) ->
                // 仅处理无下划线的纯拼音文件名
                !key.contains('_') &&
                    key.length >= 4 &&
                    // 文件名是拼音的连续子串
                    pinyin.contains(key) &&
                    // 子串出现位置必须与音节边界对齐（防跨字拼接）
                    PinyinHelper.isSyllableAligned(shipName, pinyin, key) &&
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
}
