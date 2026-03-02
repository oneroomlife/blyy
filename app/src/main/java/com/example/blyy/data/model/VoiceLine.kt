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
