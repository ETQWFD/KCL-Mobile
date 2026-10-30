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

package com.kcl.pcl.viewmodel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kcl.pcl.BuildConfig
import com.kcl.pcl.R
import com.kcl.pcl.path.GLOBAL_CLIENT
import com.kcl.pcl.path.GLOBAL_JSON
import com.kcl.pcl.path.URL_PROJECT_INFO
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.ui.components.MarqueeText
import com.kcl.pcl.ui.components.SimpleListDialog
import com.kcl.pcl.ui.screens.content.elements.DisabledAlpha
import com.kcl.pcl.ui.upgrade.UpgradeDialog
import com.kcl.pcl.ui.upgrade.UpgradeFilesDialog
import com.kcl.pcl.upgrade.GithubContentApi
import com.kcl.pcl.upgrade.GithubReleaseApi
import com.kcl.pcl.upgrade.RemoteData
import com.kcl.pcl.upgrade.TooFrequentOperationException
import com.kcl.pcl.utils.logging.Logger
import com.kcl.pcl.utils.network.safeBodyAsJson
import com.kcl.pcl.utils.network.withRetry
import com.kcl.pcl.utils.string.decodeBase64
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "LauncherUpgradeVM"

sealed interface LauncherUpgradeOperation {
    data object None : LauncherUpgradeOperation
    /** 已检查到启动器存在新版本，展示更新信息 */
    data class Upgrade(val data: RemoteData) : LauncherUpgradeOperation
    /** 选择要安装的安装包文件 */
    data class SelectApk(val data: RemoteData) : LauncherUpgradeOperation
    /** 打开网盘分享 */
    data class OpenCloudDrive(val cloudDrive: RemoteData.CloudDrive) : LauncherUpgradeOperation
}

/**
 * 最新版本的信息获取源
 */
private const val LATEST_VERSION = "latest_version_md.json"
private const val LATEST_API_URL = "$URL_PROJECT_INFO/$LATEST_VERSION"
private const val LATEST_API_CHINESE_URL = "https://raw.githubusercontent.com/ETQWFD/KCL-Mobile/main/$LATEST_VERSION"
/**
 * 国内备用镜像：jsDelivr 的 GitHub 加速。
 *
 * `api.github.com` 与 `raw.githubusercontent.com` 在中国大陆都经常连不上，
 * 只留它们两个时「检查更新」会直接失败（用户看到的就是「点了没反应」）。
 * jsDelivr 走的是自家 CDN，同一份文件能正常取到。
 */
private const val LATEST_API_JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/ETQWFD/KCL-Mobile@main/$LATEST_VERSION"

/** 一次「检查更新」最多等这么久（毫秒）：超时就当失败，别让按钮一直转。 */
private const val UPDATE_TIMEOUT_MS = 60_000L
/** GitHub Releases 最新版本接口：动态拉取安装包资产，避免在 latest_version_md.json 里写死 files。 */
private const val RELEASE_LATEST_URL = "https://api.github.com/repos/ETQWFD/KCL-Mobile/releases/latest"

/**
 * 用于记录启动器更新 ViewModel
 */
class LauncherUpgradeViewModel: ViewModel() {
    var operation by mutableStateOf<LauncherUpgradeOperation>(LauncherUpgradeOperation.None)

    private val checkMutex = Mutex()

    /**
     * 检查是否在限频时间内
     * @param time 限频时间（毫秒）
     * @param lastCheckTime 上次检查的时间戳
     */
    private fun isWithinRateLimit(
        time: Long,
        lastCheckTime: Long
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        if (lastCheckTime > currentTime) {
            //用户调整到了未来的时间，无法正常判断
            //直接允许进行检查
            return false
        }
        return currentTime - lastCheckTime < time
    }

    /**
     * 更新最后一次检查的时间
     */
    private fun updateLastCheckTime() {
        AllSettings.lastUpgradeCheck.save(System.currentTimeMillis())
    }

    /** 同一个 ViewModel 实例只做一次启动检查（重组 / 重入不会重复打网络）。 */
    private var startCheckLaunched = false

    /**
     * 在启动时检查更新。
     *
     * **不再做「一小时一次」的限频**：按需求改成每次进入应用都查一次。
     * 本地 checks 只防同一个 ViewModel 实例被重复调用（`PclUpgradeHost` 每次组合
     * 都会调它），跨进程/跨 Activity 重建仍会重新检查，也就是「每次进入都查」。
     */
    fun checkOnAppStart(
        onIsLatest: suspend () -> Unit = {}
    ) {
        if (startCheckLaunched) return
        startCheckLaunched = true
        viewModelScope.launch {
            val data = fetchRemoteData()
            if (data != null) {
                checkForUpgrade(
                    data = data,
                    lastIgnored = AllSettings.lastIgnoredVersion.getValue(),
                    ignoreDismissedVersions = true, //启动时检查忽略用户已忽略的版本
                    onUpgrade = { data ->
                        operation = LauncherUpgradeOperation.Upgrade(data)
                    },
                    onIsLatest = onIsLatest
                )
            }
            updateLastCheckTime()
        }
    }

