package com.azurlane.blyy.util

/**
 * wiki 舰船页语音表与皮肤的对齐规则（船坞立绘改名 / Live2D 互动语音共用）。
 *
 * wiki 语音表按皮肤分表，data-title 为真实皮肤名；实测约定：
 *  - **未命名表**（data-title 缺失 → 解析层回退 [DEFAULT_NAME]）全部属于默认装扮，
 *    且默认皮肤的台词可能拆成多张未命名表（如 Z23 / 埃尔德里奇各有两张）
 *  - 实名表按页序依次对应 换装1、换装2、…（与资源目录 _N 编号对齐：换装k = 资源 _k+1；
 *    实证：埃尔德里奇换装3 = 正月的牵手 = live2d 资源 aierdeliqi_4 渲染的和服模型）
 *  - 【誓约】xxx 表对应立绘"誓约"tab，xxx.改 表对应"改造"tab，均不占用换装序号
 *  - "X（特殊形态）"表是皮肤 X 的战斗变身形态（实证：伊404 = 绛縢华舞 / 绛縢华舞（特殊形态）），
 *    不占用换装序号
 *
 * 纯 Kotlin 无 Android 依赖，可 JVM 单测。
 */
object SkinVoiceIndex {

    /** 未命名表的回退名（与 ShipRepository 解析层约定一致） */
    const val DEFAULT_NAME = "默认装扮"

    private const val OATH_PREFIX = "【誓约】"
    private const val REMODEL_SUFFIX = ".改"
    private const val SPECIAL_FORM_SUFFIX = "（特殊形态）"

    /** "X（特殊形态）" → X；其余原样返回 */
    fun stripSpecialFormSuffix(name: String): String =
        if (name.endsWith(SPECIAL_FORM_SUFFIX)) name.removeSuffix(SPECIAL_FORM_SUFFIX) else name

    fun isOathTable(name: String): Boolean = name.startsWith(OATH_PREFIX)

    fun isRemodelTable(name: String): Boolean = name.endsWith(REMODEL_SUFFIX)

    /** 语音表分类结果（表名均为解析回退后的名字，按页序） */
    data class SkinTables(
        /** 默认装扮的表名集合：首表名 + 全部未命名回退表 */
        val defaultNames: Set<String>,
        /** 换装序列：第 k 项即"换装k"对应的真实皮肤名 */
        val skinSequence: List<String>,
        /** 誓约皮肤表名（立绘"誓约"tab 对应） */
        val oathNames: List<String>,
        /** 改造皮肤表名（立绘"改造"tab 对应） */
        val remodelNames: List<String>
    )

    fun classify(titles: List<String>): SkinTables {
        if (titles.isEmpty()) {
            return SkinTables(emptySet(), emptyList(), emptyList(), emptyList())
        }
        // 输入可能是"每行台词"的 skinName 序列（同一张表的 N 行连续同名），
        // 先归一"特殊形态"后缀，再折叠连续同名运行——每段连续同名视为一张表。
        // 归一让 "X" 与 "X（特殊形态）" 相邻运行折叠为同一皮肤，不占换装序号
        val tables = titles.map { stripSpecialFormSuffix(it) }.fold(mutableListOf<String>()) { acc, name ->
            if (acc.lastOrNull() != name) acc.add(name)
            acc
        }
        // 未命名表（回退名）全部属于默认装扮，不占用换装序号；
        // 实名表按页序依次对应 换装1、换装2、…（实测：埃尔德里奇换装3=正月的牵手，
        // 与 live2d 资源 aierdeliqi_4 渲染的和服模型一致）。
        // distinct() 兜底同名皮肤表非连续出现时（页面结构异常）不重复占位
        val skinSequence = tables.filter { name ->
            name != DEFAULT_NAME && !isOathTable(name) && !isRemodelTable(name)
        }.distinct()
        return SkinTables(
            defaultNames = setOf(DEFAULT_NAME),
            skinSequence = skinSequence,
            oathNames = tables.filter { isOathTable(it) },
            remodelNames = tables.filter { isRemodelTable(it) }
        )
    }
}
