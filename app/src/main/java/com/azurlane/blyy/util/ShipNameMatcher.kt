package com.azurlane.blyy.util

import android.util.Log
import kotlin.math.abs
import kotlin.math.min

/**
 * 舰娘名 / 资源 id 的**统一匹配引擎**（纯 Kotlin，可 JVM 全量回归）。
 *
 * ## 为什么合并
 *
 * 历史上两套匹配算法各自演进：
 * - 船坞头像 [AvatarMatcher]：索引是 `assets/blhx_avatar` 的**拼音文件名**；
 * - 今日秘书舰 SD [LocalSdResolver]：索引是 `blhx_sd/` 的**拼音目录名**。
 *
 * 它们复制了 6 个同义函数（`extractTailMark` / `isSkinSuffix` / `resolveWithSuffix` /
 * `resolveFuzzySubstring` / `resolveContainingVariant` / 变体拆分），还各带一份
 * **互相冲突**的手写别名表——同一个「贝露」一边映射 `HDN401`、一边映射 `peineiluopo`，
 * 「泛用型布里 / 试作型布里MKII」在 SD 表里都指向 `buli_super`（必然撞车）。
 *
 * 合并后的取舍：
 * - **保留**头像侧的别名表（1004 舰全量回归 + 零撞车审计背书）与音节边界闸门；
 * - **保留** SD 侧的首字母缩写、逐字前缀、目录前缀三类策略（覆盖 `welxfht` / `wuerlixi`）；
 * - **丢弃**两份重复实现与冲突映射，别名改成**有序候选列表**：按顺序尝试，
 *   以「索引里真的存在」者为准（冲突项因此自动择优，无需人工裁决）；
 * - **新增**拼写容错（Damerau-Levenshtein），覆盖 `jueqiang`/`juejiang`、`binfeng`/`bangfeng`
 *   这类拼音库与实际资源拼写不一致、又不在别名表里的情况。
 *
 * ## 匹配分级（从强到弱，命中即返回）
 *
 * | 级别 | 说明 |
 * |------|------|
 * | `SPECIAL` | 变体标记优先：`·META`→`_alter`、`μ兵装`→`_idol`、`小X`→`_younv` |
 * | `TAIL_MARK` | 尾部标记：`霞(DOA)`→`xia_doa`、`天城CV`→`tiancheng_cv` |
 * | `EXACT` | 原名 / 别名精确命中 |
 * | `PINYIN` | 全拼精确（含变体名的全拼） |
 * | `CONTAINING` | 候选以「拼音_」开头（联动后缀 `_doa`/`_tolove` 等） |
 * | `PREFIX` | 候选是拼音前缀（`wuerlixi` ← 乌尔里希·冯·胡滕） |
 * | `FUZZY` | 拼音中的音节对齐子串（`qinli` ← 五河琴里） |
 * | `INITIALS` | 首字母缩写（`welxfht`） |
 * | `SEGMENTS` | 逐字拼音前 N 段拼接 |
 * | `TYPO` | 拼写容错（编辑距离 ≤1/≤2） |
 *
 * 所有前缀/子串类匹配都必须通过**音节边界**校验（[PinyinHelper.isSyllableAligned] /
 * [PinyinHelper.isSyllableBoundaryAt]），这是历史误配（"水星号"→"彗星"）的直接防线。
 */
object ShipNameMatcher {

    private const val TAG = "ShipNameMatcher"

    /** 变体种类 */
    enum class Variant {
        /** 默认形态 */
        DEFAULT,

        /** 换装序号（`_2` / `_12`） */
        SKIN,

        /** 改造（`.改` → `_g`） */
        REMODEL,

        /** 誓约婚皮（`_h`） */
        OATH,

        /** META / 余烬（`_alter`） */
        META,

        /** μ兵装（`_idol`） */
        IDOL,

        /** 幼女 / 小船（`_younv`） */
        YOUNV,

        /** 特装型布里（`_super`） */
        SUPER,

        /** 尾部联动标记（`_doa` / `_cv` / `_jp` …） */
        TAIL_MARK
    }

