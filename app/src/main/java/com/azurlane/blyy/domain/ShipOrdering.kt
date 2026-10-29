package com.azurlane.blyy.domain

/**
 * 舰娘列表展示排序的共享约定（与 ShipDao 查询里的稀有度 CASE 顺序保持一致）。
 * 生日日历 ViewModel 与生日小组件共用，避免多处 when 分叉后悄悄不一致。
 */
object ShipOrdering {

    /** 稀有度排序键（数字越小越稀有；未知稀有度排最后） */
    fun rarityRank(rarity: String): Int = when (rarity) {
        "海上传奇", "决战方案", "三星" -> 1
        "超稀有", "最高方案", "二星" -> 2
        "精锐", "一星" -> 3
        "稀有" -> 4
        "普通" -> 5
        else -> 6
    }
}