    /**
     * 用户在设置内手动点击检查更新
     * @param onInProgress 准备检查更新
     * @param onIsLatest 当前启动器是最新版
     * @param onChecked 拿到远端数据后回调（null = 网络失败）。
     *   PCL 界面用它区分「失败 / 已最新 / 有新版本」并弹出对应的 PCL 弹窗 ——
     *   只看 Boolean 返回值分不清前两者，而那边没有 Toast 可以兜底。
     */
    suspend fun checkManually(
        onInProgress: suspend () -> Unit = {},
        onIsLatest: suspend () -> Unit = {},
        onChecked: suspend (RemoteData?) -> Unit = {},
    ): Boolean {
        return checkMutex.withLock {
            if (
                isWithinRateLimit(
                    time = TimeUnit.SECONDS.toMillis(5L),
                    lastCheckTime = AllSettings.lastUpgradeCheck.getValue()
                )
            ) throw TooFrequentOperationException()

            onInProgress()

            val data = fetchRemoteData()
            if (data != null) {
                checkForUpgrade(
                    data = data,
                    lastIgnored = AllSettings.lastIgnoredVersion.getValue(),
                    ignoreDismissedVersions = false,
                    onUpgrade = { data ->
                        operation = LauncherUpgradeOperation.Upgrade(data)
                    },
                    onIsLatest = onIsLatest
                )
            }
            updateLastCheckTime()
            onChecked(data)
            data != null
        }
    }

    /**
     * 从远端获取最新的启动器信息。
     *
     * 整体套一层 60 秒超时：`GLOBAL_CLIENT` 单次请求 30 秒超时，主源 + 两个镜像源
     * 各自还带重试，网络不通时能拖上好几分钟 —— 用户点完「检查更新」只会觉得死了。
     */
    private suspend fun fetchRemoteData(): RemoteData? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(UPDATE_TIMEOUT_MS) { fetchRemoteDataInner() }
        }
    }

    private suspend fun fetchRemoteDataInner(): RemoteData? {
        return runCatching {
            withRetry(logTag = "LauncherUpgrade", maxRetries = 2) {
                //获取最新的启动器信息
                val api = GLOBAL_CLIENT.get(LATEST_API_URL).safeBodyAsJson<GithubContentApi>()
                //需要Base64解密
                val contentString = decodeBase64(api.content)
                GLOBAL_JSON.decodeFromString(RemoteData.serializer(), contentString)
                    .let { it.copy(files = fetchReleaseAssets()) }
            }
        }.getOrElse { e ->
            if (Locale.getDefault().language == "zh") {
                // 国内依次试 raw.githubusercontent → jsDelivr，任一成功即可。
                // 镜像源只重试一次：再失败就该立刻把「连不上」告诉他，而不是继续等。
                var result: RemoteData? = null
                for (url in listOf(LATEST_API_CHINESE_URL, LATEST_API_JSDELIVR_URL)) {
                    result = runCatching {
                        Logger.info(TAG, "Check for updates via mirror: $url")
                        withRetry(logTag = "LauncherUpgrade_Chinese", maxRetries = 1) {
                            GLOBAL_CLIENT.get(url).safeBodyAsJson<RemoteData>()
                                .let { it.copy(files = fetchReleaseAssets()) }
                        }
                    }.getOrNull()
                    if (result != null) break
                }
                if (result == null) {
                    Logger.warning(TAG, "Failed to check for launcher upgrade!", e)
                }
                result
            } else {
                Logger.warning(TAG, "Failed to check for launcher upgrade!", e)
                null
            }
        }
    }

    /**
     * 从 GitHub Releases 最新版本动态拉取安装包资产列表。
     *
     * 这样 `latest_version_md.json` 里就**不用写死 files**：每次发新版只需把 APK 上传到
     * GitHub Release，启动器就能自动拿到全部安装包的下载地址、架构和大小。
     * 拉取失败（断网 / 被墙）时返回空列表，此时仍可通过网盘链接手动下载。
     */
    private suspend fun fetchReleaseAssets(): List<RemoteData.RemoteFile> {
        return runCatching {
            GLOBAL_CLIENT.get(RELEASE_LATEST_URL).safeBodyAsJson<GithubReleaseApi>()
                .assets
                .mapNotNull { asset ->
                    inferArch(asset.name)?.let { arch ->
                        RemoteData.RemoteFile(
                            fileName = asset.name,
                            uri = asset.downloadUrl,
                            arch = arch,
                            size = asset.size,
                        )
                    }
                }
        }.getOrElse { e ->
            Logger.warning(TAG, "Failed to fetch release assets!", e)
            emptyList()
        }
    }

    /** 从安装包文件名推断架构（如 `xxx-arm64-v8a.apk` → ARM64）。 */
    private fun inferArch(name: String): RemoteData.RemoteFile.Arch? {
        val n = name.lowercase()
        return when {
            "universal" in n -> RemoteData.RemoteFile.Arch.ALL
            "arm64" in n -> RemoteData.RemoteFile.Arch.ARM64
            "armeabi" in n -> RemoteData.RemoteFile.Arch.ARM
            "x86_64" in n -> RemoteData.RemoteFile.Arch.X86_64
            "x86" in n -> RemoteData.RemoteFile.Arch.X86
            else -> null
        }
    }

    /**
     * 检查启动器是否需要更新
     * @param lastIgnored 上次弹出更新弹窗时，用户所忽略的版本号
     * @param ignoreDismissedVersions 是否忽略用户已忽略的版本
     * @param onUpgrade 发现需要更新时调用
     * @param onIsLatest 当前已是最新版本时
     */
    private suspend fun checkForUpgrade(
        data: RemoteData,
        lastIgnored: Int?,
        ignoreDismissedVersions: Boolean,
        onUpgrade: suspend (RemoteData) -> Unit,
        onIsLatest: suspend () -> Unit = {}
    ) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        if (currentVersionCode < data.code) {
            //启动器为旧版本
            when {
                ignoreDismissedVersions && lastIgnored == data.code -> {
                    //忽略这次更新
                    Logger.info(TAG, "Launcher update detected: $currentVersionCode -> ${data.code}, but ignored by user")
                }
                else -> {
                    //弹出更新弹窗
                    Logger.info(TAG, "Launcher update detected: $currentVersionCode -> ${data.code}, dialog shown to user")
                    onUpgrade(data)
                }
            }
        } else {
            Logger.info(TAG, "Launcher is running the latest version: $currentVersionCode")
            onIsLatest()
        }
    }
}