    /** 查询解析结果 */
    data class Parsed(
        val raw: String,
        /** 原文（音节校验与全拼都以它为准，保证 "小赤城" 仍是 xiaochicheng） */
        val core: String,
        /** 去掉变体标记后的基名（"长春.改" → "长春"），仅用于带后缀的尝试 */
        val variantBase: String,
        val pinyin: String,
        val initials: String,
        val segments: List<String>,
        /** 派生候选基名（去 `.改` / `·META` / `(μ兵装)` / 联动后缀 / 英文前缀 …） */
        val variants: List<String>,
        val variant: Variant,
        val tailMark: String?
    )

    /** 一次匹配结果 */
    data class Match(val key: String, val stage: String, val confidence: Int)

    data class Options(
        /** 允许首字母缩写（SD 目录常见 `welxfht`；头像资产几乎不用） */
        val allowInitials: Boolean = true,
        /** 允许拼写容错（编辑距离） */
        val allowTypo: Boolean = true,
        val aliases: Map<String, List<String>> = ALIASES
    )

    // ------------------------------------------------------------------ 资源 id 解析

    /** 资源 id / 文件名拆解结果 */
    data class KeyParts(
        /** 去掉全部尾部标记后的核心拼音 */
        val core: String,
        /** 尾部标记（按出现顺序，如 `["alter"]` / `["rw","hx"]`） */
        val suffixTokens: List<String>,
        val variant: Variant,
        val tailMark: String?
    )

    /** 资源 id 上常见的「非形态」后缀（立绘分层、和谐版等），匹配时直接剥掉 */
    private val NOISE_SUFFIXES = setOf("shophx", "buildhx", "hx", "rw", "front", "tex", "res")

    /**
     * 资源 id / 文件名 → 结构化的核心拼音 + 尾部标记。
     *
     * `2b_2_hx` → core `2b`，suffix `["hx"]`；`dafeng_alter` → core `dafeng`，variant META；
     * `boge_g` → core `boge`，variant REMODEL；`xia_doa` → core `xia`，tailMark `doa`。
     */
    fun keyParts(key: String): KeyParts {
        var current = key.trim().lowercase()
        current = current.substringBeforeLast('.', current)
        val tokens = ArrayList<String>(2)
        while (true) {
            val idx = current.lastIndexOf('_')
            if (idx <= 0) break
            val token = current.substring(idx + 1)
            if (token.isEmpty()) break
            val known = token.all { it.isDigit() } || token in SKIN_TOKENS ||
                token in NOISE_SUFFIXES || token in OATH_TOKENS || token in REMODEL_TOKENS ||
                token in ALTER_TOKENS || token in IDOL_TOKENS || token in YOUNV_TOKENS ||
                token in SUPER_TOKENS || token in TAIL_TOKENS
            if (known) {
                tokens.add(0, token)
                current = current.substring(0, idx)
                continue
            }
            break
        }
        val variant = when {
            tokens.any { it in OATH_TOKENS } -> Variant.OATH
            tokens.any { it in REMODEL_TOKENS } -> Variant.REMODEL
            tokens.any { it in ALTER_TOKENS } -> Variant.META
            tokens.any { it in IDOL_TOKENS } -> Variant.IDOL
            tokens.any { it in YOUNV_TOKENS } -> Variant.YOUNV
            tokens.any { it in SUPER_TOKENS } -> Variant.SUPER
            tokens.any { it.all { c -> c.isDigit() } } -> Variant.SKIN
            tokens.isNotEmpty() -> Variant.TAIL_MARK
            else -> Variant.DEFAULT
        }
        val tailMark = tokens.lastOrNull { token ->
            token !in SKIN_TOKENS && token !in NOISE_SUFFIXES && token !in ALTER_TOKENS &&
                token !in IDOL_TOKENS && token !in YOUNV_TOKENS && token !in SUPER_TOKENS &&
                token !in OATH_TOKENS && token !in REMODEL_TOKENS
        }
        return KeyParts(current, tokens, variant, tailMark)
    }

    // ------------------------------------------------------------------ 查询解析

