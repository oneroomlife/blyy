package com.azurlane.blyy.data.model

enum class VoiceLanguage {
    CN,
    JP;

    val displayName: String
        get() = when (this) {
            CN -> "中配"
            JP -> "日配"
        }

    val shortName: String
        get() = when (this) {
            CN -> "CN"
            JP -> "JP"
        }
}