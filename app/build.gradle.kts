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
        versionName = "2.2.1"

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

    // 把 extractGdxNatives 任务生成的 libgdx.so 目录加入 jniLibs 源集，
    // AGP 会把这里的 .so 打入 APK 的 lib/<abi>/ 下。
    // ⚠️ AGP 9.x 默认禁止在 srcDir() 中传 Provider，已在 gradle.properties 中
    // 设置 android.sourceset.disallowProvider=false 重新启用。
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/gdxNatives"))
        }
    }
}

/**
 * 从 gdx-platform:natives-* classifier JAR 中提取 libgdx.so 到 generated jniLibs/<abi>/。
 *
 * 原因：gdx-platform JAR 的 .so 在 jar 根目录（非 jni/<abi>/ 结构），AGP 不会自动
 * 从 JAR 依赖提取 .so 到 APK 的 lib/ 下。而 SharedLibraryLoader 在 Android 上调用
 * System.loadLibrary("gdx")，要求 libgdx.so 必须在 APK 的 lib/<abi>/ 中。
 * 详见 dependencies 块中的注释。
 */
val gdxNativesDir = layout.buildDirectory.dir("generated/gdxNatives")

val extractGdxNatives by tasks.registering {
    val gdxVersion = libs.versions.libgdx.get()
    val abiClassifiers = listOf(
        "arm64-v8a" to "natives-arm64-v8a",
        "armeabi-v7a" to "natives-armeabi-v7a",
        "x86" to "natives-x86",
        "x86_64" to "natives-x86_64"
    )
    inputs.property("gdxVersion", gdxVersion)
    outputs.dir(gdxNativesDir)
    doLast {
        val cp = configurations.getByName("debugRuntimeClasspath").resolve()
        abiClassifiers.forEach { (abi, classifier) ->
            val jarName = "gdx-platform-$gdxVersion-$classifier.jar"
            val jarFile = cp.find { it.name == jarName } ?: return@forEach
            val abiOutDir = gdxNativesDir.get().asFile.resolve(abi).apply { mkdirs() }
            zipTree(jarFile).matching { include("*.so") }.files.forEach { soFile ->
                soFile.copyTo(File(abiOutDir, soFile.name), overwrite = true)
            }
            logger.lifecycle("Extracted libgdx.so from $jarName → ${abiOutDir.relativeTo(rootDir)}")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(extractGdxNatives)
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

    /** ---------------- Spine SD 小人动画（碧蓝航线 3.8.99 二进制格式） ----------------
     *  spine-libgdx 3.8.99.1 → 解析 .skel/.atlas 骨骼与动画
     *  gdx 1.13.5 → 提供 TextureAtlas / PolygonSpriteBatch / GL20 抽象
     *  gdx-backend-android 1.13.5 → 提供 AndroidGL20 / AndroidFiles，桥接 GLES20 与 assets
     *
     *  ⚠️ gdx.jar 与 gdx-backend-android.aar 都不含 native .so，libgdx 的 Gdx2DPixmap
     *  JNI 实现在 gdx-platform:natives-* classifier jar 中（每个 ABI 一个 jar，~75KB）。
     *  必须显式声明 4 个 ABI 的 natives，否则 TextureAtlas 加载 PNG 时抛
     *  UnsatisfiedLinkError: Gdx2DPixmap.load(long[], byte[], int, int) 闪退。
     *  version catalog 不支持 classifier 字段，沿用 zstd-jni 的字符串声明模式。
     *
     *  ⚠️ gdx-platform JAR 把 libgdx.so 放在 jar 根目录（不是 jni/<abi>/ 结构），
     *  AGP 不会自动从 JAR 依赖提取 .so 到 APK 的 lib/ 下。
     *  而 SharedLibraryLoader.load() 在 Android 上调用 System.loadLibrary("gdx")
     *  要求 libgdx.so 必须在 APK 的 lib/<abi>/ 中。
     *  因此用 extractGdxNatives 任务在 build 时把 .so 解压到 generated jniLibs 源集。 */
    implementation(libs.spine.libgdx)
    implementation(libs.libgdx.core)
    implementation(libs.libgdx.backend.android)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.libgdx.get()}:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.libgdx.get()}:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.libgdx.get()}:natives-x86")
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.libgdx.get()}:natives-x86_64")

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
