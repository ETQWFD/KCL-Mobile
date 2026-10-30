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

package com.kcl.pcl.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.ui.pcl2.components.PclMenuCheckRow
import com.kcl.pcl.ui.pcl2.components.PclMenuComboRow
import com.kcl.pcl.ui.pcl2.components.PclMenuPanel
import com.kcl.pcl.ui.pcl2.components.PclMenuPanels
import com.kcl.pcl.ui.pcl2.components.PclMenuRow
import com.kcl.pcl.ui.pcl2.components.PclMenuSliderRow
import com.kcl.pcl.ui.pcl2.components.PclMenuSurface
import com.kcl.pcl.ui.pcl2.components.PclSlider
import com.kcl.pcl.ui.pcl2.theme.PclMotion
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import com.kcl.pcl.ui.theme.itemColor
import com.kcl.pcl.ui.theme.onItemColor
import java.text.DecimalFormat

/**
 * 菜单状态
 */
enum class MenuState {
    /**
     * 初始化 (刚加载时)
     */
    NONE {
        override fun next() = SHOW
    },

    /**
     * 展示中
     */
    SHOW {
        override fun next() = HIDE
    },

    /**
     * 隐藏中
     */
    HIDE {
        override fun next() = SHOW
    };

    abstract fun next(): MenuState
}

/* ============================================================================
 *  样式分流
 *
 *  游戏内悬浮菜单曾被整体改成 PCL2 观感，但启动器可以在「设置 → 启动 →
 *  界面风格」里切回原生 ZL 界面 —— 那时候菜单还是 PCL 样子就很割裂。
 *  所以这里每个函数都变成「分流器」：
 *
 *    · `AllSettings.usePcl2Ui == true`  → PCL2 控件（圆角 5 卡片 + MyListItem 行，
 *      实现在本文件下半部分，前缀 `Pcl`）；
 *    · `AllSettings.usePcl2Ui == false` → 上游原生 Material 实现（`MenuZl.kt`，
 *      前缀 `Zl`）。
 *
 *  默认参数一律沿用上游写法（`MaterialTheme.shapes.large` / `itemColor()`），
 *  因为 PCL 分支本来就忽略 shape / color / contentColor，只有 ZL 分支会用它们。
 * ==========================================================================*/

/**
 * 单块（右侧）菜单浮层。
 */
@Composable
fun MenuSubscreen(
    state: MenuState,
    closeScreen: () -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    backgroundColor: Color = Color.Black.copy(alpha = 0.25f),
    content: @Composable ColumnScope.() -> Unit
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuSubscreen(
            state = state,
            closeScreen = closeScreen,
            backgroundColor = backgroundColor,
            content = content,
        )
    } else {
        ZlMenuSubscreen(
            state = state,
            closeScreen = closeScreen,
            shape = shape,
            backgroundColor = backgroundColor,
            content = content,
        )
    }
}

/**
 * 左右两块菜单浮层。
 *
 * @param leftTitle  PCL 卡片标题（仅在 PCL2 界面下生效）。为 null 时左栏不占标题位
 *                   （例如游戏内菜单的左栏自带图标标签栏）。
 * @param rightTitle PCL 卡片标题（仅在 PCL2 界面下生效）。
 * @param leftMenuTitle  原生界面的左栏标题布局，`AllSettings.usePcl2Ui == false` 时使用。
 * @param rightMenuTitle 原生界面的右栏标题布局，同上。
 *
 * 两套标题参数并存，是为了同一处调用点能同时喂给两种界面风格。
 */
