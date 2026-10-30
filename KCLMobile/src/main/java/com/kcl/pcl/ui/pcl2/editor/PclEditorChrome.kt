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

package com.kcl.pcl.ui.pcl2.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kcl.pcl.R
import com.kcl.pcl.ui.pcl2.components.PclMenuPanel
import com.kcl.pcl.ui.pcl2.components.PclMenuPanels
import com.kcl.pcl.ui.pcl2.components.PclPainterIconButton
import com.kcl.pcl.ui.pcl2.components.animatePclColor
import com.kcl.pcl.ui.pcl2.components.rememberPclInteraction
import com.kcl.pcl.ui.pcl2.theme.PclDimens
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclHairline
import com.kcl.pcl.ui.pcl2.theme.pclPalette

/**
 * 控制布局编辑器的 **PCL 风格外壳**（编辑器专用件）。
 *
 * 通用的「菜单行 / 开关行 / 下拉行 / 浮层 / 图标按钮 / 对话框按钮」都在
 * `ui/pcl2/components/PclMenuControls.kt`（进游戏悬浮窗菜单、编辑器弹窗、编辑器浮层三处共用）；
 * 这里只放编辑器独有的几个：
 *   · [PclEditorMenuPanels] / [PclEditorPanel] —— 浮层入口（按编辑器原来的参数顺序包一层）
 *   · [PclLayerRow]           ↔ 原 `ControlLayerItem`
 *   · [PclEditorActionButton] ↔ 原 `ActionButton`（悬浮在画布上的 属性/复制/删除）
 */

/* ============================================================================
 *  浮层入口
 * ==========================================================================*/

/**
 * 编辑器左右两块浮层，对齐原
 * `DualMenuSubscreen(state, closeScreen, leftMenuContent, rightMenuTitle, rightMenuContent)`。
 *
 * 几何与原生完全一致（各占 1/3 宽、贴左右边、四周留 12、点空白处关闭），外观走 [PclMenuPanels]。
 */
@Composable
fun PclEditorMenuPanels(
    visible: Boolean,
    onClose: () -> Unit,
    leftTitle: String,
    leftContent: @Composable ColumnScope.() -> Unit,
    rightTitle: String,
    rightTitleAccessory: (@Composable () -> Unit)? = null,
    rightContent: @Composable ColumnScope.() -> Unit,
) {
    PclMenuPanels(
        visible = visible,
        onClose = onClose,
        leftTitle = leftTitle,
        leftContent = leftContent,
        rightTitle = rightTitle,
        rightTitleAccessory = rightTitleAccessory,
        rightContent = rightContent,
    )
}

/** 一块浮层 = 一张 PCL 卡片（编辑器侧的透传，方便调用处只依赖一个包）。 */
@Composable
fun PclEditorPanel(
    title: String?,
    modifier: Modifier = Modifier,
    titleAccessory: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PclMenuPanel(
        title = title,
        modifier = modifier,
        titleAccessory = titleAccessory,
        content = content,
    )
}

/* ============================================================================
 *  控件层行
 * ==========================================================================*/

/**
 * 一行控制层，对应原生 `ControlLayerItem`。
 *
 * 原生是「M3 Surface + 4dp 主色描边表示选中 + 眼睛 / 更多 / 拖拽三个 IconButton」。
 * PCL 的列表项选中**不画边框**，只有左侧一条 5px `ColorBrush3` 竖条 + 前景色转深色
 * （`MyListItem.xaml.vb` 的 `SetChecked`），这里照那个来。
 *
 * [dragHandleModifier] 由调用处传入 `Modifier.draggableHandle()` —— 拖拽手势的挂载点必须
 * 留在调用处，否则 `ReorderableItem` 认不出谁是可拖的手柄。
 */
@Composable
fun PclLayerRow(
    name: String,
    selected: Boolean,
    hidden: Boolean,
    enabled: Boolean,
    dragHandleModifier: Modifier,
    onToggleHide: () -> Unit,
    onAttribute: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    val background = animatePclColor(
        if (active && enabled) p.color7.copy(alpha = 190f / 255f) else Color.Transparent,
        120,
    )
    val borderColor = animatePclColor(if (active && enabled) p.color6 else Color.Transparent, 120)
    // 选中态：前景色 → `ColorBrush2`；左侧竖条高度 = 项高 − 12（`RectCheck.Margin="0,6,0,6"`）
    val contentColor = animatePclColor(if (selected) p.color2 else p.color1, 200)
    val barHeight by animateDpAsState(
        targetValue = if (selected && enabled) height - 12.dp else 0.dp,
        animationSpec = tween(300),
        label = "pclLayerCheckBar",
    )
    val corner = RoundedCornerShape(if (height < 40.dp) 0.dp else 6.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(corner)
            .background(background)
            .border(pclHairline(), borderColor, corner)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer(alpha = if (enabled) 1f else 0.4f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(6.dp).height(height),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (barHeight > 0.dp) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(p.color3),
                    )
                }
            }
            PclPainterIconButton(
                painter = painterResource(
                    if (hidden) R.drawable.ic_visibility_off_outlined
                    else R.drawable.ic_visibility_outlined
                ),
                enabled = enabled,
                size = 32.dp,
                logoSize = 19.dp,
                tint = if (hidden) p.gray3 else p.color1,
                onClick = onToggleHide,
            )
            Text(
                text = name,
                style = PclTextStyle.listTitle(p),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            PclPainterIconButton(
                painter = painterResource(R.drawable.ic_more_horiz),
                enabled = enabled,
                size = 32.dp,
                logoSize = 19.dp,
                contentDescription = name,
                onClick = onAttribute,
            )
            // 拖拽手柄：触控区撑满整行高，方便按住就拖
            Box(
                modifier = dragHandleModifier
                    .fillMaxHeight()
                    .width(34.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = if (enabled) p.gray3 else p.gray5,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/* ============================================================================
 *  悬浮操作按钮
 * ==========================================================================*/

/**
 * 悬浮在画布上的操作按钮（属性 / 复制 / 删除），对应原生 `ActionButton`。
 *
 * 原生用 `MaterialTheme.colorScheme.primary` 的实心胶囊，在 PCL 界面里那是一片
 * 突兀的深蓝。这里换成 PCL 的 `MyButton`：底色半透明白、1px 边框、文字颜色与边框一致
 * （PCL 里 `Foreground` 绑定 `BorderBrush`），指向时一并转 `ColorBrush3`。
 *
 * 与源码的一点差异：底色用 `ColorBrushSemiWhite`（#BBFFFFFF）而不是 `HalfWhite`（#55FFFFFF）——
 * 按钮悬浮在**游戏画面预览**之上，太透的话深色文字会糊在深色画面上。
 */
@Composable
fun PclEditorActionButton(
    text: String,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    val borderColor = animatePclColor(
        when {
            !enabled -> p.gray4
            active -> p.color3
            else -> p.color1
        }
    )
    val background = animatePclColor(if (active && enabled) p.color7 else p.semiWhite)

    Row(
        modifier = modifier
            .heightIn(min = PclDimens.buttonHeight)
            .clip(RoundedCornerShape(PclDimens.buttonCorner))
            .background(background)
            .border(pclHairline(), borderColor, RoundedCornerShape(PclDimens.buttonCorner))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = PclDimens.buttonPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = borderColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = PclTextStyle.button(p).copy(color = borderColor),
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}
