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

package com.kcl.pcl.ui.screens.main

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kcl.pcl.BuildKeys
import com.kcl.pcl.R
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.ui.activities.CrashType
import com.kcl.pcl.ui.components.BackgroundCard
import com.kcl.pcl.ui.components.MarqueeText
import com.kcl.pcl.ui.components.ScalingActionButton
import com.kcl.pcl.ui.components.verticalScrollWithBar
import com.kcl.pcl.ui.pcl2.components.PclBackgroundImageLayer
import com.kcl.pcl.ui.pcl2.components.PclButton
import com.kcl.pcl.ui.pcl2.components.PclCard
import com.kcl.pcl.ui.pcl2.components.PclHint
import com.kcl.pcl.ui.pcl2.components.PclHintTheme
import com.kcl.pcl.ui.pcl2.components.PclText
import com.kcl.pcl.ui.pcl2.data.PclSkinSettings
import com.kcl.pcl.ui.pcl2.theme.PclDimens
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import com.kcl.pcl.ui.theme.backgroundColor
import com.kcl.pcl.ui.theme.onBackgroundColor

@Composable
fun ErrorScreen(
    crashType: CrashType,
    shareLogs: Boolean = true,
    canUpload: Boolean = false,
    canRestart: Boolean = true,
    onShareLogsClick: () -> Unit = {},
    onUploadClick: () -> Unit = {},
    onRestartClick: () -> Unit = {},
    onExitClick: () -> Unit = {},
    onOrientationChanged: (Int) -> Unit = {},
    body: @Composable ColumnScope.() -> Unit
) {
    //获取方向信息，展示两套不同的UI
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    //PCL2 界面风格：整页换成 PCL 的窗体 / 卡片 / 按钮，不再用 Material 的 Scaffold
    if (AllSettings.usePcl2Ui.state) {
        PclErrorScreen(
            crashType = crashType,
            shareLogs = shareLogs,
            canUpload = canUpload,
            canRestart = canRestart,
            isLandscape = isLandscape,
            onShareLogsClick = onShareLogsClick,
            onUploadClick = onUploadClick,
            onRestartClick = onRestartClick,
            onExitClick = onExitClick,
            onRotateClick = {
                onOrientationChanged(
                    if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                )
            },
            body = body
        )
        return
    }

    if (isLandscape) {
        ErrorScreenLandscape(
            crashType = crashType,
            shareLogs = shareLogs,
            canUpload = canUpload,
            canRestart = canRestart,
            onShareLogsClick = onShareLogsClick,
            onUploadClick = onUploadClick,
            onRestartClick = onRestartClick,
            onExitClick = onExitClick,
            onRotateClick = {
                onOrientationChanged(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            },
            body = body
        )
    } else {
        ErrorScreenPortrait(
            crashType = crashType,
            shareLogs = shareLogs,
            canUpload = canUpload,
            canRestart = canRestart,
            onShareLogsClick = onShareLogsClick,
            onUploadClick = onUploadClick,
            onRestartClick = onRestartClick,
            onExitClick = onExitClick,
            onRotateClick = {
                onOrientationChanged(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            },
            body = body
        )
    }
}

/**
 * 崩溃页面（横屏页面）
 */
@Composable
private fun ErrorScreenLandscape(
    crashType: CrashType,
    shareLogs: Boolean,
    canUpload: Boolean,
    canRestart: Boolean,
    onShareLogsClick: () -> Unit,
    onUploadClick: () -> Unit,
    onRestartClick: () -> Unit,
    onExitClick: () -> Unit,
    onRotateClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            val text = when (crashType) {
                //在启动器崩溃的时候，显示一个较为严重的标题
                CrashType.LAUNCHER_CRASH -> stringResource(R.string.crash_launcher_title, BuildKeys.LAUNCHER_NAME)
                //游戏运行崩溃了，大概和启动器关系不大，仅展示应用标题
                CrashType.GAME_CRASH -> BuildKeys.LAUNCHER_NAME
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = text)
                //旋转竖屏
                IconButton(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    onClick = onRotateClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fullscreen_exit),
                        contentDescription = null
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        containerColor = backgroundColor(),
        contentColor = onBackgroundColor(),
        contentWindowInsets = if (AllSettings.launcherFullScreen.state) {
            WindowInsets()
        } else {
            WindowInsets.safeContent.only(WindowInsetsSides.Horizontal)
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ErrorContent(
                modifier = Modifier.weight(7f),
                body = body
            )

            ActionLayout(
                modifier = Modifier.weight(3f),
                crashType = crashType,
                shareLogs = shareLogs,
                canUpload = canUpload,
                canRestart = canRestart,
                onShareLogsClick = onShareLogsClick,
                onUploadClick = onUploadClick,
                onRestartClick = onRestartClick,
                onExitClick = onExitClick
            )
        }
    }
}

/**
 * 崩溃页面（竖屏版本）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorScreenPortrait(
    crashType: CrashType,
    shareLogs: Boolean,
    canUpload: Boolean,
    canRestart: Boolean,
    onShareLogsClick: () -> Unit,
    onUploadClick: () -> Unit,
    onRestartClick: () -> Unit,
    onExitClick: () -> Unit,
    onRotateClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    //控制下拉菜单的显示状态
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    //应用标题
                    Text(text = BuildKeys.LAUNCHER_NAME)
                },
                actions = {
                    //旋转横屏
                    IconButton(
                        onClick = onRotateClick
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mobile_rotate_filled),
                            contentDescription = null
                        )
                    }

                    Row {
                        IconButton(
                            onClick = { showMenu = true }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.generic_more)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (canUpload) {
                                DropdownMenuItem(
                                    text = {
                                        MarqueeText(text = stringResource(R.string.crash_link_share_button))
                                    },
                                    onClick = {
                                        showMenu = false
                                        onUploadClick()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    MarqueeText(text = stringResource(R.string.crash_share_logs))
                                },
                                onClick = {
                                    showMenu = false
                                    onShareLogsClick()
                                },
                                enabled = shareLogs
                            )
                            if (canRestart) {
                                DropdownMenuItem(
                                    text = {
                                        MarqueeText(text = stringResource(R.string.crash_restart))
                                    },
                                    onClick = {
                                        showMenu = false
                                        onRestartClick()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    MarqueeText(text = stringResource(R.string.crash_exit))
                                },
                                onClick = {
                                    showMenu = false
                                    onExitClick()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScrollWithBar(rememberScrollState())
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            //崩溃类型
            Text(
                text = stringResource(
                    R.string.crash_type,
                    stringResource(crashType.textRes)
                )
            )

            if (crashType == CrashType.LAUNCHER_CRASH) {
                //仅在启动器崩溃时，才显示这行略显严重的文本
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.crash_launcher_title, BuildKeys.LAUNCHER_NAME)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = body
                )
            } else {
                body()
            }
        }
    }
}

@Composable
private fun ErrorContent(
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit
) {
    BackgroundCard(
        modifier = modifier,
        influencedByBackground = false,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = body
        )
    }
}

@Composable
private fun ActionLayout(
    modifier: Modifier = Modifier,
    crashType: CrashType,
    shareLogs: Boolean,
    canUpload: Boolean,
    canRestart: Boolean,
    onShareLogsClick: () -> Unit = {},
    onUploadClick: () -> Unit = {},
    onRestartClick: () -> Unit = {},
    onExitClick: () -> Unit = {}
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = stringResource(
                    R.string.crash_type,
                    stringResource(crashType.textRes)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)
        ) {
            if (canUpload) {
                ScalingActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onUploadClick
                ) {
                    MarqueeText(text = stringResource(R.string.crash_link_share_button))
                }
            }
            if (shareLogs) {
                ScalingActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onShareLogsClick
                ) {
                    MarqueeText(text = stringResource(R.string.crash_share_logs))
                }
            }
            if (canRestart) {
                ScalingActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestartClick
                ) {
                    MarqueeText(text = stringResource(R.string.crash_restart))
                }
            }
            ScalingActionButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onExitClick
            ) {
                MarqueeText(text = stringResource(R.string.crash_exit))
            }
        }
    }
}

/* ============================================================================
 *  PCL2 界面风格下的退出 / 崩溃页
 *
 *  这一套完全自绘，不碰 Material 的 Scaffold / Surface —— PCL 的界面由它自己的
 *  资源字典驱动，任何 Material 默认样式（elevation 染色、圆角 28 的胶囊按钮、
 *  M3 的 surfaceContainer 底色）套上去都会立刻「不像 PCL」。
 * ==========================================================================*/

/**
 * PCL 风格的退出 / 崩溃页。
 *
 * 结构照 `FormMain`：标题栏（三段渐变 48 高）+ 窗体（渐变或纯色底 + 背景图）。
 * 横屏时左「说明卡片」右「操作列」，竖屏时上下堆叠 —— 和原生的两套布局一一对应，
 * 只是把 Material 的卡片与按钮换成 `MyCard` / `MyButton`。
 */
@Composable
private fun PclErrorScreen(
    crashType: CrashType,
    shareLogs: Boolean,
    canUpload: Boolean,
    canRestart: Boolean,
    isLandscape: Boolean,
    onShareLogsClick: () -> Unit,
    onUploadClick: () -> Unit,
    onRestartClick: () -> Unit,
    onExitClick: () -> Unit,
    onRotateClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    val title = when (crashType) {
        //启动器崩溃：PCL 会把「哪一环出错了」写进标题栏
        CrashType.LAUNCHER_CRASH -> stringResource(R.string.crash_launcher_title, BuildKeys.LAUNCHER_NAME)
        //游戏崩溃与启动器关系不大，只显示应用标题
        CrashType.GAME_CRASH -> BuildKeys.LAUNCHER_NAME
    }
    val typeText = stringResource(R.string.crash_type, stringResource(crashType.textRes))

    //全屏关掉时避开刘海/导航栏（与原生分支的 insets 口径一致）
    val insetsModifier = if (AllSettings.launcherFullScreen.state) {
        Modifier
    } else {
        Modifier.windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PclErrorBackdrop(modifier = Modifier.matchParentSize())

        Column(modifier = Modifier.fillMaxSize().then(insetsModifier)) {
            PclErrorTitleBar(title = title, onRotateClick = onRotateClick)

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    //左边：说明卡片（`MyCard` 白底 + 5 圆角），内容自己滚
                    PclCard(
                        modifier = Modifier
                            .weight(7f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScrollWithBar(state = rememberScrollState())
                                .padding(all = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = body
                        )
                    }

                    //右边：崩溃类型 + 操作按钮（贴底）
                    PclErrorActions(
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight(),
                        typeText = typeText,
                        shareLogs = shareLogs,
                        canUpload = canUpload,
                        canRestart = canRestart,
                        onShareLogsClick = onShareLogsClick,
                        onUploadClick = onUploadClick,
                        onRestartClick = onRestartClick,
                        onExitClick = onExitClick,
                        fillHeight = true
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScrollWithBar(state = rememberScrollState())
                        .padding(all = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PclText(text = typeText, color = pclPalette.color1)

                    PclCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(all = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = body
                        )
                    }

                    PclErrorActions(
                        modifier = Modifier.fillMaxWidth(),
                        typeText = null,
                        shareLogs = shareLogs,
                        canUpload = canUpload,
                        canRestart = canRestart,
                        onShareLogsClick = onShareLogsClick,
                        onUploadClick = onUploadClick,
                        onRestartClick = onRestartClick,
                        onExitClick = onExitClick,
                        fillHeight = false
                    )
                }
            }
        }
    }
}

/**
 * PCL 窗体底：纯色 #F5F5F5 或三段渐变（`ModSecret.ThemeRefreshMain`），再叠一层背景图。
 *
 * 与 `PclShell` 的写法逐项一致，「界面 → 个性化」里换过的背景图 / 透明度这里同样生效 ——
 * 崩溃页也是启动器的一部分，不该是另一套观感。
 */
@Composable
private fun PclErrorBackdrop(modifier: Modifier = Modifier) {
    val p = pclPalette
    val colorful = PclSkinSettings.colorfulBackground
    val opacityPercent = AllSettings.launcherBackgroundOpacity.state
    val alpha = (opacityPercent / 100f).coerceIn(0.2f, 1f)

    Box(
        modifier = modifier
            .background(if (colorful) Color.Transparent else Color(0xFFF5F5F5))
            .drawWithCache {
                if (!colorful) return@drawWithCache onDrawBehind {}
                val stops = p.bodyGradient
                //色标 Offset -0.1 / 0.4 / 1.1 归一化到 0..1（Compose 不接受负值）
                val brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to stops[0].copy(alpha = alpha),
                        ((0.4f + 0.1f) / 1.2f) to stops[1].copy(alpha = alpha),
                        1f to stops[2].copy(alpha = alpha),
                    ),
                    start = Offset(size.width * 0.9f, 0f),
                    end = Offset(size.width * 0.1f, size.height),
                )
                onDrawBehind { drawRect(brush = brush, size = size) }
            }
    ) {
        PclBackgroundImageLayer(
            modifier = Modifier.matchParentSize(),
            opacityPercent = opacityPercent
        )
    }
}