@Composable
fun DualMenuSubscreen(
    state: MenuState,
    closeScreen: () -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    backgroundColor: Color = Color.Black.copy(alpha = 0.25f),
    titleHeight: Dp = 48.dp,
    leftMenuTitle: (@Composable BoxScope.() -> Unit)? = null,
    leftMenuContent: @Composable ColumnScope.() -> Unit = {},
    rightMenuTitle: (@Composable BoxScope.() -> Unit)? = null,
    rightMenuContent: @Composable ColumnScope.() -> Unit = {},
    leftTitle: String? = null,
    rightTitle: String? = null,
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuPanels(
            visible = state == MenuState.SHOW,
            onClose = closeScreen,
            leftTitle = leftTitle,
            leftContent = leftMenuContent,
            rightTitle = rightTitle,
            rightContent = rightMenuContent,
        )
    } else {
        ZlDualMenuSubscreen(
            state = state,
            closeScreen = closeScreen,
            shape = shape,
            backgroundColor = backgroundColor,
            titleHeight = titleHeight,
            leftMenuTitle = leftMenuTitle,
            leftMenuContent = leftMenuContent,
            rightMenuTitle = rightMenuTitle,
            rightMenuContent = rightMenuContent,
        )
    }
}

/* ============================================================================
 *  行
 * ==========================================================================*/

/**
 * 一行菜单文字。
 *
 * [appendLayout] 是行尾附加物，直接摆在文字右侧。
 */
@Composable
fun MenuTextButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean = true,
    influencedByBackground: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
    appendLayout: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuTextButton(
            modifier = modifier,
            text = text,
            enabled = enabled,
            appendLayout = appendLayout,
            onClick = onClick,
        )
    } else {
        ZlMenuTextButton(
            modifier = modifier,
            text = text,
            enabled = enabled,
            influencedByBackground = influencedByBackground,
            shape = shape,
            color = color,
            contentColor = contentColor,
            appendLayout = appendLayout,
            onClick = onClick,
        )
    }
}

/**
 * 一行带开关的菜单项。
 *
 * `onSwitch` 收到的是**切换后**的值。
 */
@Composable
fun MenuSwitchButton(
    modifier: Modifier = Modifier,
    text: String,
    switch: Boolean,
    onSwitch: (Boolean) -> Unit,
    enabled: Boolean = true,
    influencedByBackground: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuSwitchButton(
            modifier = modifier,
            text = text,
            switch = switch,
            onSwitch = onSwitch,
            enabled = enabled,
        )
    } else {
        ZlMenuSwitchButton(
            modifier = modifier,
            text = text,
            switch = switch,
            onSwitch = onSwitch,
            enabled = enabled,
            influencedByBackground = influencedByBackground,
            shape = shape,
            color = color,
            contentColor = contentColor,
        )
    }
}

/**
 * 一行「标题 + 下拉」。
 *
 * PCL 界面下是下拉框（`MyComboBox`）；原生界面下是就地展开的 RadioButton 列表。
 */
@Composable
fun <E> MenuListLayout(
    modifier: Modifier = Modifier,
    title: String,
    items: List<E>,
    currentItem: E,
    onItemChange: (E) -> Unit,
    getItemText: @Composable (E) -> String,
    selectedItemLayout: (@Composable ColumnScope.(E) -> Unit)? = null,
    enabled: Boolean = true,
    maxListHeight: Dp = 200.dp,
    influencedByBackground: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
) {
    if (AllSettings.usePcl2Ui.state) {
        // `getItemText` 是 @Composable（要用 stringResource），不能塞进普通 lambda，
        // 先在组合里把每个条目的文字算好。
        val labels = items.associateWith { getItemText(it) }
        PclMenuComboRow(
            modifier = modifier,
            label = title,
            items = items,
            selected = currentItem,
            enabled = enabled,
            labelOf = { labels[it] ?: it.toString() },
            onSelect = onItemChange,
        )
    } else {
        ZlMenuListLayout(
            modifier = modifier,
            title = title,
            items = items,
            currentItem = currentItem,
            onItemChange = onItemChange,
            getItemText = getItemText,
            selectedItemLayout = selectedItemLayout,
            enabled = enabled,
            maxListHeight = maxListHeight,
            influencedByBackground = influencedByBackground,
            shape = shape,
            color = color,
            contentColor = contentColor,
        )
    }
}

