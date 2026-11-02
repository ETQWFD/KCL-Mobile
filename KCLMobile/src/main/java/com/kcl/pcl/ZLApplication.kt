/*
 * KCL Mobile
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.kyant.fishnet.Fishnet
import com.kcl.pcl.context.GlobalContext
import com.kcl.pcl.context.refreshContext
import com.kcl.pcl.coroutine.TaskSystem
import com.kcl.pcl.game.account.AccountsManager
import com.kcl.pcl.game.path.GamePathManager
import com.kcl.pcl.keepalive.TaskKeepAlive
import com.kcl.pcl.notification.NotificationManager
import com.kcl.pcl.path.PathManager
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.setting.loadAllSettings
import com.kcl.pcl.ui.activities.ErrorActivity
import com.kcl.pcl.ui.activities.FatalErrorActivity
import com.kcl.pcl.ui.activities.MainActivity
import com.kcl.pcl.ui.activities.Pcl2Activity
import com.kcl.pcl.ui.activities.showFatalError
import com.kcl.pcl.ui.activities.showLauncherCrash
import com.kcl.pcl.utils.device.Architecture
import com.kcl.pcl.utils.logging.Logger
import com.kcl.pcl.utils.writeCrashFile
import com.tencent.mmkv.MMKV
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toOkioPath
import kotlin.properties.Delegates

@HiltAndroidApp
class ZLApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        @JvmStatic
        var DEVICE_ARCHITECTURE by Delegates.notNull<Int>()
    }

    override fun onCreate() {
        GlobalContext = this
        refreshContext(this)
        //初始化任务保活控制器，需在任何任务开始前完成初始化
        TaskKeepAlive.initialize(this)

        //提前建好全部通知渠道。
        //通知渠道原本只在 MainActivity.onCreate 里创建，而 PCL2 界面走的是 Pcl2Activity，
        //根本不经过 MainActivity；渠道缺失时 GameService / JvmService 的 startForeground
        //会被系统判为 "Bad notification for startForeground" 并抛出 RemoteServiceException，
        //表现为「启动游戏崩溃」以及「安装加载器时 :jvm 进程秒退、安装流程一直卡住」。
        //放在 Application 里可以在任何入口（含各子进程）都保证渠道存在。
        runCatching {
            NotificationManager.initManager(this)
        }.onFailure {
            Log.w("ZLApplication", "Failed to create notification channels", it)
        }

        Thread.setDefaultUncaughtExceptionHandler { _, th ->
            //停止所有任务（失败也不能阻断后续兜底）
            runCatching { TaskSystem.stopAll() }

            val throwable = if (th is SplashException) th.cause!!
            else th

            //兜底：落盘 / 弹窗任何一步若自己再抛异常，绝不能让它把「未捕获异常处理器」本身带崩——
            //那样会退化成系统默认行为，进程静默死亡、用户连崩溃页都看不到。
            //因此整段用 try/finally 包住，无论如何都走到 killProcess。
            try {
                runCatching { Logger.error("Startup", "An exception occurred", throwable) }

                writeCrashFile(
                    file = PathManager.FILE_CRASH_REPORT,
                    throwable = throwable
                ) { t ->
                    runCatching {
                        Logger.error("AppCrash", "An exception occurred while saving the crash report", t)
                    }
                }

                runCatching { showLauncherCrash(this@ZLApplication, throwable, th !is SplashException) }
            } finally {
                Process.killProcess(Process.myPid())
            }
        }

        super.onCreate()
        runCatching {
            Fishnet.init(this, PathManager.DIR_NATIVE_LOGS.absolutePath)

            MMKV.initialize(this)
            loadAllSettings(this)

            Logger.initialize(this)

            initializeData()
            PathManager.DIR_FILES_PRIVATE = getDir("files", MODE_PRIVATE)
            DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture()
            //Force x86 lib directory for Asus x86 based zenfones
            if (Architecture.isx86Device() && Architecture.is32BitsDevice) {
                val originalJNIDirectory = applicationInfo.nativeLibraryDir
                applicationInfo.nativeLibraryDir = originalJNIDirectory.take(originalJNIDirectory.lastIndexOf("/")) + "/x86"
            }
        }.onFailure { launchTh ->
            writeCrashFile(
                file = PathManager.FILE_CRASH_REPORT,
                throwable = launchTh
            ) {
                Log.w("ZLApplication", "An exception occurred while saving the crash report", it)
            }
            showFatalError(this, launchTh)
        }

        // 记录「用户最后停在哪个启动器界面」，供崩溃后的重启按钮使用。
        //
        // 放在 `initializeData()` 之后：下面每次都要写 MMKV，而 MMKV 的初始化在上面那个
        // runCatching 里，顺序反了会抛。
        //
        // 背景：`ProcessPhoenix.triggerRebirth(context)` 在不给 Intent 时会去查 manifest 的
        // launcher activity，而本项目曾经注册了**多个** LAUNCHER（SplashActivity、Pcl2Activity），
        // 拿到哪个不确定。（第 98 轮起桌面只保留 SplashActivity 一个图标，Pcl2Activity 不再
        // 带 LAUNCHER 过滤器，这条歧义已经基本消失；下面的记录逻辑仍然保留 —— 它记的是
        // 「用户最后停在哪个界面」，无论如何都比靠 manifest 猜要准。）
        // 把界面名落盘之后，`ErrorActivity` 就能带 Intent 指路，绕开这条歧义路径。
        //
        // ⚠️ **只记启动器主界面**，其余 Activity 一律不记：
        //   · 崩溃页自己不算（否则重启会落在崩溃页上）；
        //   · 游戏界面 `VMActivity` 跑在 `:game` 子进程里，`registerActivityLifecycleCallbacks`
        //     在每个进程都会注册，它在子进程 resume 时同样会回调到这里。一旦把这个类名写进去，
        //     主进程读到的就是一个**不在白名单里**的值，`restartTarget()` 只能返回 null，
        //     于是又退化回上面那个「不带 Intent 的 triggerRebirth」的歧义路径。
        //     顺带也避免两个进程同时写同一份 MMKV。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity && activity !is Pcl2Activity) return
                runCatching { AllSettings.lastForegroundScreen.save(activity.javaClass.name) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshContext(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(20L * 1024 * 1024) // 20MB
                    .weakReferencesEnabled(true) //弱引用
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(512L * 1024 * 1024) // 512MB
                    .directory(PathManager.DIR_IMAGE_CACHE.toOkioPath())
                    .build()
            }
            .components {
                add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun initializeData() {
        AccountsManager.initialize(this)
        GamePathManager.initialize(this)
    }
}
