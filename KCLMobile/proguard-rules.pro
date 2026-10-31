-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# SDL
-keep class org.libsdl.app.** { *; }

# Launcher
-keep class org.lwjgl.glfw.CallbackBridge {
    *;
}
-keep class com.oracle.dalvik.VMLauncher {
    *;
}

#
## Hilt
#-keep class dagger.hilt.** { *; }
#-keep class javax.inject.** { *; }
#-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
#-keepclasseswithmembers class * {
#    @dagger.hilt.* <methods>;
#}

# Prevent R8 from over-optimizing constructors (causes StackOverflow with Hilt + proguard-android-optimize.txt)
-keepclassmembers,allowobfuscation class * {
    @dagger.hilt.internal.GeneratedEntryPoint <init>(...);
}
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *


-keep class com.kcl.pcl.bridge.** { *; }
-keep class com.kcl.pcl.utils.device.VulkanChecker {
    *;
}
-keep class com.kcl.pcl.utils.device.VulkanCapabilities {
    *;
}
-keep interface com.kcl.pcl.utils.device.VulkanLogCallback {
    *;
}
-keep class com.kcl.pcl.game.input.CriticalNativeTest {
    *;
}

# Libraries
-keep class com.github.steveice10.opennbt.** { *; }

# SoraEditor language-textmate
-keep class org.jcodings.** { *; }

#
# ============================================================
# 加固规则（仅 R8/ProGuard 规则，不引入第三方加固 SDK / 加壳）
# 说明：
# 1) AndroidManifest 中声明的 Activity 由 AGP 自动生成 keep 规则，
#    此处对启动与崩溃上报入口做显式保留，防止未来 Activity 移动包名后
#    入口类名/回调被混淆导致快捷启动或崩溃上报失效。
# 2) android.util.Log.* 的 -assumenosideeffects 已由默认
#    proguard-android-optimize.txt 提供，此处不重复添加。
# 3) 字符串字面量混淆需要第三方插件，本项目不引入，故不加。
# ============================================================

# Launcher entry & crash-reporting activities: keep class name and members
-keep public class com.kcl.pcl.ui.activities.SplashActivity { *; }
-keep public class com.kcl.pcl.ui.activities.MainActivity { *; }
-keep public class com.kcl.pcl.ui.activities.QuickLaunchActivity { *; }
-keep public class com.kcl.pcl.ui.activities.FatalErrorActivity { *; }