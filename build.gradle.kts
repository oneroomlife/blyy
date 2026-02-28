// 根目录的 build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // 添加 Compose Compiler 插件声明 (针对 Kotlin 2.0+)
    alias(libs.plugins.compose.compiler) apply false
}
