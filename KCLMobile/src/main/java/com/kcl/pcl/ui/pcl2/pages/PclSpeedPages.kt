/*
 * KCL Mobile — PCL2 UI port
 *
 * 下载管理页（对应 PCL 的 `PageSpeedLeft` + `PageSpeedRight`，页面名 `LangPageNameDownloadManagement`）。
 *
 * 左栏（PageSpeedLeft.xaml）：总进度 / 下载速度 / 剩余文件，
 * 每块都是「14 号 ColorBrush3 标题 + 2px 渐变分隔线（Margin 25,7）+ 20 号数值」，
 * 用 1* / 0.6* 的间隔把几块均匀撑开（PCL 的第四块「剩余线程」只在调试模式显示，这里同样不显示）。
 *
 * 右栏（PageSpeedRight.xaml + PageSpeedLeft.xaml.vb:69-208）：
 * 每个任务一张 `MyCard`（`Margin="0,0,0,15"`，标题 = 任务名），
 * 内容 `Grid Margin="14,40,15,10"`、列宽 50 / *、每个分任务一行 26 高：
 *   · 未开始 → 三个点（Width=18、Height=6、Margin="0,7,0,0"）
 *   · 进行中 → `floor(progress*100)%` 文本（ColorBrush3）
 *   · 已完成 → 对勾（Height=16、Width=15、Margin="0,3,0,0"）
 *   · 失败   → 叉（15×15、Margin="0,1,0,0"）
 * 卡片右上角是关闭按钮（Height=20、Margin="0,10,10,0"、LogoScale=1.1），点它会取消任务。
 *
 * 卡片种类（每条任务一张）：
 *   · 「安装 Minecraft xxx」—— 游戏安装器的各阶段（`PclRuntime.installTasks`）；
 *   · 「安装整合包：xxx」—— 整合包的**下载 + 安装合成一条**。这两段分属两个引擎
 *     （下载走 `TaskSystem`、安装走 `ModpackImporter`），登记进 `PclRuntime` 后才在这张卡里
 *     连成一条连续的进度；本地导入的整合包没有下载段，直接从安装阶段开始；
 *   · 「下载任务」—— 其余走全局任务系统的单文件下载（模组 / 资源包 / 光影包）。
 *     整合包的下载任务已经并进它自己的卡片，这里要剔掉，否则同一次下载会被列两遍。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kcl.pcl.coroutine.Task
import com.kcl.pcl.coroutine.TaskStage
import com.kcl.pcl.coroutine.TaskSystem
import com.kcl.pcl.coroutine.TitledTask
import com.kcl.pcl.ui.androidText
import com.kcl.pcl.ui.pcl2.PclContent
import com.kcl.pcl.ui.pcl2.PclStrings
import com.kcl.pcl.ui.pcl2.PclSubPage
import com.kcl.pcl.ui.pcl2.components.PclCard
import com.kcl.pcl.ui.pcl2.components.PclIconButton
import com.kcl.pcl.ui.pcl2.components.PclIcons
import com.kcl.pcl.ui.pcl2.components.PclText
import com.kcl.pcl.ui.pcl2.data.LocalPclRuntime
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import com.kcl.pcl.ui.toAndroidString
import kotlin.math.floor

/**
 * `PageSpeedLeft` + `PageSpeedRight` —— 下载管理页。
 *
 * PCL 里点「开始下载」就是把这个任务丢进任务列表并切到这一页
 * （`FormMain.xaml.vb:1603` 的悬浮下载按钮也是切到这里），而不是弹一个进度对话框。
 */
fun pclDownloadManagerPage(): PclSubPage = PclSubPage(
    title = PclStrings.LangPageNameDownloadManagement,
    leftContent = { PclSpeedLeft() },
    content = { PclSpeedRight() },
)

// ---------------------------------------------------------------- 左栏

