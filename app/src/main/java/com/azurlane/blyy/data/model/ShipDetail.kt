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

/** 一条技能（description 可能为空——wiki 有些页面把描述放在折叠块里） */
@Serializable
data class SkillRow(
    val name: String,
    val description: String = ""
)