@Composable
fun LauncherUpgradeOperation(
    operation: LauncherUpgradeOperation,
    onChanged: (LauncherUpgradeOperation) -> Unit,
    onIgnoredClick: (code: Int) -> Unit,
    onLinkClick: (String) -> Unit
) {
    when (operation) {
        is LauncherUpgradeOperation.None -> {}
        is LauncherUpgradeOperation.Upgrade -> {
            UpgradeDialog(
                data = operation.data,
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                onFilesClick = {
                    onChanged(LauncherUpgradeOperation.SelectApk(operation.data))
                },
                onIgnored = {
                    onIgnoredClick(operation.data.code)
                },
                onLinkClick = onLinkClick,
                onCloudDriveClick = { cloudDrive ->
                    onChanged(LauncherUpgradeOperation.OpenCloudDrive(cloudDrive))
                }
            )
        }
        is LauncherUpgradeOperation.SelectApk -> {
            UpgradeFilesDialog(
                data = operation.data,
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                onFileSelected = { file ->
                    onLinkClick(file.uri)
                    onChanged(LauncherUpgradeOperation.None)
                }
            )
        }
        is LauncherUpgradeOperation.OpenCloudDrive -> {
            val current by remember(operation) {
                mutableStateOf<RemoteData.CloudDrive.Link?>(null)
            }
            SimpleListDialog(
                title = stringResource(R.string.upgrade_cloud_drive),
                items = operation.cloudDrive.links,
                onItemSelected = { link ->
                    onLinkClick(link.link)
                },
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                current = current,
                itemLayout = { item, isCurrent, onClick ->
                    CloudDriveLayout(
                        link = item,
                        selected = isCurrent,
                        onClick = onClick
                    )
                },
                showConfirm = true,
                confirmText = {
                    MarqueeText(text = stringResource(R.string.generic_confirm))
                }
            )
        }
    }
}


@Composable
private fun CloudDriveLayout(
    link: RemoteData.CloudDrive.Link,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Column(
            modifier = Modifier.alpha(if (enabled) 1.0f else DisabledAlpha),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            //网盘名称
            MarqueeText(
                modifier = Modifier.fillMaxWidth(),
                text = link.name,
                style = MaterialTheme.typography.labelMedium
            )
            //网盘链接
            MarqueeText(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.7f),
                text = link.link,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}