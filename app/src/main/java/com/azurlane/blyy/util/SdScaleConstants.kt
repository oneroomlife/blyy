package com.azurlane.blyy.util

/**
 * SD 小人显示倍率的统一上下限。
 *
 * 上限从 2.0 提到 3.0：部分导出的 SD 资源「可见内容」相对骨骼包围盒很小
 * （虽然现在已经按可见内容取景，历史资源仍可能偏小），用户普遍反馈
 * 「放大到最大还是小」，所以把可调范围放开到 3 倍。
 *
 * 下限 0.3 用于内部 clamp（避免持久化脏数据把小人缩成 1 像素）；
 * 设置页滑块只暴露 [SECRETARY_SD_SCALE_SLIDER_MIN]..[SECRETARY_SD_SCALE_MAX]。
 */
const val SECRETARY_SD_SCALE_MIN = 0.3f
const val SECRETARY_SD_SCALE_MAX = 3.0f

/** 设置页滑块下限（不给用户提供「缩到看不见」的档位） */
const val SECRETARY_SD_SCALE_SLIDER_MIN = 0.5f
