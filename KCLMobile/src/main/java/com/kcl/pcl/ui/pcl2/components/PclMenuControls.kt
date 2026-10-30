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

package com.kcl.pcl.ui.pcl2.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kcl.pcl.ui.pcl2.theme.LocalPclPalette
import com.kcl.pcl.ui.pcl2.theme.PclDimens
import com.kcl.pcl.ui.pcl2.theme.PclMotion
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclHairline
import com.kcl.pcl.ui.pcl2.theme.pclPalette

/**
 * PCL 风格的「菜单 / 设置行 / 对话框行」通用件。
 *
 * 三种界面共用同一套观感：
 *   1. `ui/components/Menu.kt`        —— 进游戏后悬浮窗里的菜单；
 *   2. `control_editor/_Layout.kt`    —— 控制布局编辑器各弹窗里的设置行；
 *   3. `ui/pcl2/editor` 下的编辑器外壳             —— 自己独有的浮层与图层列表。
 *
 * 版式依据取自 PCL2 自己的控件，不另立规则：
 *   · 行 = `MyListItem` 的指向态 —— 平时透明，指向（触屏为按下）时浮出
 *     `ColorBrush7 @ alpha 190` 的底色 + 1px `ColorBrush6` 边框，
 *     高 <40 圆角 0（PCL 的 `RectBack.CornerRadius`），否则 6；选中态是左侧 5px `ColorBrush3` 竖条；
 *   · 开关 = `MyCheckBox`；下拉 = `MyComboBox`；滑杆 = `MySlider`；
 *   · 按钮 = `MyButton`（`Foreground` 绑定 `BorderBrush`，指向时一起转 `ColorBrush3`）；
 *   · 卡片 / 浮层 = `MyCard`（圆角 5、影子 3、底色 `#F5FFFFFF`、标题 13 号加粗在 (15,12)）。
 *
 * 这一组组件【不】套 `PclTheme`：`PclTheme` 会把 fontScale 钉成 1、density 抬到 ≥1，
 * 而游戏内菜单与编辑器都是按设备 density 排的，套上去会让浮层与画面错位。
 * 只取 `pclPalette`（有默认值）与 PCL 的字号 / 尺寸常量。
 */

/* ============================================================================
 *  基础行
 * ==========================================================================*/

/**
 * 菜单 / 设置行的统一底板：透明底 + 指向浮层 + 可点。
 *
 * [content] 是 `RowScope`，方便调用处用 `Modifier.weight(1f)` 排「标题在左、控件在右」。
 */
@Composable
fun PclMenuSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    radius: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    /**
     * 选中时在左侧画一条 5px `ColorBrush3` 竖条 —— PCL 的列表项选中就靠它
     * （`MyListItem.xaml.vb:SetChecked`，PCL **不**画选中边框）。
     */
    leadingBar: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    val shape = RoundedCornerShape(radius)
    val valid = enabled && onClick != null
    // MyListItem.xaml.vb:584/620 —— 指向浮层：底色 ColorBg1（ColorBrush7 @ alpha 190）+ 1px ColorBrush6 边框
    val background = animatePclColor(
        if (active && valid) p.color7.copy(alpha = 190f / 255f) else Color.Transparent,
        120,
    )
    val borderColor = animatePclColor(if (active && valid) p.color6 else Color.Transparent, 120)
    val barHeight by animateDpAsState(
        targetValue = if (leadingBar && enabled) 30.dp else 0.dp,
        animationSpec = tween(300),
        label = "pclMenuLeadingBar",
    )
    val barColor = if (leadingBar) p.color3 else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(pclHairline(), borderColor, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .graphicsLayer(alpha = if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = horizontalArrangement,
        ) {
            if (leadingBar) {
                // 竖条不占内容宽度：用负边距贴到左边缘内侧
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .width(5.dp)
                        .height(barHeight.coerceAtLeast(0.dp))
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
            content()
        }
    }
}

/**
 * 一行可点的菜单文字，对应 PCL 列表项的文字行。
 *
 * @param height 行高；<40 时圆角为 0（PCL 的规则），否则 6。
 */
