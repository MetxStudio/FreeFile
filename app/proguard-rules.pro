# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# ==================== NewBlackbox（VirtualBox 免安装运行） ====================
# 核心类保留
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep class com.didi.virtualapk.** { *; }
-dontwarn top.niunaijun.**
-dontwarn com.didi.virtualapk.**

# 反射相关
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keep class **.R$* { *; }