/*
 * KCL Mobile — PCL2 UI port
 *
 * 启动器自身的更新下载与安装（PCL 界面）。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.kcl.pcl.game.download.engine.DownloadEngine
import com.kcl.pcl.game.download.engine.DownloadRequest
import com.kcl.pcl.game.download.engine.DownloadStats
import com.kcl.pcl.upgrade.RemoteData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 启动器更新的下载/安装状态。
 *
 * 之前「选择安装包」是 `openLink()` 把 GitHub 的资产链接丢给浏览器 —— 在 PCL 界面里
 * 用户看到的就是「选完什么都没发生」，还得自己回浏览器点下载、再回文件管理器安装。
 * 现在改成启动器自己下载并直接调起系统安装界面。
 */
sealed interface PclLauncherUpdateState {
    data object Idle : PclLauncherUpdateState

    /** 正在下载。`total <= 0` 表示服务器没给大小，进度按不确定处理。 */
    data class Downloading(
        val version: String,
        val archText: String,
        val downloaded: Long,
        val total: Long,
        val speed: Long,
    ) : PclLauncherUpdateState

    /** 需要用户先允许「安装未知应用」（Android 8+）。授权返回后会自动继续安装。 */
    data class NeedPermission(
        val version: String,
        val archText: String,
    ) : PclLauncherUpdateState

    data class Failed(
        val version: String,
        val archText: String,
        val message: String,
    ) : PclLauncherUpdateState
}

/** 当前设备 ABI → 该下哪个架构的安装包。 */
internal fun currentLauncherArch(): RemoteData.RemoteFile.Arch {
    val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
    return when {
        abis.any { it == "arm64-v8a" } -> RemoteData.RemoteFile.Arch.ARM64
        abis.any { it == "armeabi-v7a" || it == "armeabi" } -> RemoteData.RemoteFile.Arch.ARM
        abis.any { it == "x86_64" } -> RemoteData.RemoteFile.Arch.X86_64
        abis.any { it == "x86" } -> RemoteData.RemoteFile.Arch.X86
        else -> RemoteData.RemoteFile.Arch.ALL
    }
}

/** 架构的展示名。用 ABI 原名而不是「ARM64 / 32 位」这种含糊说法，和包名对得上。 */
internal fun RemoteData.RemoteFile.Arch.abiText(): String = when (this) {
    RemoteData.RemoteFile.Arch.ALL -> "universal"
    RemoteData.RemoteFile.Arch.ARM -> "armeabi-v7a"
    RemoteData.RemoteFile.Arch.ARM64 -> "arm64-v8a"
    RemoteData.RemoteFile.Arch.X86 -> "x86"
    RemoteData.RemoteFile.Arch.X86_64 -> "x86_64"
}

/**
 * 启动器安装包的下载器。
 *
 * 放在 object 里而不是某个 Composable 里：下载要能跨页面存活（用户可能切到别的版块去），
 * 进度框由 [PclUpgradeHost] 无条件渲染，所以只需要一份全局状态。
 */