@Composable
fun PclMenuRow(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val p = pclPalette
    PclMenuSurface(
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        // PCL 的 `RectBack.CornerRadius = If(Height > 40, 6, 0)`：矮行（左栏条目）不圆角
        radius = if (height < 40.dp) 0.dp else 6.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        onClick = onClick,
    ) {
        Text(
            text = text,
            style = PclTextStyle.listTitle(p),
            color = if (enabled) p.color1 else p.gray4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一行带开关的菜单项。
 *
 * 开关在 PCL 里就是 `MyCheckBox`（不是 M3 的 Switch），整行可点。
 */
@Composable
fun PclMenuCheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PclMenuSurface(
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        onClick = { onCheckedChange(!checked) },
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = PclTextStyle.listTitle(pclPalette),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        // 传空串就是「只要方块、不要文字」——与 PclCheckBox 的判定一致
        PclCheckBox(
            text = "",
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 一行「标题 + 下拉框」，标题在上、下拉占满整行。
 *
 * 浮层 / 对话框常常只有半屏宽，标题与触发器挤在一行会把下拉压得只剩几十 dp。
 */
@Composable
fun <T> PclMenuComboRow(
    label: String,
    items: List<T>,
    selected: T?,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val p = pclPalette
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            text = label,
            style = PclTextStyle.listInfo(p),
            color = if (enabled) p.gray2 else p.gray4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        PclComboBox(
            items = items,
            selected = selected,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { item -> item?.let(labelOf).orEmpty() },
            onSelect = onSelect,
        )
    }
}

/**
 * 一行「标题 + 滑杆」，标题与数值同一行、滑杆在下。
 *
 * [valueText] 由调用处渲染，这样整数 / 小数 / 后缀的格式化都留在调用处（与原实现一致）。
 *
 * @param onValueClick 点「标题 / 数值」时触发（原实现是点整行弹出数值输入框）。
 *   刻意**不**把整行做成可点：滑杆自己会消费手势，两个点击区叠在一起时容易出现
 *   「拖完滑杆顺手把输入框也弹出来」。
 */
@Composable
fun PclMenuSliderRow(
    title: String,
    valueText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueClick: (() -> Unit)? = null,
    slider: @Composable () -> Unit,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    PclMenuSurface(
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onValueClick != null) {
                            Modifier.clickable(
                                enabled = enabled,
                                interactionSource = interaction,
                                indication = null,
                                onClick = onValueClick,
                            )
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = PclTextStyle.listTitle(p),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = valueText,
                    style = PclTextStyle.listTitle(p).copy(
                        color = when {
                            !enabled -> p.gray4
                            active -> p.color2
                            else -> p.color3
                        }
                    ),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            slider()
        }
    }
}

/**
 * PCL 的小胶囊标签（`MyLabel`）—— 替代原生那个用 `tertiary` 薰衣草色的 `LittleTextLabel`。
 *
 * 配色按背景色深浅反过来：浅底用蓝字、深底用白字，和 `PclPillButton` 的 `highlight` 分支同源。
 */
@Composable
fun PclMenuPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val p = pclPalette
    Text(
        text = text,
        style = PclTextStyle.listInfo(p).copy(color = p.color3),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(p.color6)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/* ============================================================================
 *  浮层（PCL 卡片）
 * ==========================================================================*/

/**
 * 左右两块浮层的容器。
 *
 * 几何与原生 `DualMenuSubscreen` 基本一致（各占 1/3 宽、贴左右边、点空白处关闭），
 * 只把「M3 Card + 48 高灰底居中标题条」换成「PCL 卡片 + 卡片标题」。
 *
 * 【与原生的一处差异】原生的浮层左右各留 12dp 外边距，在一整屏里看着「离屏幕边还有一段」；
 * PCL 的左栏是**紧贴窗体边缘**的，用户也反馈「悬浮球菜单太靠中间」。因此这里去掉水平外边距，
 * 让两块浮层直接贴住屏幕左右边（只保留上下 12dp）。
 *
 * @param leftTitle 传 null 表示左栏自己带顶栏内容（例如游戏内菜单的图标标签栏），此时不留标题位。
 */
@Composable
fun PclMenuPanels(
    visible: Boolean,
    onClose: () -> Unit,
    leftContent: @Composable ColumnScope.() -> Unit,
    rightContent: @Composable ColumnScope.() -> Unit,
    leftTitle: String? = null,
    rightTitle: String? = null,
    rightTitleAccessory: (@Composable () -> Unit)? = null,
) {
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            // 背景遮罩：PCL 没有这种「浮层 + 点空白关闭」的交互，浓度沿用原生值
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        indication = null,
                        interactionSource = scrimInteraction,
                        onClick = onClose,
                    ),
            )
        }

        // 左侧菜单
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction = 1f / 3f)
                .fillMaxHeight()
                .padding(vertical = 12.dp),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally(
                    animationSpec = tween(200, easing = PclMotion.easeOutFluent),
                ) { -40 },
                exit = fadeOut() + slideOutHorizontally { -40 },
            ) {
                PclMenuPanel(
                    title = leftTitle,
                    modifier = Modifier.fillMaxSize(),
                    content = leftContent,
                )
            }
        }

        // 右侧菜单
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(fraction = 1f / 3f)
                .fillMaxHeight()
                .padding(vertical = 12.dp),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally(
                    animationSpec = tween(200, easing = PclMotion.easeOutFluent),
                ) { 40 },
                exit = fadeOut() + slideOutHorizontally { 40 },
            ) {
                PclMenuPanel(
                    title = rightTitle,
                    modifier = Modifier.fillMaxSize(),
                    titleAccessory = rightTitleAccessory,
                    content = rightContent,
                )
            }
        }
    }
}