    /** 解析舰娘名（与 wiki 一致的写法：`长春.改` / `大凤·META` / `霞(DOA)` / `Z23`） */
    fun parse(name: String): Parsed {
        val raw = name.trim()
        var core = raw
        var variant = Variant.DEFAULT

        val tailMark = extractTailMark(raw)
        if (tailMark != null) {
            variant = Variant.TAIL_MARK
            // 注意：core 仍是原文——"大黄蜂II" 的全拼 dahuangfengii 才能命中
            // dahuangfengII.webp；早期版本把 II 剥掉后就只能撞上"大黄蜂"
            core = raw
        }
        // 变体基名（去掉标记后的名字），只用于「带后缀」的尝试；
        // 全拼/音节校验始终用原文，否则 "小赤城" 会被算成 chicheng 而丢掉 xiaochicheng
        var variantBase = core
        when {
            raw.contains("·META", true) || raw.contains(".META", true) -> {
                variant = Variant.META
                variantBase = core.replace("·META", "").replace(".META", "").trim()
            }

            raw.contains("μ兵装") -> {
                variant = Variant.IDOL
                variantBase = core.replace("(μ兵装)", "").replace("（μ兵装）", "").replace("μ兵装", "").trim()
            }

            // `.改` 是显式改造标记，必须先于「小」前缀判定：
            // 否则「小天鹅.改」会被吃进 YOUNV 分支，REMODEL 缺 _g 不回退的硬规则被绕过
            raw.contains(".改") || raw.endsWith("改") || raw.contains("Kai") -> {
                variant = Variant.REMODEL
                variantBase = core.replace(".改", "").replace("改", "").replace("Kai", "").trim()
            }

            raw.startsWith("小") && raw.length > 1 -> {
                variant = Variant.YOUNV
                variantBase = raw.removePrefix("小").trim()
            }
        }
        if (variantBase.isBlank()) variantBase = raw

        return Parsed(
            raw = raw,
            core = core,
            variantBase = variantBase,
            pinyin = PinyinHelper.toPinyin(core),
            initials = PinyinHelper.toPinyinInitials(core),
            segments = PinyinHelper.toPinyinSegments(core).filter { it.isNotEmpty() },
            variants = buildVariants(raw),
            variant = variant,
            tailMark = tailMark?.lowercase()
        )
    }

    /**
     * 生成舰娘名的候选基名（与旧 `AvatarMatcher.buildVariants` 一致）。
     *
     * 覆盖：去 `.改`/`改`/`Kai`、去 `·META`、去 `(μ兵装)`、去 `II`、
     * 去联动后缀（DOA/SSSS/BB/CV/DE/JP）、英文数字前缀（`Z1莉泽洛特`→`Z1`）、
     * `伊13十纱`→`I13`、以及组合去除。
     */
    fun buildVariants(shipName: String): List<String> {
        val variants = LinkedHashSet<String>()

        val noRemodel = when {
            shipName.contains(".改") -> shipName.replace(".改", "")
            shipName.contains("改") -> shipName.replace("改", "")
            shipName.contains("Kai") -> shipName.replace("Kai", "")
            else -> null
        }
        noRemodel?.let { variants.add(it) }

        if (shipName.contains("·META")) variants.add(shipName.replace("·META", ""))
        if (shipName.contains("(μ兵装)")) variants.add(shipName.replace("(μ兵装)", ""))
        if (shipName.endsWith("II")) variants.add(shipName.removeSuffix("II"))

        COLLAB_SUFFIXES.forEach { suffix ->
            if (shipName.endsWith(suffix)) {
                shipName.removeSuffix(suffix).trim().takeIf { it.isNotBlank() }?.let { variants.add(it) }
            }
        }

        extractAlphanumericPrefix(shipName)?.let { variants.add(it) }
        if (shipName.startsWith("伊")) {
            extractAlphanumericPrefix("I${shipName.removePrefix("伊")}")?.let { variants.add(it) }
        }
        if (shipName.contains(".改") && shipName.contains("·META")) {
            variants.add(shipName.replace(".改", "").replace("·META", ""))
        }
        return variants.toList()
    }

