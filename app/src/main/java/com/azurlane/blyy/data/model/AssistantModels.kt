package com.azurlane.blyy.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 碧蓝航线小助手 — 数据模型
 *
 * 对应 main.py 中 B站碧蓝航线 API 的响应结构
 */

// ── 服务器映射 ──

/** 服务器信息：全名 + ID */
data class AzurLaneServer(
    val fullName: String,
    val id: String
)

/**
 * 服务器映射表，与 main.py 中 server_map 完全一致
 * key 为服务器简称/关键词，value 为 (全名, ID)
 */
val AZUR_LANE_SERVER_MAP: Map<String, Pair<String, String>> = mapOf(
    "莱茵演习" to Pair("官网1服-莱茵演习", "101"),
    "夏威夷" to Pair("ios1服-夏威夷", "201"),
    "巴巴罗萨" to Pair("官网2服-巴巴罗萨", "102"),
    "珊瑚海" to Pair("ios2服-珊瑚海", "202"),
    "霸王行动" to Pair("官网3服-霸王行动", "103"),
    "中途岛" to Pair("ios3服-中途岛", "203"),
    "冰山行动" to Pair("官网4服-冰山行动", "104"),
    "铁底湾" to Pair("ios4服-铁底湾", "204"),
    "彩虹计划" to Pair("官网5服-彩虹计划", "105"),
    "所罗门" to Pair("ios5服-所罗门", "205"),
    "发电机计划" to Pair("官网6服-发电机计划", "106"),
    "马里亚纳" to Pair("ios6服-马里亚纳", "206"),
    "瞭望台行动" to Pair("官网7服-瞭望台行动", "107"),
    "莱特湾" to Pair("ios7服-莱特湾", "207"),
    "十字路口行动" to Pair("官网8服-十字路口行动", "108"),
    "硫磺岛" to Pair("ios8服-硫磺岛", "208"),
    "朱诺行动" to Pair("官网9服-朱诺行动", "109"),
    "冲绳岛" to Pair("ios9服-冲绳岛", "209"),
    "杜立特空袭" to Pair("官网10服-杜立特空袭", "110"),
    "阿留申群岛" to Pair("ios10服-阿留申群岛", "210"),
    "地狱犬行动" to Pair("官网11服-地狱犬行动", "111"),
    "马耳他" to Pair("ios11服-马耳他", "211"),
    "开罗宣言" to Pair("官网12服-开罗宣言", "112"),
    "奥林匹克行动" to Pair("官网13服-奥林匹克行动", "113"),
    "小王冠行动" to Pair("官网14服-小王冠行动", "114"),
    "波茨坦公告" to Pair("官网15服-波茨坦公告", "115"),
    "白色方案" to Pair("官网16服-白色方案", "116"),
    "瓦尔基里行动" to Pair("官网17服-瓦尔基里行动", "117"),
    "曼哈顿计划" to Pair("官网18服-曼哈顿计划", "118"),
    "八月风暴" to Pair("官网19服-八月风暴", "119"),
    "秋季旅行" to Pair("官网20服-秋季旅行", "120"),
    "水星行动" to Pair("官网21服-水星行动", "121"),
    "莱茵河卫兵" to Pair("官网22服-莱茵河卫兵", "122"),
    "北极光计划" to Pair("官网23服-北极光计划", "123"),
    "长戟计划" to Pair("官网24服-长戟计划", "124"),
    "暴雨行动" to Pair("官网25服-暴雨行动", "125"),
    "水仙行动" to Pair("官网26服-水仙行动", "126"),
    "冬月计划" to Pair("官网27服-冬月计划", "127"),
    "长弓计划" to Pair("官网28服-长弓计划", "128"),
    "裁决协议" to Pair("官网29服-裁决协议", "129")
)

/**
 * 解析服务器名称/ID，与 main.py 中 _resolve_server 逻辑一致
 * @param name 服务器名称（支持简称/全称/模糊匹配）或数字ID
 * @return 解析结果 AzurLaneServer，未匹配返回 null
 */
fun resolveServer(name: String?): AzurLaneServer? {
    if (name.isNullOrBlank()) return null
    val nameStr = name.trim()
    // 纯数字：按 ID 查找
    if (nameStr.all { it.isDigit() }) {
        for ((_, v) in AZUR_LANE_SERVER_MAP) {
            if (v.second == nameStr) return AzurLaneServer(v.first, v.second)
        }
        return AzurLaneServer("ID:$nameStr", nameStr)
    }
    // 文字：模糊匹配
    for ((key, v) in AZUR_LANE_SERVER_MAP) {
        if (key.contains(nameStr)) return AzurLaneServer(v.first, v.second)
    }
    return null
}

// ── API 响应模型 ──

/** 用户详情 API 顶层响应 */
@Serializable
data class UserDetailResponse(
    val code: Int = -1,
    val message: String = "",
    val data: UserDetailData? = null
)

@Serializable
data class UserDetailData(
    val user_info: UserInfo = UserInfo(),
    val statistics: Statistics = Statistics(),
    val progress_tracking: ProgressTracking = ProgressTracking(),
    val combat_overview: CombatOverview = CombatOverview()
)

@Serializable
data class UserInfo(
    val nickname: String = "",
    val level: Int = 0,
    val server: String = "",
    val uid: String = "",
    val guild_name: String? = null
)

@Serializable
data class Statistics(
    val collection_rate: String = "",
    val mainline_progress: String = "",
    val coins_current: Int = 0,
    val oil_current: Int = 0,
    val food_current: Int = 0
)

@Serializable
data class ProgressTracking(
    val commissions: CommissionProgress = CommissionProgress(),
    val research: ResearchProgress = ResearchProgress()
)

@Serializable
data class CommissionProgress(
    val in_progress: Int = 0,
    val completed: Int = 0,
    val idle: Int = 0
)

@Serializable
data class ResearchProgress(
    val in_progress: Int = 0,
    val completed: Int = 0,
    val idle: Int = 0
)

@Serializable
data class CombatOverview(
    val exercise: ExerciseInfo = ExerciseInfo(),
    val daily_challenges: List<DailyChallenge> = emptyList()
)

@Serializable
data class ExerciseInfo(
    val today_remaining: Int = 0
)

@Serializable
data class DailyChallenge(
    val daily_challenge_name: String = "",
    val daily_challenge_remaining_attempts: Int = 0
)

/** 建造记录 API 顶层响应 */
@Serializable
data class BuildRecordResponse(
    val code: Int = -1,
    val message: String = "",
    val data: BuildRecordData? = null
)

@Serializable
data class BuildRecordData(
    val nickname: String = "",
    val uid: String = "",
    @SerialName("serverName") val serverName: String = "",
    val buildRecords: BuildRecords = BuildRecords()
)

@Serializable
data class BuildRecords(
    val total_count: Int = 0,
    val data: List<BuildRecordItem> = emptyList()
)

@Serializable
data class BuildRecordItem(
    val roleName: String = "",
    val taskName: String = ""
)
