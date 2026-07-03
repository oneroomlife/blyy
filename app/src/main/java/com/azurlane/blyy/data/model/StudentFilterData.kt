package com.azurlane.blyy.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 学生档案筛选数据 — 对应 gamekee 学生页面的 14 个筛选维度
 *
 * 存储为 JSON 字符串在 Ship.filterData 字段中，仅在 archiveType == STUDENT 时有效。
 * 序列化/反序列化通过 [toJson] / [fromJson] 完成。
 */
@Immutable
@Serializable
data class StudentFilterData(
    /** 星级：1星、2星、3星 */
    @SerialName("star") val starRating: String = "",
    /** 限定/常驻：限定、常驻 */
    @SerialName("limited") val limitedType: String = "",
    /** 攻击类型：爆炸、贯通、神秘、振击 */
    @SerialName("attack") val attackType: String = "",
    /** 防御类型：轻装甲、重装甲、特殊装甲 */
    @SerialName("defense") val defenseType: String = "",
    /** 编队位置：前排、后排 */
    @SerialName("formation") val formationPosition: String = "",
    /** 战斗职责：输出、支援、坦克 */
    @SerialName("role") val combatRole: String = "",
    /** 站位前后：前、后 */
    @SerialName("position") val frontBackColor: String = "",
    /** 武器种类：HG、SMG、AR、SG、SR、MG、RL、GL、FT */
    @SerialName("weapon") val weaponType: String = "",
    /** 市街适应：S、A、B、C、D */
    @SerialName("street") val streetAdapt: String = "",
    /** 室外适应：S、A、B、C、D */
    @SerialName("outdoor") val outdoorAdapt: String = "",
    /** 室内适应：S、A、B、C、D */
    @SerialName("indoor") val indoorAdapt: String = "",
    /** 掩体互动：高、中、低 */
    @SerialName("cover") val coverInteract: String = "",
    /** 就读学校 */
    @SerialName("school") val school: String = "",
    /** 所在社团 */
    @SerialName("club") val club: String = ""
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun fromJson(jsonStr: String): StudentFilterData? {
            if (jsonStr.isBlank()) return null
            return try {
                json.decodeFromString<StudentFilterData>(jsonStr)
            } catch (e: Exception) {
                null
            }
        }

        /** 14 个筛选维度的元数据定义 — 用于 UI 动态生成筛选选项 */
        val FILTER_CATEGORIES = listOf(
            FilterCategory("starRating", "星级", listOf("1星", "2星", "3星")),
            FilterCategory("limitedType", "限定/常驻", listOf("限定", "常驻")),
            FilterCategory("attackType", "攻击类型", listOf("爆炸", "贯通", "神秘", "振击")),
            FilterCategory("defenseType", "防御类型", listOf("轻装甲", "重装甲", "特殊装甲")),
            FilterCategory("formationPosition", "编队位置", listOf("前排", "后排")),
            FilterCategory("combatRole", "战斗职责", listOf("输出", "支援", "坦克")),
            FilterCategory("frontBackColor", "站位前后", listOf("前", "后")),
            FilterCategory("weaponType", "武器种类", listOf("HG", "SMG", "AR", "SG", "SR", "MG", "RL", "GL", "FT")),
            FilterCategory("streetAdapt", "市街适应", listOf("S", "A", "B", "C", "D")),
            FilterCategory("outdoorAdapt", "室外适应", listOf("S", "A", "B", "C", "D")),
            FilterCategory("indoorAdapt", "室内适应", listOf("S", "A", "B", "C", "D")),
            FilterCategory("coverInteract", "掩体互动", listOf("高", "中", "低")),
            FilterCategory("school", "就读学校", listOf(
                "阿拜多斯", "格黑娜", "千禧年", "崔尼蒂", "歌赫娜",
                "赤冬", "百鬼夜行", "山海经", "瓦尔基里", "SRT",
                "阿里乌斯", "其他"
            )),
            FilterCategory("club", "所在社团", listOf(
                "实现正义委员会", "风纪委员会", "游戏开发部", "圣三一自治区域",
                "温讬科珀斯", "RABBIT小队", "其他"
            ))
        )
    }

    fun toJson(): String = try {
        json.encodeToString(this)
    } catch (e: Exception) {
        ""
    }

    /**
     * 根据筛选维度 key 获取对应字段值
     * @param key FilterCategory.key
     */
    fun getFieldValue(key: String): String = when (key) {
        "starRating" -> starRating
        "limitedType" -> limitedType
        "attackType" -> attackType
        "defenseType" -> defenseType
        "formationPosition" -> formationPosition
        "combatRole" -> combatRole
        "frontBackColor" -> frontBackColor
        "weaponType" -> weaponType
        "streetAdapt" -> streetAdapt
        "outdoorAdapt" -> outdoorAdapt
        "indoorAdapt" -> indoorAdapt
        "coverInteract" -> coverInteract
        "school" -> school
        "club" -> club
        else -> ""
    }
}

/**
 * 筛选维度元数据
 * @param key 字段标识，对应 StudentFilterData 的属性名
 * @param label UI 显示名称
 * @param options 可选值列表
 */
@Immutable
data class FilterCategory(
    val key: String,
    val label: String,
    val options: List<String>
)