    /** 提取开头的英文+数字前缀（`Z1莉泽洛特`→`Z1`、`I13`→`I13`） */
    fun extractAlphanumericPrefix(shipName: String): String? {
        Regex("^([A-Za-z]+[0-9]*)[\\u4e00-\\u9fff]").find(shipName)?.let { match ->
            val prefix = match.groupValues[1]
            if (prefix.any { it.isLetter() }) return prefix
        }
        Regex("^([A-Za-z]+[0-9]+)$").find(shipName)?.let { return it.groupValues[1] }
        return null
    }

    /** 提取尾部英文标记（`霞(DOA)`→`DOA`、`天城CV`→`CV`） */
    fun extractTailMark(shipName: String): String? {
        Regex("[(（]([A-Za-z]{2,6}[0-9]*)[)）]$").find(shipName)?.let { return it.groupValues[1] }
        Regex("^(.*?[\\u4e00-\\u9fff·.])([A-Za-z]{2,6}[0-9]*)$").find(shipName)?.let {
            return it.groupValues[2]
        }
        return null
    }

    // ------------------------------------------------------------------ 匹配

    /**
     * 在 [keys]（小写资源 id / 文件名基名）中为 [query] 找最佳匹配。
     *
     * @return 命中的 key 与命中的级别；无匹配返回 null
     */
    fun match(query: String, keys: Collection<String>, options: Options = Options()): Match? =
        match(query, keys.associateBy { it.trim().lowercase() }, options)