/** `PageSpeedLeft`：总进度 / 下载速度 / 剩余文件。 */
@Composable
private fun PclSpeedLeft() {
    val runtime = LocalPclRuntime.current
    val installTasks by runtime.installTasks.collectAsState()
    val modpackTasks by runtime.modpackTaskList.collectAsState()
    val modpackDownloads by runtime.modpackDownloadTasks.collectAsState()
    val downloadTasks by runtime.downloadTasks.collectAsState()
    val batchProgress by runtime.downloadProgress.collectAsState()

    // 整合包任务的下载半截已经并进它自己的那张卡片了，这里要把它从「下载任务」里剔掉，
    // 否则同一次下载会在左栏被统计两遍（总进度与剩余文件都会偏）。
    // 现在可以**多条**整合包同时跑，所以按 id 集合整批过滤，不能只比一个 id。
    val modpackDownloadIds = modpackDownloads.map { it.id }.toSet()
    val soloDownloadTasks = downloadTasks.filterNot { it.id in modpackDownloadIds }

    val allTasks: List<Task> =
        installTasks.map { it.task } + modpackTasks + soloDownloadTasks

    // 每个任务的进度 / 阶段 / 速度都是 `StateFlow` —— 直接读 `.value` 是**不会触发重组**的，
    // 只有任务列表本身变化（新增/移除任务）时才刷新，表现出来就是安装期间左栏数字一直冻着、
    // 看着像卡死。这里显式收集它们，任何一个变化都会让整段重新测量。
    // 用 `key(task)` 固定槽位，避免任务增删时状态串到别的任务上。
    val live = allTasks.map { task ->
        key(task) {
            val progress by task.progress.collectAsState()
            val stage by task.stage.collectAsState()
            val speed by task.rateBytesPerSec.collectAsState()
            Triple(progress, stage, speed)
        }
    }

    // 总进度 = 各任务进度的平均值（PCL：Tasks.Select(Progress).Average()，保留两位小数）
    val progressText = if (allTasks.isEmpty()) {
        "100 %"
    } else {
        val average = live.map { (progress, stage, _) ->
            if (stage == TaskStage.COMPLETED) {
                1.0
            } else {
                progress.coerceIn(0f, 1f).toDouble()
            }
        }.average()
        if (average > 0.999999) {
            "100 %"
        } else {
            val scaled = average * 100
            val integer = floor(scaled).toInt()
            val decimal = floor((scaled - integer) * 100).toInt()
            "$integer.${decimal.toString().padStart(2, '0')} %"
        }
    }

    val speedText = formatByteSize(live.sumOf { it.third ?: 0L }) + "/s"
    // 剩余文件来自下载引擎的全局进度快照；引擎没在跑时显示 "0*"（与 PCL 一致）
    val fileText = if (allTasks.isEmpty()) {
        "0"
    } else {
        batchProgress?.let { progress ->
            (progress.totalFiles - progress.downloadedFiles).coerceAtLeast(0).toString()
        } ?: "0*"
    }

    // PageSpeedLeft.xaml 没有 ScrollViewer：行定义是
    //   `1* | Auto,Auto,Auto | 0.6* | Auto,Auto,Auto | 0.6* | Auto,Auto,Auto | 1*`
    // （调试模式才有的「剩余线程」四行在正式版被压成 0 高）。
    // 所以这里不能用 verticalScroll —— 滚动容器会把高度约束变成无穷，weight 失去意义。
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        PclSpeedBlock(PclStrings.LangSpeedTotalProgress, progressText)
        Spacer(Modifier.weight(0.6f))
        PclSpeedBlock(PclStrings.LangSpeedSpeed, speedText)
        Spacer(Modifier.weight(0.6f))
        PclSpeedBlock(PclStrings.LangSpeedLeftFiles, fileText)
        Spacer(Modifier.weight(1f))
    }
}

