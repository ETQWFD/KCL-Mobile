/*
 * KCL Mobile — PCL2 UI port
 *
 * 外部导入请求的交接点。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次「分享到启动器」/「用启动器打开文件」的导入请求。
 *
 * @param type `IMPORT_TYPE_MODPACK` / `IMPORT_TYPE_CONTROLS`（常量在 `SplashActivity.kt` 顶部）
 * @param uri 对方应用给的内容 URI（SplashActivity 已经尽量申请了持久读权限）
 */
class PclImportRequest(
    val type: String,
    val uri: Uri,
)

/**
 * 导入请求从 Activity 交到 Compose 界面的中转站。
 *
 * 为什么需要这一层：整合包要交给 `ModpackImporter`，而它的进度弹窗、安装锁、任务登记
 * 全都挂在 PCL2 界面的 `PclRuntime` 上（`PclRuntime` 由 `PclMainScreen` 里的
 * `rememberPclRuntime()` 创建，Activity 里根本拿不到）。所以 Activity 只负责把请求放进来，
 * 真正的处理放在界面的 `PclExternalImportHost` 里。
 *
 * 用 `mutableStateOf` 而不是 Flow：这正是「请求到达时界面还没组合好」的场景，
 * 组合后第一次读就能拿到，不需要额外的重放逻辑。
 */
object PclImportBridge {
    var pending by mutableStateOf<PclImportRequest?>(null)
        private set

    fun submit(type: String, uri: Uri) {
        pending = PclImportRequest(type, uri)
    }

    /** 取出并清空，保证同一次请求只处理一遍。 */
    fun take(): PclImportRequest? = pending.also { pending = null }
}