    private fun match(query: String, index: Map<String, String>, options: Options): Match? {
        if (query.isBlank() || index.isEmpty()) return null

        val parsed = parse(query)

        // 0. 整名别名优先（`赤城·META` → midchicheng_alter 这类拼写差异大的先例）
        options.aliases[parsed.raw]?.forEach { alias ->
            index[alias.lowercase()]?.let { return Match(it, "ALIAS", 100) }
        }

        // 1. 特殊形态（META / μ兵装 / 幼女）优先带对应后缀
        when (parsed.variant) {
            Variant.META, Variant.IDOL -> {
                val suffix = if (parsed.variant == Variant.META) ALTER_SUFFIX else IDOL_SUFFIX
                val hit = matchWithSuffix(parsed.variantBase, suffix, index, options)
                if (hit != null) return Match(hit, "SPECIAL", 98)
                // 皮肤形态硬性不回退：META/μ兵装的形象与基础舰娘完全不同，
                // 缺文件时必须交回调用方走网络兜底，绝不能显示基础舰娘
                Log.d(TAG, "[MISS] '$query' (${parsed.variant} 资源缺失，拒绝回退基础头像)")
                return null
            }

            Variant.YOUNV -> {
                // 幼女形态有小船贴图的优先；没有则继续按原名匹配（xiaochicheng）
                matchWithSuffix(parsed.variantBase, YOUNV_SUFFIX, index, options)?.let {
                    return Match(it, "SPECIAL", 98)
                }
            }

            Variant.REMODEL -> {
                val hit = matchWithSuffix(parsed.variantBase, REMODEL_SUFFIX, index, options)
                if (hit != null) return Match(hit, "REMODEL", 97)
                // 改造舰立绘与基础形态不同，缺 `_g` 时不回退（旧实现同款硬性规则）
                Log.d(TAG, "[MISS] '$query' (改造资源缺失，拒绝回退基础头像)")
                return null
            }

            else -> Unit
        }

        // 2. 尾部标记变体（霞(DOA) → xia_doa；缺文件时继续按原名走）
        parsed.tailMark?.let { mark ->
            matchWithTailMark(parsed, mark, index, options)?.let { return Match(it, "TAIL_MARK", 96) }
        }

        // 3. 原名 / 别名精确
        index[parsed.raw.lowercase()]?.let { return Match(it, "EXACT", 94) }
        aliasCandidates(parsed, options).forEach { alias ->
            index[alias.lowercase()]?.let { return Match(it, "ALIAS", 92) }
        }

        // 4. 全拼精确（含变体名的全拼）
        val pinyins = LinkedHashSet<String>().apply {
            add(parsed.pinyin)
            aliasCandidates(parsed, options).forEach { alias ->
                PinyinHelper.toPinyin(alias).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            parsed.variants.forEach { variant ->
                PinyinHelper.toPinyin(variant).takeIf { it.isNotEmpty() }?.let { add(it) }
                options.aliases[variant]?.forEach { add(it.lowercase()) }
                add(variant.lowercase())
            }
        }.filter { it.isNotEmpty() }
        pinyins.forEach { p -> index[p]?.let { return Match(it, "PINYIN", 90) } }

        // 5. 包含匹配（`拼音_联动后缀`，后缀不是皮肤语义）
        pinyins.forEach { p -> containingMatch(p, index)?.let { return Match(it, "CONTAINING", 86) } }

        // 6. 反向包含：候选是拼音前缀（音节边界对齐）
        pinyins.forEach { p ->
            reversePrefixMatch(parsed.core, p, index)?.let { return Match(it, "PREFIX", 84) }
        }

        // 7. 模糊子串（音节对齐 + 占比闸门）
        pinyins.forEach { p ->
            fuzzySubstring(parsed.core, p, index)?.let { return Match(it, "FUZZY", 78) }
        }

        // 8. 首字母缩写（SD 目录 `welxfht`）
        if (options.allowInitials && parsed.initials.length >= 3) {
            index[parsed.initials]?.let { return Match(it, "INITIALS", 74) }
        }

        // 9. 逐字拼音前 N 段（`wuer` + `li` + `xi`）
        if (parsed.segments.size >= 2) {
            for (n in parsed.segments.size downTo 2) {
                val combined = parsed.segments.take(n).joinToString("")
                index[combined]?.let { return Match(it, "SEGMENTS", 70) }
            }
        }

        // 10. 拼写容错（拼音库与资源拼写不一致，且不在别名表里）
        if (options.allowTypo) {
            typoMatch(pinyins, index)?.let { return Match(it, "TYPO", 60) }
        }

        Log.d(TAG, "[MISS] '$query' (pinyin=${parsed.pinyin} variant=${parsed.variant})")
        return null
    }

    /**
     * 誓约头像：优先特殊变体 → 改造 → 婚皮 `_h` → 默认。
     *
     * 与旧 `AvatarMatcher.matchOath` 行为一致：META/μ/幼女没有婚皮，誓约后仍显示该变体；
     * 改造舰誓约后仍应显示改造头像。
     */
    fun matchOath(query: String, keys: Collection<String>, options: Options = Options()): String? =
        matchOath(query, keys.associateBy { it.trim().lowercase() }, options)

    private fun matchOath(query: String, index: Map<String, String>, options: Options): String? {
        if (query.isBlank() || index.isEmpty()) return null

        expectedSuffix(parse(query))?.let { suffix ->
            matchWithSuffix(query, suffix, index, options)?.let { return it }
        }
        val parsed = parse(query)
        if (parsed.variant == Variant.REMODEL) {
            matchWithSuffix(parsed.variantBase, REMODEL_SUFFIX, index, options)?.let { return it }
        }
        matchWithSuffix(query, OATH_SUFFIX, index, options)?.let { return it }
        return match(query, index, options)?.key
    }

    /**
     * 带指定后缀的匹配（`_g` / `_h` / `_alter` / `_idol` / `_younv` / `_doa` …）。
     *
     * 依次尝试：别名+后缀 → 原名+后缀 → 全拼+后缀 → 变体名/变体拼音+后缀 →
     * 「拼音是候选前缀且候选带该后缀」的模糊兜底。
     */
    fun matchWithSuffix(
        query: String,
        suffix: String,
        keys: Collection<String>,
        options: Options = Options()
    ): String? = matchWithSuffix(query, suffix, keys.associateBy { it.trim().lowercase() }, options)

    private fun matchWithSuffix(
        query: String,
        suffix: String,
        index: Map<String, String>,
        options: Options
    ): String? {
        val parsed = parse(query)
        val bases = LinkedHashSet<String>().apply {
            add(parsed.variantBase)
            add(parsed.raw)
            add(parsed.core)
            addAll(parsed.variants)
        }
        val suffixKey = suffix.lowercase()

        // 1. 别名 + 后缀（`滨风.改` → bangfeng_g）
        bases.forEach { base ->
            options.aliases[base]?.forEach { alias ->
                index["${alias.lowercase()}$suffixKey"]?.let { return it }
            }
            index["${base.lowercase()}$suffixKey"]?.let { return it }
            val pinyin = PinyinHelper.toPinyin(base)
            if (pinyin.isNotEmpty()) index["$pinyin$suffixKey"]?.let { return it }
        }

        // 2. 模糊：候选以「拼音」为音节对齐子串且以该后缀结尾
        //    覆盖「朱利奥·凯撒·META」→ kaisa_alter（kaisa 是 zhuliaokaisa 的音节对齐子串）
        bases.forEach { base ->
            val pinyin = PinyinHelper.toPinyin(base)
            if (pinyin.length < 5) return@forEach
            val best = index.keys.asSequence()
                .filter { it.endsWith(suffixKey) && it.length > suffixKey.length + 3 }
                .mapNotNull { key ->
                    val stem = key.removeSuffix(suffixKey)
                    if (PinyinHelper.isSyllableAligned(base, pinyin, stem) && pinyin.contains(stem)) {
                        stem to key
                    } else {
                        null
                    }
                }
                .maxByOrNull { it.first.length }
            best?.let { return index[it.second] }
        }
        return null
    }

    // ------------------------------------------------------------------ 内部策略

    /** 变体 → 期望的资源后缀 */
    private fun expectedSuffix(parsed: Parsed): String? = when (parsed.variant) {
        Variant.META -> ALTER_SUFFIX
        Variant.IDOL -> IDOL_SUFFIX
        Variant.YOUNV -> YOUNV_SUFFIX
        else -> null
    }

    private fun aliasCandidates(parsed: Parsed, options: Options): List<String> {
        val out = LinkedHashSet<String>()
        options.aliases[parsed.raw]?.let { out.addAll(it) }
        options.aliases[parsed.core]?.let { out.addAll(it) }
        parsed.variants.forEach { variant ->
            options.aliases[variant]?.forEach { out.add(it.lowercase()) }
        }
        return out.toList()
    }

    /** 尾部标记：`霞(DOA)` → `xia_doa`（先别名，再全拼，再变体） */
    private fun matchWithTailMark(
        parsed: Parsed,
        mark: String,
        index: Map<String, String>,
        options: Options
    ): String? {
        val suffix = "_${mark.lowercase()}"
        val bases = LinkedHashSet<String>().apply {
            add(parsed.core)
            addAll(parsed.variants)
        }
        bases.forEach { base ->
            options.aliases[base]?.forEach { alias ->
                index["${alias.lowercase()}$suffix"]?.let { return it }
            }
            index["${base.lowercase()}$suffix"]?.let { return it }
            val pinyin = PinyinHelper.toPinyin(base)
            if (pinyin.isNotEmpty()) index["$pinyin$suffix"]?.let { return it }
        }
        return null
    }

    /** 候选以 `拼音_` 开头且后缀不是皮肤语义（`suixiang_doa` / `lala_tolove`） */
    private fun containingMatch(pinyin: String, index: Map<String, String>): String? {
        if (pinyin.length < 3) return null
        val prefix = "${pinyin}_"
        val hit = index.keys.firstOrNull { key ->
            key.startsWith(prefix) && !isSkinSuffix(key.substringAfter('_'))
        }
        return hit?.let { index[it] }
    }

    /** 候选是拼音前缀（`wuerlixi` ← wuerlixifenghuteng），必须音节边界对齐 */
    private fun reversePrefixMatch(core: String, pinyin: String, index: Map<String, String>): String? {
        if (pinyin.length < 5) return null
        var best: Pair<String, String>? = null
        index.keys.forEach { key ->
            val parts = keyParts(key)
            // 皮肤形态（_h/_g/_alter/_idol/_younv/_N）不参与默认匹配；
            // 联动标记（_tolove/_doa）要参与——`mengmeng_tolove` 就是梦梦的唯一资源
            if (parts.variant != Variant.DEFAULT && parts.variant != Variant.TAIL_MARK) return@forEach
            val stem = parts.core
            if (stem.length < 4) return@forEach
            if (!pinyin.startsWith(stem)) return@forEach
            if (!PinyinHelper.isSyllableBoundaryAt(core, pinyin, stem.length)) return@forEach
            val current = best
            if (current == null || stem.length > current.first.length) best = stem to key
        }
        return best?.second?.let { index[it] }
    }

    /** 拼音中的音节对齐子串（`qinli` ← 五河琴里） */
    private fun fuzzySubstring(core: String, pinyin: String, index: Map<String, String>): String? {
        if (pinyin.length < 5) return null
        val hit = index.keys.asSequence()
            .filter { key ->
                !key.contains('_') && key.length >= 4 && pinyin.contains(key) &&
                    PinyinHelper.isSyllableAligned(core, pinyin, key) &&
                    (key.length >= 6 || key.length.toFloat() / pinyin.length >= 0.4f)
            }
            .maxByOrNull { it.length }
        return hit?.let { index[it] }
    }

    /** 拼写容错：编辑距离 ≤1（长度 ≥5）/ ≤2（长度 ≥8），仅无后缀候选 */
    private fun typoMatch(pinyins: Collection<String>, index: Map<String, String>): String? {
        var best: Pair<String, Int>? = null
        index.keys.forEach { key ->
            val parts = keyParts(key)
            if (parts.suffixTokens.isNotEmpty()) return@forEach
            if (key.length < 5) return@forEach
            val limit = if (key.length >= 8) 2 else 1
            pinyins.forEach { pinyin ->
                if (abs(key.length - pinyin.length) > limit) return@forEach
                val distance = damerauLevenshtein(key, pinyin, limit)
                if (distance in 1..limit) {
                    val current = best
                    if (current == null || distance < current.second) best = key to distance
                }
            }
        }
        return best?.first?.let { index[it] }
    }

    /** Damerau-Levenshtein（带提前退出；相邻换位算一次编辑） */
    private fun damerauLevenshtein(a: String, b: String, max: Int): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        for (i in 1..n) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = min(min(current[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = min(value, prevPrev[j - 2] + 1)
                }
                current[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > max) return max + 1
            val recycled = prevPrev
            prevPrev = prev
            prev = current
            current = recycled
        }
        return prev[m]
    }

    // ------------------------------------------------------------------ 常量

    private const val OATH_SUFFIX = "_h"
    private const val REMODEL_SUFFIX = "_g"
    private const val ALTER_SUFFIX = "_alter"
    private const val IDOL_SUFFIX = "_idol"
    private const val YOUNV_SUFFIX = "_younv"

    private val OATH_TOKENS = setOf("h")
    private val REMODEL_TOKENS = setOf("g")
    private val ALTER_TOKENS = setOf("alter")
    private val IDOL_TOKENS = setOf("idol")
    private val YOUNV_TOKENS = setOf("younv")
    private val SUPER_TOKENS = setOf("super")
    private val SKIN_TOKENS = setOf("y", "r")

    /** 尾部联动标记（出现在 `拼音_标记` 里） */
    private val TAIL_TOKENS = setOf(
        "doa", "cv", "jp", "bb", "de", "ssss", "tolove", "dark", "ger", "shanluan", "new"
    )

    private val COLLAB_SUFFIXES = listOf("DOA", "SSSS", "BB", "CV", "DE", "JP")

    /** 是否皮肤后缀（`_h` / `_g` / `_alter` / `_2` …） */
    fun isSkinSuffix(suffix: String): Boolean {
        if (suffix.isEmpty()) return false
        if (suffix.all { it.isDigit() }) return true
        return suffix in OATH_TOKENS || suffix in REMODEL_TOKENS || suffix in ALTER_TOKENS ||
            suffix in IDOL_TOKENS || suffix in YOUNV_TOKENS || suffix in SUPER_TOKENS ||
            suffix == "hx" || suffix in SKIN_TOKENS
    }

    /**
     * 手写别名表：中文名 → 拼音候选（**有序**）。
     *
     * 合并自头像表（1004 舰全量回归 + 零撞车审计）与 SD 表（补两条头像表缺的）。
     * 冲突项保留双候选：按顺序尝试，以索引中实际存在者为准。
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "滨风" to listOf("bangfeng"),
        "库珀" to listOf("kubo"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "贝露" to listOf("HDN401", "peineiluopo"),
        "第二代" to listOf("erdaimu"),
        "双海亚美" to listOf("yamei"),
        "八舞耶倶矢·八舞夕弦" to listOf("bawu"),
        "卡菈·伊迪亚斯" to listOf("kala"),
        "莉拉·德西亚斯" to listOf("lila"),
        "艾菈·冯·杜勒" to listOf("aila"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "乌戈里诺·维瓦尔迪" to listOf("wugelini", "linuo"),
        "葛兹·冯·伯利欣根" to listOf("gezi"),
        "新条茜" to listOf("qian"),
        "三浦梓" to listOf("zi"),
        "22" to listOf("22"),
        "33" to listOf("33"),
        "2B" to listOf("2b"),
        "苏维埃同盟" to listOf("suweiaitongmengnew"),
        "拉·加利索尼埃" to listOf("jialisuoniye"),
        "拉·加利索尼埃·META" to listOf("jialisuoniye_alter"),
        "特装型布里MKIII" to listOf("buli_super"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "试作型布里MKII" to listOf("kin", "buli_super"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "泛用型布里" to listOf("gin", "buli_super"),
        "玛丽·西莱斯特号" to listOf("mali"),
        "威廉·D·波特" to listOf("bote"),
        "小腓特烈" to listOf("feiteliedadi_younv"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "小斯佩" to listOf("sipeibojue_younv", "speibojue_younv"),
        // 两套旧表不一致：按顺序尝试，以索引中实际存在者为准
        "小贝法" to listOf("beierfasite_younv", "beifashen_younv"),
        "朝凪" to listOf("zhaozhi"),
        "酒匂" to listOf("jiuyun"),
        "雫" to listOf("na_doa"),
        "曾克海军上将" to listOf("zengkehaijunshangjiang"),
        "BLACK★ROCK" to listOf("heiyansheshou"),
        "天城CV" to listOf("tiancheng_cv"),
        "赤城·META" to listOf("midchicheng_alter"),
        "倔强" to listOf("juejiang"),
        "如月千早" to listOf("qianzao"),
        "英王乔治五世" to listOf("qiaozhiwushi"),
        "加贺BB" to listOf("jiahezhanlie"),
        "约克DE" to listOf("yueke_ger"),
        "重剑" to listOf("zhongjian"),
        "飞鸟川千濑" to listOf("qianlai"),
        "朴茨茅斯冒险号" to listOf("pucimaosi"),
        "印第安纳波利斯" to listOf("bolisi"),
        "紫" to listOf("zi_shanluan"),
        "DEAD" to listOf("siwangzhuzai"),
        "绊爱" to listOf("aijiangDD"),
        "绊爱·Elegant" to listOf("aijiangCL"),
        "绊爱·Anniversary" to listOf("aijiangCV"),
        "绊爱·SuperGamer" to listOf("aijiangBB"),
        "凪咲" to listOf("zhixiao_DOA"),
        "涅普顿" to listOf("HDN101"),
        "绀紫之心" to listOf("HDN102_1"),
        "诺瓦露" to listOf("HDN201"),
        "圣黑之心" to listOf("HDN202_1"),
        "布兰" to listOf("HDN301"),
        "群白之心" to listOf("HDN302_1"),
        "翡绿之心" to listOf("HDN402_1"),
        "博格" to listOf("boge")
    )

    /**
     * SD 侧补充别名（不进共享表）：只在 SD 资源匹配里生效。
     *
     * `哈里森` 与 `莫里森` 共用 `molisen` 目录，但船坞头像只有一个 `molisen.webp`，
     * 放进共享表会触发「两舰命中同一头像」的撞车审计；`博格` 的拼音本就等于目录名。
     */
    val SD_EXTRA_ALIASES: Map<String, List<String>> = ALIASES + mapOf(
        "哈里森" to listOf("molisen"),
        "博格" to listOf("boge")
    )
}
