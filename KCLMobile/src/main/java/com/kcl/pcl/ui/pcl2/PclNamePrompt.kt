/*
 * KCL Mobile — PCL2 UI port
 *
 * 「给新实例起个名字」询问框。
 *
 * 对应 PCL2 在安装整合包时的 `MyMsgInput`：`PageInstanceInstall` 拿到整合包名之后先弹一个
 * 输入框（默认值就是整合包的名字），用户点确定才真的开始装。
 *
 * 【为什么做成全局挂起询问，而不是在页面里放一个 state】
 * 整合包的 `waitForVersionName` 回调是由 `ModpackImporter` 在**它自己的任务协程**里调用的，
 * 调用点可能发生在三种完全不同的时机：
 *   · 版本管理 →「导入整合包」：选完文件立刻问；
 *   · 长按整合包 → 分享到启动器：界面还没进版本页就问；
 *   · 下载 → 整合包：**下载完成之后**才问（此时用户可能已经切到别的版块了）。
 * 如果把对话框挂在某个页面里，第三种情况下这个协程会一直等一个永远不会被组合出来的弹窗，
 * 表现就是「整合包装到一半卡住」。所以询问本身是一个 `CompletableDeferred`，对话框挂在
 * 窗体最外层（[PclNamePromptHost]），无论用户当前在哪一页都能弹出来。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kcl.pcl.ui.pcl2.components.PclColorState
import com.kcl.pcl.ui.pcl2.components.PclHintTheme
import com.kcl.pcl.ui.pcl2.components.PclMsgButton
import com.kcl.pcl.ui.pcl2.components.PclMsgInputDialog
import com.kcl.pcl.ui.pcl2.pages.pclUniqueVersionName
import kotlinx.coroutines.CompletableDeferred

/**
 * 全局的「版本名称」询问点。
 *
 * 同一时刻只保留一条询问：整合包安装本来就在 `modpackInstallLock` 上串行，
 * 真出现两条时后一条会把前一条按默认名放行，绝不留下永远不返回的挂起。
 */
object PclNamePrompt {
    /** 一条待回答的询问。 */
    class Request(
        val title: String,
        val label: String,
        val defaultName: String,
    ) {
        internal val answer = CompletableDeferred<String>()
    }

    /** 当前没被回答的那一条（null = 没有）。 */
    var pending by mutableStateOf<Request?>(null)
        private set

    /**
     * 挂起询问版本名。
     * @return 用户输入的名称；点「使用默认名」或点了弹窗外面时返回 [defaultName]
     */
    suspend fun ask(
        defaultName: String,
        title: String = "设置版本名称",
        label: String = "这个名字会成为新实例的版本名，装完之后在版本列表里就显示它。",
    ): String {
        // 上一条还没答完（极端情况）：按它自己的默认名放它过去，别把协程挂死
        pending?.let { old ->
            pending = null
            old.answer.complete(old.defaultName)
        }
        val request = Request(title = title, label = label, defaultName = defaultName)
        pending = request
        return request.answer.await()
    }

    /** 用户点了「确定」。 */
    fun answer(text: String) {
        val request = pending ?: return
        pending = null
        request.answer.complete(text)
    }

    /**
     * 用户放弃了输入（点「使用默认名」、点弹窗外面、按返回键）：
     * 按默认名继续安装，而不是把整个导入取消掉 —— 用户既然已经选了包，默认名是安全的。
     */
    fun dismiss() {
        val request = pending ?: return
        pending = null
        request.answer.complete(request.defaultName)
    }
}

/**
 * 挂在窗体最外层的询问框（见 [PclNamePrompt]）。
 *
 * 两个按钮：**确定**（用输入框里的名字）/ **使用默认名**。
 * 没有「取消」——取消一次整合包导入要连带清半成品目录，成本远高于让用户多点一下确定。
 */
@Composable
fun PclNamePromptHost() {
    val request = PclNamePrompt.pending ?: return
    var text by remember(request) { mutableStateOf(request.defaultName) }
    PclMsgInputDialog(
        title = request.title,
        lines = listOf(request.label, "留空则使用默认名，重名会自动加序号。"),
        value = text,
        hint = request.defaultName,
        onValueChange = { text = it },
        buttons = { requestClose ->
            PclMsgButton(text = "确定", colorState = PclColorState.Highlight) {
                PclNamePrompt.answer(text)
                requestClose()
            }
            PclMsgButton(text = "使用默认名") {
                PclNamePrompt.dismiss()
                requestClose()
            }
        },
        // 点弹窗外面 / 按返回键：视为「用默认名继续」
        onDismiss = { PclNamePrompt.dismiss() },
    )
}

/**
 * 「问一次版本名，然后保证它没被占用」的收口函数。
 *
 * 三条整合包链路（本地导入、分享导入、下载后自动安装）都调它，避免三处的默认值/去重规则走偏：
 *   1. 默认值 = 整合包自己的名字（清单里的 `name`），先过一遍 [pclUniqueVersionName]
 *      让它在弹窗里就是**能直接用**的名字（不会一确定就撞名）；
 *   2. 用户输入之后再 sanitize + 去重一次 —— 手输的名字可能带 `/`、可能撞上已装版本；
 *   3. 最终名和用户输入不一致时说清楚，别让用户以为名字被吞了。
 */
internal suspend fun pclAskVersionName(
    rawDefault: String,
    title: String = "设置版本名称",
    label: String = "这个名字会成为新实例的版本名，装完之后在版本列表里就显示它。",
): String {
    val fallback = pclUniqueVersionName(rawDefault.ifBlank { "整合包" })
    val typed = PclNamePrompt.ask(defaultName = fallback, title = title, label = label)
    val finalName = pclUniqueVersionName(typed.ifBlank { fallback })
    if (typed.isNotBlank() && finalName != typed.trim()) {
        PclHintHost.push("已改用「$finalName」（原名不可用或已被占用）。", PclHintTheme.Yellow)
    }
    return finalName
}