/**
 * 一块浮层 = 一张 PCL 卡片。
 *
 * @param title 为 null 时不画标题，并去掉卡片的默认内容边距（由调用处自己排顶栏）。
 */
@Composable
fun PclMenuPanel(
    title: String?,
    modifier: Modifier = Modifier,
    titleAccessory: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PclCard(
        title = title,
        modifier = modifier,
        // 浮层是「按一下就一直挂着」的，不需要卡片那种鼠标指向变标题色的动效
        hasMouseAnimation = false,
        titleAccessory = titleAccessory,
        // 有标题时留出 PCL 卡片的标题高度；没有标题时内容自己从顶端排起
        contentPadding = if (title == null) {
            PaddingValues(0.dp)
        } else {
            PaddingValues(
                start = 8.dp,
                top = PclDimens.cardContentTop,
                end = 8.dp,
                bottom = 8.dp,
            )
        },
        content = content,
    )
}

/* ============================================================================
 *  顶部图标标签栏
 * ==========================================================================*/

/**
 * 浮层顶部的图标标签栏 —— 替代 M3 的 `SecondaryScrollableTabRow`。
 *
 * PCL 的标签栏是「未选中 = `ColorBrush2` 图标，选中 = `ColorBrush3` 图标 + 3px 下划线」
 * （见 `MyPageLeft` 的选中条与顶部导航的配色），没有 M3 那种圆角指示器与灰底。
 */
@Composable
fun PclMenuTabRow(
    tabIcons: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tabIconSizes: List<Dp> = List(tabIcons.size) { 18.dp },
    height: Dp = 42.dp,
) {
    val p = pclPalette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        tabIcons.forEachIndexed { index, iconRes ->
            val selected = index == selectedIndex
            val (interaction, active) = rememberPclInteraction()
            val iconColor = animatePclColor(
                when {
                    selected -> p.color3
                    active -> p.color3
                    else -> p.color2
                }
            )
            val background = animatePclColor(
                if (active && !selected) p.color7.copy(alpha = 190f / 255f) else Color.Transparent,
                120,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(tabIconSizes.getOrElse(index) { 18.dp }),
                )
                // 选中条：贴底 3px
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(p.color3),
                    )
                }
            }
        }
    }
}

/* ============================================================================
 *  图标按钮与对话框按钮
 * ==========================================================================*/

/**
 * 图标按钮。
 *
 * `PclIconButton` 只吃 `ImageVector`，而编辑器 / 游戏内菜单这几个图标是原生 drawable，
 * 所以补一个 `Painter` 版本，配色与指向行为和 `PclIconButton` 的 `PclIconTheme.Color` 一致：
 * 平时 `ColorBrush4`、指向 `ColorBrush2`、禁用 `Gray5`。
 */