/**
 * 一行「标题 + 滑杆」（整数）。
 */
@Composable
fun MenuSliderLayout(
    modifier: Modifier = Modifier,
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChangeFinished: (Int) -> Unit = {},
    suffix: String? = null,
    influencedByBackground: Boolean = false,
    colors: SliderColors = SliderDefaults.colors(),
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuSliderLayout(
            modifier = modifier,
            title = title,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            suffix = suffix,
        )
    } else {
        ZlMenuSliderLayout(
            modifier = modifier,
            title = title,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            suffix = suffix,
            influencedByBackground = influencedByBackground,
            colors = colors,
            shape = shape,
            color = color,
            contentColor = contentColor,
        )
    }
}

/**
 * 一行「标题 + 滑杆」（小数）。
 */
@Composable
fun MenuSliderLayout(
    modifier: Modifier = Modifier,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChangeFinished: (Float) -> Unit = {},
    suffix: String? = null,
    decimalFormat: String = "#0.00",
    influencedByBackground: Boolean = false,
    colors: SliderColors = SliderDefaults.colors(),
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuSliderLayout(
            modifier = modifier,
            title = title,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            suffix = suffix,
            decimalFormat = decimalFormat,
        )
    } else {
        ZlMenuSliderLayout(
            modifier = modifier,
            title = title,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            suffix = suffix,
            decimalFormat = decimalFormat,
            influencedByBackground = influencedByBackground,
            colors = colors,
            shape = shape,
            color = color,
            contentColor = contentColor,
        )
    }
}

/**
 * 通用行底板（保留给需要自定义内容的调用处）。
 */
