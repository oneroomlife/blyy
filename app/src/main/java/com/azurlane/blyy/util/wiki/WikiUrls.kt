package com.azurlane.blyy.util.wiki

import android.net.Uri

/**
 * 碧蓝航线 wiki 页面地址的唯一拼装入口。
 *
 * 抽出来的原因：语音/立绘/档案/详情**四条抓取链路**都要拼同一个 URL，而名字归一化
 * （`.改` / `改` / `Kai` 后缀、`DEAD` / `BLACK★ROCK` 特例）一旦在四处各写一份，
 * 改规则时必然漏改。现在 `ShipRepository` 与 `ShipDetailRepository` 都走这里。
 */
object WikiUrls {

    const val BASE = "https://wiki.biligame.com/blhx/"

    /** wiki 页面名与游戏内显示名不一致的特例（顺序无关，精确匹配） */
    private val NAME_MAPPING = mapOf(
        "DEAD" to "DEAD_MASTER",
        "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
    )

    /** 去掉改造后缀并套用特例映射 */
    fun normalize(shipName: String): String {
        var processed = shipName
        when {
            processed.contains(".改") -> processed = processed.replace(".改", "")
            processed.contains("改") -> processed = processed.replace("改", "")
            processed.contains("Kai") -> processed = processed.replace("Kai", "")
        }
        return (NAME_MAPPING[processed] ?: processed).trim()
    }

    /** 舰娘详情页（已 URL 编码） */
    fun page(shipName: String): String = BASE + Uri.encode(normalize(shipName))

    /** 图鉴总表（抓名录用） */
    fun shipList(): String = BASE + Uri.encode("舰船图鉴")
}
