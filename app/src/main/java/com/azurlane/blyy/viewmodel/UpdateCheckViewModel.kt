package com.azurlane.blyy.viewmodel

import com.azurlane.blyy.util.AppUpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 仅用于在 Composable 中获取 AppUpdateChecker 实例的轻量 ViewModel。
 * AppUpdateChecker 本身是 @Singleton，所有逻辑都在其中，
 * 此 ViewModel 仅作为 Hilt 注入的桥梁。
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    val updateChecker: AppUpdateChecker
) : androidx.lifecycle.ViewModel()
