package com.example.blyy.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class VoiceLine(
    val skinName: String,
    val scene: String,
    val dialogue: String,
    val audioUrl: String
)