@Composable
fun PclPainterIconButton(
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 30.dp,
    logoSize: Dp = (size - 10.dp).coerceAtLeast(4.dp),
    tint: Color? = null,
    contentDescription: String? = null,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    val hoverColor = animatePclColor(if (active) p.color2 else p.color4)
    val color = if (!enabled) p.gray5 else tint ?: hoverColor

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(logoSize),
        )
    }
}

/**
 * 对话框底部按钮 —— `MyButton` 的观感。
 *
 * 刻意做成「M3 `FilledTonalButton` / `Button` 的同形替换」：调用处写的
 * `PclDialogButton(modifier = ..., onClick = ...) { MarqueeText(text = ...) }` 直接就能编译，
 * 因此各弹窗只需换名字，不必重排布局。
 *
 * @param highlight 主要动作（对应 M3 的 `Button`）；否则是次要动作（对应 `FilledTonalButton`）。
 */
@Composable
fun PclDialogButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val p = pclPalette
    val (interaction, active) = rememberPclInteraction()
    val borderColor = animatePclColor(
        when {
            !enabled -> p.gray4
            active -> p.color3
            highlight -> p.color2
            else -> p.color1
        }
    )
    val background = animatePclColor(
        when {
            active && enabled -> p.color7
            else -> p.halfWhite
        }
    )

    Box(
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
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 调用处传进来的多半是裸 Text / MarqueeText（不带样式），
        // 这里把颜色与字号按 PCL 的按钮文字喂下去，避免它们退回 M3 的默认字色。
        CompositionLocalProvider(
            LocalContentColor provides borderColor,
            LocalTextStyle provides PclTextStyle.button(p),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = PclDimens.buttonPaddingH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

/* ============================================================================
 *  输入框
 * ==========================================================================*/

/**
 * PCL 风格的输入框 —— 替代编辑器弹窗里原来那个 M3 `OutlinedTextField`。
 *
 * 【为什么内部还留着 M3 的 `OutlinedTextField`】它的光标管理、IME 动作、错误态与
 * `supportingText` 那套联动是成熟实现，重写一遍只会引入回归。这里做的是**换皮**：
 *   · 标签从「浮在边框缺口里」挪到框外上方（PCL 的写法：12 号灰字，`MyTextBox` 前置说明）；
 *   · 边框：常态 `ColorBrush3`、聚焦 `ColorBrush2`、错误 `ColorBrushRedDark`（1px、圆角 3）；
 *   · 底色 `#55ffffff`（`ColorBrushHalfWhite`）、正文 13 号 `ColorBrush1`、光标 `ColorBrush3`。
 *
 * 参数名与 `OwnOutlinedTextField` 对齐，弹窗里换名字即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PclOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = PclTextStyle.base(LocalPclPalette.current),
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions? = null,
    shape: Shape = RoundedCornerShape(PclDimens.buttonCorner),
) {
    val p = pclPalette
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        if (label != null) {
            CompositionLocalProvider(LocalContentColor provides p.gray2) {
                Box(modifier = Modifier.padding(bottom = 4.dp)) { label() }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { string ->
                onValueChange(if (singleLine) string.replace("\n", "") else string)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = p.color1),
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            keyboardOptions = keyboardOptions
                ?: if (singleLine) KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                else KeyboardOptions.Default,
            keyboardActions = keyboardActions ?: KeyboardActions(
                onDone = { focusManager.clearFocus(true) }
            ),
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.color1,
                unfocusedTextColor = p.color1,
                disabledTextColor = p.gray4,
                errorTextColor = p.redDark,
                focusedContainerColor = p.white,
                unfocusedContainerColor = p.halfWhite,
                errorContainerColor = p.halfWhite,
                cursorColor = p.color3,
                focusedBorderColor = p.color2,
                unfocusedBorderColor = p.color3,
                errorBorderColor = p.redDark,
                focusedLabelColor = p.color3,
                unfocusedLabelColor = p.gray2,
            ),
        )
    }
}

/* ============================================================================
 *  内部小工具
 * ==========================================================================*/

@Composable
internal fun rememberPclInteraction(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    return source to (hovered || pressed)
}

@Composable
internal fun animatePclColor(target: Color, durationMillis: Int = 100): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis),
        label = "pclMenuColor",
    ).value
