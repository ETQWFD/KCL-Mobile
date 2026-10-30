/*
 * KCL Mobile — PCL2 UI port
 *
 * 下载/安装任务管理弹窗（对应 PCL 的「下载管理」窗口）。
 *
 * 展示 KCL 全局任务系统里的真实任务：进度、速度、消息，并支持取消。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kcl.pcl.coroutine.Task
import com.kcl.pcl.coroutine.TaskStage
import com.kcl.pcl.coroutine.TaskSystem
import com.kcl.pcl.coroutine.TitledTask
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import com.kcl.pcl.ui.toAndroidString

/**
 * 「下载管理」弹窗。
 *
 * @param tasks 来自 `TaskSystem.tasksFlow` 的全局下载任务（模组 / 资源包等）
 * @param installTasks 来自 `PclRuntime.installTasks` 的游戏安装任务
 *        —— 点「后台运行」把它收起来之后，进度就靠这里看
 */
@Composable
fun PclDownloadTaskDialog(
    tasks: List<Task>,
    installTasks: List<TitledTask> = emptyList(),
    onCancelInstall: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val p = pclPalette
    // PclDialogContainer 负责 Dialog + 进出动画 + 「延迟到动画播完才真正 dismiss」
    PclDialogContainer(onDismiss = onDismiss) { requestClose ->
        PclCard(
            modifier = Modifier.widthIn(min = 300.dp, max = 520.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Text(
                text = "下载管理",
                style = PclTextStyle.cardTitle(p).copy(fontSize = 15.sp, lineHeight = PclTextStyle.lineHeight(15),),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (tasks.isEmpty() && installTasks.isEmpty()) {
                Text(
                    text = "当前没有正在进行的下载或安装任务。",
                    style = PclTextStyle.base(p),
                    modifier = Modifier.padding(top = 15.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    installTasks.forEach { titled ->
                        PclTitledTaskRow(
                            titled = titled,
                            onCancel = onCancelInstall,
                        )
                    }
                    tasks.forEach { task ->
                        PclTaskRow(task)
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (tasks.isNotEmpty() || installTasks.isNotEmpty()) {
                    PclButton(
                        text = "全部取消",
                        onClick = {
                            if (installTasks.isNotEmpty()) onCancelInstall()
                            TaskSystem.stopAll()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                }
                PclButton(
                    text = "关闭",
                    colorState = PclColorState.Highlight,
                    onClick = requestClose,
                )
            }
        }
    }
}

/** 游戏安装任务的一行（标题来自 `TitledTask.title`）。 */
@Composable
private fun PclTitledTaskRow(titled: TitledTask, onCancel: () -> Unit) {
    val p = pclPalette
    val context = LocalContext.current
    val progress by titled.task.progress.collectAsState()
    val message by titled.task.message.collectAsState()
    val speed by titled.task.rateBytesPerSec.collectAsState()
    val stage by titled.task.stage.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titled.title.toAndroidString(context),
                style = PclTextStyle.listTitle(p),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            val speedValue = speed
            if (speedValue != null && speedValue > 0) {
                Text(text = formatSpeed(speedValue), style = PclTextStyle.listInfo(p))
                Spacer(Modifier.width(8.dp))
            }
            if (stage != TaskStage.COMPLETED) {
                PclTextButton(text = "取消", onClick = onCancel)
            }
        }
        val detail = message?.toAndroidString(context)
        if (!detail.isNullOrBlank()) {
            Text(text = detail, style = PclTextStyle.listInfo(p), maxLines = 1)
        }
        if (progress >= 0f) {
            PclProgressBar(progress = progress, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun PclTaskRow(task: Task) {
    val p = pclPalette
    val context = LocalContext.current
    val progress by task.progress.collectAsState()
    val message by task.message.collectAsState()
    val speed by task.rateBytesPerSec.collectAsState()
    val stage by task.stage.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message?.toAndroidString(context)?.takeIf { it.isNotBlank() }
                    ?: if (stage == TaskStage.COMPLETED) "已完成" else "准备中…",
                style = PclTextStyle.listTitle(p),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            val speedValue = speed
            if (speedValue != null && speedValue > 0) {
                Text(text = formatSpeed(speedValue), style = PclTextStyle.listInfo(p))
                Spacer(Modifier.width(8.dp))
            }
            if (stage != TaskStage.COMPLETED) {
                PclTextButton(text = "取消", onClick = { TaskSystem.cancelTask(task) })
            }
        }
        if (progress >= 0f) {
            PclProgressBar(progress = progress, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private fun formatSpeed(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB/s".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB/s".format(bytes / 1024.0)
    else -> "$bytes B/s"
}
