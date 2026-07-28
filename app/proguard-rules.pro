# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── 通用属性保留 ──
# 保留行号表与源文件名，使 release 包崩溃栈可读（用于 Crashlytics / Logcat 定位）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# 保留注解、内部类、泛型签名、异常表 — 序列化框架、反射、Compose 运行时均依赖
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,Exceptions

# ── ZSTD-JNI ──
# 官方文档明确说明：Java 类不能被重命名/最小化/重定位，
# 否则 JVM 链接 native 库时会因类名不匹配而失败。
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
-keepclassmembers class com.github.luben.zstd.** { *; }

# ── Spine + libgdx ──
# spine-libgdx 3.8 通过反射访问 Attachment 子类，libgdx 通过反射加载 GL 驱动，
# 必须保留类名与字段名，否则运行时找不到类或字段。
-keep class com.esotericsoftware.** { *; }
-keepclassmembers class com.esotericsoftware.** { *; }
-keep class com.badlogic.** { *; }
-keepclassmembers class com.badlogic.** { *; }
-dontwarn com.badlogic.**
-dontwarn com.esotericsoftware.**

# ── kotlinx.serialization ──
# 项目大量使用 @Serializable（JiuxinModels / Leaderboard / AssistantModels / StudentFilterData /
# PlayLaterItem / ChatSession 等），序列化器通过反射 + Companion.serializer() 查找，
# 若被混淆会导致运行时 SerializerNotFound / ClassNotFound 崩溃。
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留 @Serializable 标注的类（允许混淆类名，但保留字段与 Companion）
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class **
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── 数据模型 ──
# Room 实体与 JSON 数据类被 DataStore / Room / Jsoup 按字段名读写，
# 混淆字段名会导致反序列化静默失败或数据库列丢失。
-keep class com.azurlane.blyy.data.model.** { *; }

# ── WebView JavaScript Interface（预留） ──
# 若后续添加 @JavascriptInterface 注解的方法，需保留其公共方法名供 JS 调用。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