internal object PclLauncherUpdate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var state by mutableStateOf<PclLauncherUpdateState>(PclLauncherUpdateState.Idle)
        private set

    private var job: Job? = null
    private var pendingFile: File? = null
    private var pendingRemote: RemoteData.RemoteFile? = null

    /**
     * 用户点了「去授权」。
     *
     * 需要它是因为 `PclDialogContainer` 的退出动画结束一定会回调 `onDismiss`，
     * 授权弹窗关闭时也会走一次 —— 不能靠「弹窗关了」来判断用户是「去授权」还是「取消」。
     */
    private var awaitingPermission = false

    /** 进入「安装」阶段但被权限挡住时，这里留着文件，授权回来用它继续装。 */
    val hasPendingInstall: Boolean get() = pendingFile != null

    fun currentVersionText(): String = when (val s = state) {
        is PclLauncherUpdateState.Downloading -> s.version
        is PclLauncherUpdateState.Failed -> s.version
        is PclLauncherUpdateState.NeedPermission -> s.version
        PclLauncherUpdateState.Idle -> ""
    }

    /**
     * 开始下载 [file]。
     *
     * 复用游戏资源的下载引擎（[DownloadEngine]）：它自带失败换源、断点重试与进度统计，
     * 不必为「下载一个 APK」再写一套。
     */
    fun start(context: Context, version: String, file: RemoteData.RemoteFile) {
        val app = context.applicationContext
        job?.cancel()
        pendingRemote = file
        val archText = file.arch.abiText()
        state = PclLauncherUpdateState.Downloading(
            version = version,
            archText = archText,
            downloaded = 0L,
            total = file.size,
            speed = 0L,
        )
        job = scope.launch {
            val dir = File(app.cacheDir, "launcher_update").apply { mkdirs() }
            val target = File(dir, file.fileName.substringAfterLast('/'))
            // 上一次没下完的残file会让引擎以为已经下好了，直接删掉重来
            runCatching { if (target.exists()) target.delete() }

            val stats = DownloadStats()
            val progress = launch {
                while (isActive) {
                    val total = if (file.size > 0) file.size else stats.expectedTotalBytes
                    val cur = state
                    if (cur is PclLauncherUpdateState.Downloading) {
                        state = cur.copy(
                            downloaded = stats.downloadedBytes,
                            total = total,
                            speed = stats.refreshSpeed(),
                        )
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            try {
                DownloadEngine.download(
                    request = DownloadRequest(
                        urls = listOf(file.uri),
                        targetFile = target,
                        expectedSize = file.size,
                    ),
                    stats = stats,
                )
                progress.cancel()
                // 下完立刻把最终进度写满，别停在 99%
                val cur = state
                if (cur is PclLauncherUpdateState.Downloading) {
                    state = cur.copy(downloaded = stats.downloadedBytes, total = stats.downloadedBytes, speed = 0L)
                }
                pendingFile = target
                withContext(Dispatchers.Main) { install(app) }
            } catch (e: Exception) {
                progress.cancel()
                state = PclLauncherUpdateState.Failed(
                    version = version,
                    archText = archText,
                    message = e.message ?: "下载失败",
                )
            }
        }
    }

    /** 重试上一次失败的下载。 */
    fun retry(context: Context) {
        val file = pendingRemote ?: return
        start(context, currentVersionText().ifEmpty { "最新版本" }, file)
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingFile = null
        awaitingPermission = false
        state = PclLauncherUpdateState.Idle
    }

    /**
     * 关掉失败/授权提示。
     *
     * 只有真的「取消」才丢掉待安装文件；点了「去授权」的那次关闭要把它留给
     * [retryInstall]（见 [awaitingPermission]）。
     */
    fun dismiss() {
        if (state is PclLauncherUpdateState.Downloading) return
        if (!awaitingPermission) pendingFile = null
        state = PclLauncherUpdateState.Idle
    }

    /** 跳系统「安装未知应用」授权页。 */
    fun openInstallSettings(context: Context) {
        awaitingPermission = true
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 从授权页回来后重试安装（[PclUpgradeHost] 监听 ON_RESUME 调用）。 */
    fun retryInstall(context: Context) {
        if (pendingFile == null) return
        install(context.applicationContext)
    }

    /**
     * 调起系统安装界面。用了 `FLAG_ACTIVITY_NEW_TASK`：这里可能不在 Activity 的
     * 上下文里（object 持有的是 Application），不加会直接抛异常。
     */
    private fun install(context: Context) {
        val file = pendingFile ?: return
        val version = currentVersionText()
        val archText = pendingRemote?.arch?.abiText().orEmpty()
        if (!file.exists() || file.length() == 0L) {
            state = PclLauncherUpdateState.Failed(version, archText, "安装包没有下载成功，文件是空的。")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 没有「安装未知应用」权限：先停在这里，去授权后 ON_RESUME 会继续
            state = PclLauncherUpdateState.NeedPermission(version, archText)
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onSuccess {
            // 系统安装界面已经弹出来了，本地状态收掉；用户取消安装也不会留下悬空弹窗
            state = PclLauncherUpdateState.Idle
            awaitingPermission = false
            pendingFile = null
        }.onFailure {
            state = PclLauncherUpdateState.Failed(
                version, archText, it.message ?: "无法打开安装程序。",
            )
        }
    }

    private const val PROGRESS_INTERVAL_MS = 300L
}

/** 字节数的紧凑写法：`181.6 MB`。 */
internal fun formatUpdateSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toInt()} ${units[index]}"
    else String.format("%.1f %s", value, units[index])
}