/** 左栏的一个数据块：标题 + 渐变分隔线 + 数值。 */
@Composable
private fun PclSpeedBlock(label: String, value: String) {
    val p = pclPalette
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = PclTextStyle.base(p).copy(fontSize = 14.sp, color = p.color3),
            textAlign = TextAlign.Center,
        )
        // ShapeLine：Height=2、Margin="25,7"，两端是 ColorObjectBg1（#bee0eafd）而非全透明
        Canvas(
            modifier = Modifier
                .padding(horizontal = 25.dp, vertical = 7.dp)
                .fillMaxWidth()
                .height(2.dp),
        ) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to p.bg1,
                    0.02f to p.color3,
                    0.98f to p.color3,
                    1f to p.bg1,
                ),
            )
        }
        Text(
            text = value,
            style = PclTextStyle.base(p).copy(fontSize = 20.sp, color = p.color1),
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- 右栏

/** `PageSpeedRight.PanMain`：`Margin="25,25,25,10"`，每个任务一张卡片。 */
@Composable
private fun PclSpeedRight() {
    val runtime = LocalPclRuntime.current
    val installTasks by runtime.installTasks.collectAsState()
    val modpackEntries by runtime.modpackEntries.collectAsState()
    val modpackDownloads by runtime.modpackDownloadTasks.collectAsState()
    val downloadTasks by runtime.downloadTasks.collectAsState()
    // 见 PclSpeedLeft：整合包的下载半截有自己的卡片，不在这里再列一次。
    // 多条整合包同时跑，按 id 集合整批过滤。
    val modpackDownloadIds = modpackDownloads.map { it.id }.toSet()
    val soloDownloadTasks = downloadTasks.filterNot { it.id in modpackDownloadIds }

    PclContent(
        contentPadding = PaddingValues(start = 25.dp, top = 25.dp, end = 25.dp, bottom = 10.dp),
    ) {
        if (installTasks.isEmpty() && modpackEntries.isEmpty() && soloDownloadTasks.isEmpty()) {
            PclCard(modifier = Modifier.fillMaxWidth()) {
                PclText(
                    text = "当前没有正在进行的下载或安装任务。",
                    color = pclPalette.gray2,
                )
            }
            return@PclContent
        }

        // 游戏安装：一个安装器 = 一张卡片，「安装的各阶段」= 卡片里的分任务
        if (installTasks.isNotEmpty()) {
            PclSpeedTaskCard(
                title = runtime.installTitle.ifBlank { "安装游戏" },
                tasks = installTasks,
                onCancel = { runtime.cancelInstall() },
            )
        }

        // 整合包：下载与安装本来是两个引擎，这里合成同一张卡 ——
        // 第一行是「下载整合包」，后面跟安装器的各个阶段（解压 / 读取 / 拷贝…），
        // 和「下载版本」那张卡一样是一条连续的、任何页面切换都不会中断的任务。
        //
        // **一条整合包一张卡**（以前几张共用一张、只显示最后登记的那条）：右上角的取消
        // 只作用于自己那一条，别的整合包该下还在下、该装还在装。
        modpackEntries.forEach { entry ->
            PclSpeedTaskCard(
                title = entry.title,
                tasks = entry.rows,
                waiting = entry.waiting,
                onCancel = { runtime.cancelModpackImport(entry.id) },
            )
        }

        // 其他走全局任务系统的下载（模组 / 资源包 / 光影包等）
        soloDownloadTasks.forEach { task ->
            PclSpeedTaskCard(
                title = "下载任务",
                tasks = listOf(TitledTask(title = androidText("下载任务"), task = task)),
                onCancel = { TaskSystem.cancelTask(task) },
            )
        }
    }
}

/** 一个任务的卡片：标题 + 分任务列表 + 右上角取消。 */
@Composable
private fun PclSpeedTaskCard(
    title: String,
    tasks: List<TitledTask>,
    onCancel: () -> Unit,
    /**
     * 下载已经完成、安装还没吐出第一个阶段（多半正排在别的整合包后面等安装锁）。
     * 此时 [tasks] 是空的，卡片会空着一块 —— 用一句「等待中」顶上。
     */
    waiting: Boolean = false,
) {
    PclCard(
        title = title,
        modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp),
        // PageSpeedLeft.xaml.vb:149 —— Grid Margin="14,40,15,10"
        contentPadding = PaddingValues(start = 14.dp, top = 40.dp, end = 15.dp, bottom = 10.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (tasks.isEmpty() && waiting) {
                    // 与分任务行同高（26dp）、图标列同为 50，视觉上不会跳一格。
                    // 图标位用 `WaitingDots()` —— PCL 的「等待中」三个点，和 PREPARING 的分任务一致。
                    Row(
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier.width(50.dp).height(26.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            WaitingDots()
                        }
                        PclText(
                            text = "等待前一个整合包安装完成…",
                            modifier = Modifier.weight(1f).padding(top = 3.dp),
                            color = pclPalette.gray2,
                        )
                    }
                }
                tasks.forEach { titled ->
                    PclSpeedSubTaskRow(titled)
                }
            }
            // BtnCancel：Height=20、Margin="0,10,10,0"、LogoScale=1.1、右上角
            PclIconButton(
                logo = PclIcons.speedCancel,
                size = 20.dp,
                logoScale = 1.1f,
                contentDescription = PclStrings.LangDialogBtnCancel,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
                onClick = onCancel,
            )
        }
    }
}

