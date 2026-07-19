package com.azurlane.blyy.data.model

import androidx.compose.runtime.Immutable

/**
 * 学生立绘画廊 — 支持 8 个分类标签
 */
@Immutable
data class StudentGallery(
    val studentName: String,
    val tabs: List<StudentGalleryTab> = emptyList()
) {
    companion object {
        /** 立绘分类标签定义（顺序固定） */
        val TAB_NAMES = listOf(
            "官方介绍",
            "回忆大厅",
            "设定集-日文",
            "设定集-繁中",
            "本家画",
            "官方衍生",
            "表情",
            "视频"
        )

        /** HTML header-container id 与标签名的映射 */
        val TAB_ID_MAP = mapOf(
            "gfjs" to "官方介绍",
            "hydt" to "回忆大厅",
            "sdj-jp" to "设定集-日文",
            "sdj-cn" to "设定集-繁中",
            "yhs" to "本家画",
            "gfys" to "官方衍生",
            "emoji" to "表情",
            "video" to "视频"
        )
    }
}

@Immutable
data class StudentGalleryTab(
    val name: String,
    val images: List<StudentGalleryImage> = emptyList(),
    val videos: List<StudentGalleryVideo> = emptyList()
) {
    val isEmpty: Boolean get() = images.isEmpty() && videos.isEmpty()
    val totalCount: Int get() = images.size + videos.size
}

@Immutable
data class StudentGalleryImage(
    val url: String,
    val description: String = ""
)

@Immutable
data class StudentGalleryVideo(
    val url: String,
    val title: String = "",
    val description: String = "",
    val coverUrl: String = ""
)
