# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── ZSTD-JNI ──
# 官方文档明确说明：Java 类不能被重命名/最小化/重定位，
# 否则 JVM 链接 native 库时会因类名不匹配而失败。
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
-keepclassmembers class com.github.luben.zstd.** { *; }

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile