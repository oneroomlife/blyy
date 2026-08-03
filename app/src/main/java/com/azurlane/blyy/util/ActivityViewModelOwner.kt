package com.azurlane.blyy.util

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 从 Context 层级中安全查找 [ViewModelStoreOwner]。
 *
 * 背景：
 * 多个啾信相关 Composable（JiuxinConfigScreen / JiuxinChatScreen /
 * ConversationListScreen / JiuxinShipConfigScreen）需要绑定到 Activity 级别的
 * ViewModelStoreOwner，以实现跨页面共享同一个 [JiuxinViewModel] 实例。
 *
 * 原先使用 `LocalContext.current as ViewModelStoreOwner` 强转，存在以下风险：
 * - 在某些 OEM ROM（如 MIUI / ColorOS 的 Activity 代理）下，LocalContext 可能被包装
 * - 部分 Compose Material3 组件在内部使用 ContextThemeWrapper 包装 LocalContext
 * - 直接强转会抛出 ClassCastException 导致应用闪退
 *
 * 本函数沿 [ContextWrapper.baseContext] 链向上查找，
 * 直到找到 [ViewModelStoreOwner] 实例（通常是 ComponentActivity）。
 *
 * @return 层级中第一个 [ViewModelStoreOwner] 实例；找不到返回 null（调用方应回退到 [androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner]）
 */
fun Context.findActivityViewModelStoreOwner(): ViewModelStoreOwner? {
    var ctx: Context? = this
    // 沿 baseContext 链向上查找，避免无限循环（理论上不会出现循环引用，加保护更安全）
    var depth = 0
    while (ctx != null && depth < 16) {
        if (ctx is ViewModelStoreOwner) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
        depth++
    }
    return null
}