/**
 * PCL 标题栏：高 48、三段渐变底、17 号白字居中，右侧放「旋转屏幕」按钮
 * （对应 `PanTitle` 里 `BtnTitle` 的位置，28×28）。
 */
@Composable
private fun PclErrorTitleBar(
    title: String,
    onRotateClick: () -> Unit
) {
    val p = pclPalette
    val brush = remember(p.titleBarGradient) { Brush.horizontalGradient(p.titleBarGradient) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PclDimens.titleBarHeight)
            .background(brush)
    ) {
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp),
            style = PclTextStyle.base(p).copy(
                fontSize = 17.sp,
                lineHeight = PclTextStyle.lineHeight(17),
                color = Color.White,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(PclDimens.titleButtonSize)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onRotateClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_mobile_rotate_filled
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * PCL 风格的操作列：崩溃类型（只有横屏会有位置放它）+ 一组 `MyButton`。
 *
 * @param fillHeight 横屏时把按钮压到底部（中间用 weight 撑开）；竖屏在滚动容器里，
 *   不能出现 weight（无限高度约束会崩），所以传 false。
 */
@Composable
private fun PclErrorActions(
    modifier: Modifier = Modifier,
    typeText: String?,
    shareLogs: Boolean,
    canUpload: Boolean,
    canRestart: Boolean,
    onShareLogsClick: () -> Unit,
    onUploadClick: () -> Unit,
    onRestartClick: () -> Unit,
    onExitClick: () -> Unit,
    fillHeight: Boolean
) {
    Column(modifier = modifier) {
        if (typeText != null) {
            PclText(text = typeText, color = pclPalette.color1)
        }
        if (fillHeight) {
            Spacer(modifier = Modifier.weight(1f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canUpload) {
                PclButton(
                    text = stringResource(R.string.crash_link_share_button),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onUploadClick
                )
            }
            if (shareLogs) {
                PclButton(
                    text = stringResource(R.string.crash_share_logs),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onShareLogsClick
                )
            }
            if (canRestart) {
                PclButton(
                    text = stringResource(R.string.crash_restart),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestartClick
                )
            }
            PclButton(
                text = stringResource(R.string.crash_exit),
                modifier = Modifier.fillMaxWidth(),
                onClick = onExitClick
            )
        }
    }
}

/**
 * PCL 风格的退出说明正文：说明 → 疑似原因（灰底等宽块）→ 下一步建议（`MyHint`）。
 *
 * 由 `ErrorActivity` 在 PCL 界面下调用；原生界面继续用那边原有的 Material 文本。
 */
@Composable
fun PclCrashBody(
    message: String,
    messageBody: String,
    reasonTitle: String,
    suspectedCause: String?,
    adviceTitle: String,
    advice: String?
) {
    val p = pclPalette

    PclText(text = message)
    PclText(text = messageBody, color = p.color1)

    suspectedCause?.let { cause ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(p.gray6)
                .padding(all = 12.dp)
        ) {
            PclText(text = reasonTitle, bold = true)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = cause,
                style = PclTextStyle.base(p).copy(
                    fontFamily = FontFamily.Monospace,
                    color = p.color1,
                )
            )
        }
    }

    if (advice != null) {
        PclText(text = adviceTitle, bold = true)
        PclHint(text = advice, theme = PclHintTheme.Blue)
    }
}

@Preview
@Composable
private fun PreviewErrorScreen() {
    MaterialExpressiveTheme {
        ErrorScreen(
            crashType = CrashType.LAUNCHER_CRASH,
            shareLogs = true,
            canUpload = true,
            canRestart = true,
            onShareLogsClick = {},
            onUploadClick = {},
            onRestartClick = {},
            onExitClick = {},
            body = {}
        )
    }
}