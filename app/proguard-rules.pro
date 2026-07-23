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

# ── Spine + libgdx ──
# spine-libgdx 3.8 通过反射访问 Attachment 子类，libgdx 通过反射加载 GL 驱动，
# 必须保留类名与字段名，否则运行时找不到类或字段。
-keep class com.esotericsoftware.** { *; }
-keepclassmembers class com.esotericsoftware.** { *; }
-keep class com.badlogic.** { *; }
-keepclassmembers class com.badlogic.** { *; }
-dontwarn com.badlogic.**
-dontwarn com.esotericsoftware.**

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