/** 分任务一行：图标列 50 + 名称，行高 26。 */
@Composable
private fun PclSpeedSubTaskRow(titled: TitledTask) {
    val p = pclPalette
    val context = LocalContext.current
    val progress by titled.task.progress.collectAsState()
    val stage by titled.task.stage.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(50.dp).height(26.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (stage) {
                TaskStage.COMPLETED -> Icon(
                    imageVector = PclIcons.speedCheck,
                    contentDescription = null,
                    tint = p.color3,
                    modifier = Modifier.padding(top = 3.dp).size(width = 15.dp, height = 16.dp),
                )

                // `progress < 0` = 引擎明确表示「进度不确定」（Task.updateProgress(-1f)）。
                // 以前这里直接 floor(-1 * 100) → 恒显示「0%」，于是「下载 Forge 安装器」
                // 「补全游戏库」这类拿不到总字节的任务看起来永远卡在百分之零。
                // PCL 对这种情况显示等待动画（PageSpeed 里 Progress = -1 就不画数字），这里照做。
                TaskStage.RUNNING -> if (progress < 0f) {
                    WaitingDots()
                } else {
                    Text(
                        text = "${floor(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = PclTextStyle.base(p).copy(fontSize = 13.sp, color = p.color3),
                        textAlign = TextAlign.Center,
                    )
                }

                TaskStage.PREPARING -> WaitingDots()
            }
        }
        Text(
            text = titled.title.toAndroidString(context),
            style = PclTextStyle.base(p).copy(
                fontSize = 13.sp,
                // PCL 里未开始的分任务用的是默认前景，进行中的会换成更深的前景色
                color = if (stage == TaskStage.PREPARING) p.gray2 else p.color1,
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(top = 3.dp),
        )
    }
}

/** 「等待中」的三个点（PCL 用三段圆弧路径，这里按同样比例画三个圆）。 */
@Composable
private fun WaitingDots() {
    val p = pclPalette
    // 按要求改成**静止**的三点：不再做跑马灯呼吸动画。
    // 静态图形同样能表示「未开始 / 等待中」，而且不占每帧重绘。
    Canvas(modifier = Modifier.padding(top = 7.dp).size(width = 18.dp, height = 6.dp)) {
        val radius = size.height / 2f
        listOf(0.14f, 0.5f, 0.86f).forEach { fraction ->
            drawCircle(
                color = p.color3.copy(alpha = 0.7f),
                radius = radius,
                center = Offset(size.width * fraction, radius),
            )
        }
    }
}

/** 字节/秒 → 可读文本（对应 PCL 的 `StringUtils.FormatByteSize`）。 */
private fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.2f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.2f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
