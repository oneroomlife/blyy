package com.azurlane.blyy.data.model

import kotlinx.serialization.Serializable

/**
 * 舰娘详情（属性 / 技能 / 装备 / CV / 画师 / 获取方式）。
 *
 * 数据来源是 wiki 舰船页，属于**半结构化**数据：页面改版时解析可能只拿到一部分，
 * 因此所有字段都有默认值，UI 需要能接受"部分为空"。这份模型同时用于：
 * 1. 站内详情页（替代"点 Wiki 跳浏览器"）；
 * 2. 啾信 RAG 的事实源（后续 E4 项）；
 * 3. `ship_cache.detailJson` 的持久化格式。
 */
@Serializable
data class ShipDetail(
    /** 声优（wiki 通常写作 CV / 声优） */
    val cv: String = "",
    /** 画师 */
    val illustrator: String = "",
    /** 属性表（耐久/火力/雷击/防空/航空/装填/命中/机动/航速/消耗…，键值与顺序以 wiki 为准） */
    val stats: List<StatRow> = emptyList(),
    /** 技能 */
    val skills: List<SkillRow> = emptyList(),
    /** 装备栏说明（如"初始装备：xxx"） */
    val equipment: List<String> = emptyList(),
    /** 获取方式 */
    val acquire: String = "",
    /** 抓取时间戳（0 = 未知/未抓过） */
    val fetchedAt: Long = 0L
) {
    val isEmpty: Boolean
        get() = cv.isBlank() && illustrator.isBlank() && stats.isEmpty() &&
            skills.isEmpty() && equipment.isEmpty() && acquire.isBlank()

    /** UI 顶部摘要用：有 CV 时优先展示 CV */
    val subtitle: String
        get() = buildList {
            if (cv.isNotBlank()) add("CV $cv")
            if (illustrator.isNotBlank()) add("画师 $illustrator")
        }.joinToString(" · ")
}

/** 一条属性（label = 耐久，value = 1234；value 保留原文，含"→ 改造后"等写法） */
@Serializable
data class StatRow(
    val label: String,
    val value: String
)

/**
 * 一条技能（description 可能为空——wiki 有些页面把描述放在折叠块里）。
 *
 * [iconUrl] 与 [badge] 是「识技能」玩法（GuessMode.SKILL）的题面数据：
 * wiki 技能表的每个技能名单元格里都嵌着 `Skillicon_xxx.png` 图标，直接取原图即可。
 * 两个字段都带默认值 —— 旧版本落库的 `ship_cache.detailJson` 没有它们，
 * 反序列化时按空串补齐，不会因为新增字段而让缓存整体失效。
 */
@Serializable
data class SkillRow(
    val name: String,
    val description: String = "",
    /**
     * 技能图标直链（patchwiki 原图优先，128×128 PNG）。
     * 空串 = 该技能在 wiki 上没有图标（隐藏模板行/空槽），不能作为出题素材。
     */
    val iconUrl: String = "",
    /** 技能标记：`""` 普通 / `"兵装强化"` 被特殊兵装强化过（描述带"专属特殊兵装强化"） */
    val badge: String = ""
) {
    /** 是否具备出题条件（有名字且有图标） */
    val isGuessable: Boolean
        get() = name.isNotBlank() && iconUrl.isNotBlank()
}
