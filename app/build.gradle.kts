plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.azurlane.blyy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.azurlane.blyy"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    //noinspection WrongGradleMethod
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

ksp {
    arg("room.generateKotlin", "false")
}

dependencies {
    /** ---------------- 基础核心 ---------------- */
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.guava)

    /** ---------------- Compose (BOM 管理) ---------------- */
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.animation)

    /** ---------------- Hilt ---------------- */
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    /** ---------------- Room ---------------- */
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    /** ---------------- DataStore ---------------- */
    implementation(libs.androidx.datastore.preferences)

    /** ---------------- 网络 & 解析 ---------------- */
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    /** ZSTD 本地解压：static.l2d.su 无视 Accept-Encoding 头强制返回 ZSTD 流，必须在客户端本地解压。
     *  ⚠️ 必须使用 @aar 后缀：标准 JAR 仅含桌面 Linux .so（/linux/aarch64/...），
     *  Android 运行时找不到 arm64-v8a .so 会抛 UnsatisfiedLinkError 闪退。
     *  AAR 包含全部 4 个 Android ABI（arm64-v8a/armeabi-v7a/x86/x86_64）的 native 库。
     *  version catalog 不支持 type 字段，故用字符串声明绕过。 */
    implementation("com.github.luben:zstd-jni:${libs.versions.zstdJni.get()}@aar")

    /** ---------------- 图片 & 媒体 ---------------- */
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    /** ---------------- UI 扩展 ---------------- */
    implementation(libs.compose.shimmer)

    /** ---------------- 测试 ---------------- */
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
