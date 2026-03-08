package com.example.blyy.domain

import com.example.blyy.data.local.ShipDao
import com.example.blyy.data.model.Ship
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 基于舰娘稀有度的权重随机选择算法。
 * 稀有度越高权重越大，提高被随机选中的概率。
 * 使用加权随机算法（Weighted Random Selection）实现。
 */
class SelectSecretaryUseCase @Inject constructor(
    private val shipDao: ShipDao
) {
    companion object {
        /** 稀有度权重：数值越大被选中概率越高（稀有度越高权重越大） */
        private val RARITY_WEIGHTS = mapOf(
            "海上传奇" to 10,
            "决战方案" to 10,
            "超稀有" to 8,
            "最高方案" to 8,
            "精锐" to 6,
            "稀有" to 4,
            "普通" to 2
        )
        private const val DEFAULT_WEIGHT = 6
    }

    /**
     * 从船坞舰娘中按稀有度权重随机选择一名秘书舰
     */
    suspend fun selectRandomSecretary(): Ship? {
        val ships = shipDao.getShipsByArchiveType("DOCK").first()
        if (ships.isEmpty()) return null
        return selectByWeight(ships)
    }

    /**
     * 从指定舰娘列表中按稀有度权重随机选择（加权随机算法）
     * 算法说明：
     * 1. 计算所有舰娘的权重总和
     * 2. 在 [0, totalWeight) 范围内生成随机数
     * 3. 遍历舰娘列表，逐个减去权重，当随机数小于 0 时，当前舰娘即为选中结果
     * 4. 权重越大的舰娘，被选中的概率越高
     */
    fun selectByWeight(ships: List<Ship>): Ship? {
        if (ships.isEmpty()) return null
        
        val totalWeight = ships.sumOf { getWeight(it.rarity) }
        
        if (totalWeight <= 0) {
            return ships.random()
        }
        
        var random = (0 until totalWeight).random()
        
        for (ship in ships) {
            random -= getWeight(ship.rarity)
            if (random < 0) {
                return ship
            }
        }
        
        return ships.last()
    }

    /**
     * 获取指定稀有度的权重值
     * @param rarity 稀有度字符串
     * @return 权重值，如果未配置则返回默认权重
     */
    private fun getWeight(rarity: String): Int = RARITY_WEIGHTS[rarity] ?: DEFAULT_WEIGHT
}
