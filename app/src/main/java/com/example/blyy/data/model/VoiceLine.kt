package com.example.blyy.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class VoiceLine(
    val skinName: String,
    val scene: String,
    val dialogue: String,
    val audioUrl: String
)

@Immutable
data class ShipGallery(
    val shipName: String,
    val illustrations: List<Pair<String, String>> = emptyList(),
    val figures: List<Pair<String, String>> = emptyList()
)

@Immutable
data class SkinFigure(
    val skinName: String,
    val figureUrl: String
)

@Immutable
data class ShipCharacterInfo(
    val shipName: String,
    val identity: String = "",
    val personality: String = "",
    val keywords: String = "",
    val possessions: String = "",
    val hairColor: String = "",
    val eyeColor: String = "",
    val moePoints: String = ""
) {
    fun hasAnyInfo(): Boolean {
        return identity.isNotEmpty() ||
               personality.isNotEmpty() || 
               keywords.isNotEmpty() || 
               possessions.isNotEmpty() || 
               hairColor.isNotEmpty() || 
               eyeColor.isNotEmpty() || 
               moePoints.isNotEmpty()
    }
    
    fun getAvailableHints(usedLabels: Set<String>): List<Pair<String, String>> {
        val hints = mutableListOf<Pair<String, String>>()
        if (identity.isNotEmpty() && "身份" !in usedLabels) hints.add("身份" to identity)
        if (personality.isNotEmpty() && "性格" !in usedLabels) hints.add("性格" to personality)
        if (keywords.isNotEmpty() && "关键词" !in usedLabels) hints.add("关键词" to keywords)
        if (possessions.isNotEmpty() && "持有物" !in usedLabels) hints.add("持有物" to possessions)
        if (hairColor.isNotEmpty() && "发色" !in usedLabels) hints.add("发色" to hairColor)
        if (eyeColor.isNotEmpty() && "瞳色" !in usedLabels) hints.add("瞳色" to eyeColor)
        if (moePoints.isNotEmpty() && "萌点" !in usedLabels) hints.add("萌点" to moePoints)
        return hints
    }
    
    fun getTotalHintCount(): Int {
        var count = 0
        if (identity.isNotEmpty()) count++
        if (personality.isNotEmpty()) count++
        if (keywords.isNotEmpty()) count++
        if (possessions.isNotEmpty()) count++
        if (hairColor.isNotEmpty()) count++
        if (eyeColor.isNotEmpty()) count++
        if (moePoints.isNotEmpty()) count++
        return count
    }
}