@Composable
fun MenuButtonLayout(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    influencedByBackground: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor(influencedByBackground),
    contentColor: Color = onItemColor(),
    blur: Int = AllSettings.backgroundBlur.state,
    onClick: () -> Unit = {},
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit
) {
    if (AllSettings.usePcl2Ui.state) {
        PclMenuButtonLayout(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    } else {
        ZlMenuButtonLayout(
            modifier = modifier,
            enabled = enabled,
            influencedByBackground = influencedByBackground,
            shape = shape,
            color = color,
            contentColor = contentColor,
            blur = blur,
            onClick = onClick,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/* ============================================================================
 *  PCL2 版实现
 *
 *  版式：浮层 = `MyCard`（圆角 5、影子 3、底色 `#F5FFFFFF`、标题 13 号加粗）；
 *  行 = `MyListItem` 的指向态（`ColorBrush7 @ alpha 190` + 1px `ColorBrush6` 边框）；
 *  开关 = `MyCheckBox`；下拉 = `MyComboBox`；滑杆 = `MySlider`。
 * ==========================================================================*/

@Composable
private fun PclMenuSubscreen(
    state: MenuState,
    closeScreen: () -> Unit,
    backgroundColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = state == MenuState.SHOW
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = backgroundColor)
                    .clickable(
                        indication = null,
                        interactionSource = scrimInteraction,
                        onClick = closeScreen
                    )
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(fraction = 1f / 3f)
                .fillMaxHeight()
                .padding(top = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally(
                    animationSpec = tween(200, easing = PclMotion.easeOutFluent)
                ) { 40 },
                exit = fadeOut() + slideOutHorizontally { 40 }
            ) {
                PclMenuPanel(
                    title = null,
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PclMenuTextButton(
    modifier: Modifier,
    text: String,
    enabled: Boolean,
    appendLayout: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    if (appendLayout == null) {
        PclMenuRow(
            modifier = modifier,
            text = text,
            enabled = enabled,
            height = 42.dp,
            onClick = onClick,
        )
    } else {
        // 行尾有附加内容时不能直接用 PclMenuRow（它只画文字），这里补一个同款底板
        PclMenuSurface(
            modifier = modifier,
            enabled = enabled,
            radius = 6.dp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            onClick = onClick,
        ) {
            Text(
                text = text,
                style = PclTextStyle.listTitle(pclPalette),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            appendLayout()
        }
    }
}

@Composable
private fun PclMenuSwitchButton(
    modifier: Modifier,
    text: String,
    switch: Boolean,
    onSwitch: (Boolean) -> Unit,
    enabled: Boolean,
) {
    PclMenuCheckRow(
        modifier = modifier,
        text = text,
        checked = switch,
        enabled = enabled,
        onCheckedChange = onSwitch,
    )
}

/** 把实际值换算成 0..1 的滑杆比例。 */
private fun pclSliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** 把 0..1 的滑杆比例换算回实际值。 */
private fun pclSliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return range.start + fraction.coerceIn(0f, 1f) * span
}

@Composable
private fun PclMenuSliderLayout(
    modifier: Modifier,
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChangeFinished: (Int) -> Unit,
    suffix: String?,
) {
    var showInputDialog by remember { mutableStateOf(false) }
    // `PclSlider` 的 `pointerInput` 只捕获**首次组合**时的 lambda，
    // 直接在这里用参数 `value` 会拿到拖动前的旧值（松手保存 = 保存旧值）。用这个兜住最新值。
    val latestValue = rememberUpdatedState(value)

    PclMenuSliderRow(
        modifier = modifier,
        title = title,
        valueText = "$value${suffix ?: ""}",
        enabled = enabled,
        onValueClick = { showInputDialog = true },
    ) {
        PclSlider(
            value = pclSliderFraction(value.toFloat(), valueRange),
            enabled = enabled,
            onValueChange = { fraction ->
                onValueChange(pclSliderValue(fraction, valueRange).toInt())
            },
            // 松手时落盘。**不传这个回调就只有输入框那条路会保存**：
            // 拖动时只走 `updateState`（纯内存），切后台 / 重启游戏后 state 从 MMKV 重新读，
            // 于是「灵敏度改了又自己跳回去」。
            onValueChangeFinished = { onValueChangeFinished(latestValue.value) },
        )
    }

    if (showInputDialog) {
        SliderValueEditDialog(
            onDismissRequest = { showInputDialog = false },
            title = title,
            valueRange = valueRange,
            value = value.toFloat(),
            onValueChange = { onValueChangeFinished(it.toInt()) },
            intCheck = true
        )
    }
}

@Composable
private fun PclMenuSliderLayout(
    modifier: Modifier,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChangeFinished: (Float) -> Unit,
    suffix: String?,
    decimalFormat: String,
) {
    val formatter = DecimalFormat(decimalFormat)
    var showInputDialog by remember { mutableStateOf(false) }
    // 同上：PclSlider 内部只捕获首次组合的 lambda，松手要读最新值
    val latestValue = rememberUpdatedState(value)

    PclMenuSliderRow(
        modifier = modifier,
        title = title,
        valueText = formatter.format(value) + (suffix ?: ""),
        enabled = enabled,
        onValueClick = { showInputDialog = true },
    ) {
        PclSlider(
            value = pclSliderFraction(value, valueRange),
            enabled = enabled,
            onValueChange = { fraction -> onValueChange(pclSliderValue(fraction, valueRange)) },
            onValueChangeFinished = { onValueChangeFinished(latestValue.value) },
        )
    }

    if (showInputDialog) {
        SliderValueEditDialog(
            onDismissRequest = { showInputDialog = false },
            title = title,
            valueRange = valueRange,
            value = value,
            onValueChange = { onValueChangeFinished(it) },
        )
    }
}

@Composable
private fun PclMenuButtonLayout(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable RowScope.() -> Unit
) {
    PclMenuSurface(
        modifier = modifier,
        enabled = enabled,
        radius = 6.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        onClick = onClick,
        content = content,
    )
}
