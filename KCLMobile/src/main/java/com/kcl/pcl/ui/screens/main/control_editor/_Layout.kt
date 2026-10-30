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

package com.kcl.pcl.ui.screens.main.control_editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movtery.colorpicker.components.TransparentChecker
import com.movtery.colorpicker.rememberColorPickerController
import com.kcl.pcl.R
import com.kcl.pcl.ui.components.ColorPickerDialog
import com.kcl.pcl.ui.components.SliderValueEditDialog
import com.kcl.pcl.ui.pcl2.components.PclMenuCheckRow
import com.kcl.pcl.ui.pcl2.components.PclMenuComboRow
import com.kcl.pcl.ui.pcl2.components.PclMenuSliderRow
import com.kcl.pcl.ui.pcl2.components.PclMenuSurface
import com.kcl.pcl.ui.pcl2.components.PclSlider
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import java.text.DecimalFormat

/**
 * 控制布局编辑器各弹窗里的「信息行」组件。
 *
 * 这里原来整套是 Material 3 的：薰衣草色的圆角行（`itemColor(false)`）、M3 Switch、
 * 紫色胶囊（`LittleTextLabel`）、`SegmentedButton`、宽度占一半的 M3 Slider。
 * PCL 界面里没有任何一处长这样，所以整层换成 PCL2 的控件观感：
 *
 *   · 行   = `MyListItem` 的指向态（透明底 + 指向时 `ColorBrush7 @ alpha 190` 与 1px `ColorBrush6` 边框，
 *            圆角 6）；选中态用左侧 5px `ColorBrush3` 竖条，PCL **不画选中边框**；
 *   · 开关 = `MyCheckBox`；下拉 = `MyComboBox`；滑杆 = `MySlider`。
 *
 * **所有函数签名保持不变**，各弹窗的调用点一行都不用改。
 * 只丢弃了两处 PCL 没有对应物的参数（`maxListHeight`、滑杆的 `fineTuningControl`：
 * 原实现是「就地展开列表 / 拖拽微调」，换成下拉框与可点数值框后精度靠直接输入保证）。
 */
@Composable
fun InfoLayoutSliderItem(
    modifier: Modifier = Modifier,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    decimalFormat: String = "#0.00",
    suffix: String? = null,
    fineTuningControl: Boolean = true,
    fineTuningStep: Float = 0.5f,
    enabled: Boolean = true,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
) {
    var showValueEditDialog by remember { mutableStateOf(false) }
    val formatter = remember(decimalFormat) { DecimalFormat(decimalFormat) }

    PclMenuSliderRow(
        modifier = modifier,
        title = title,
        valueText = formatter.format(value) + (suffix ?: ""),
        enabled = enabled,
        onValueClick = { showValueEditDialog = true },
    ) {
        PclSlider(
            value = pclFractionOf(value, valueRange),
            enabled = enabled,
            onValueChange = { onValueChange(pclValueOf(it, valueRange)) },
            onValueChangeFinished = onValueChangeFinished,
        )
    }

    if (showValueEditDialog) {
        SliderValueEditDialog(
            onDismissRequest = { showValueEditDialog = false },
            title = title,
            valueRange = valueRange,
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = {
                onValueChangeFinished?.invoke()
            }
        )
    }
}

/**
 * 列表信息设置项 —— 改成 PCL 的下拉框（`MyComboBox`）。
 *
 * 原实现是「就地展开一列 RadioButton」，M3 的圆点选择器在 PCL 里没有对应物。
 */
@Composable
fun <E> InfoLayoutListItem(
    modifier: Modifier = Modifier,
    title: String,
    items: List<E>,
    selectedItem: E,
    onItemSelected: (E) -> Unit,
    getItemText: @Composable (E) -> String,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    maxListHeight: Dp = 200.dp
) {
    // `getItemText` 是 @Composable（要用 stringResource），不能塞进普通 lambda，
    // 先在组合里把每个条目的文字算好。
    val labels = items.associateWith { getItemText(it) }
    PclMenuComboRow(
        modifier = modifier,
        label = title,
        items = items,
        selected = selectedItem,
        labelOf = { labels[it] ?: it.toString() },
        onSelect = onItemSelected,
    )
}

@Composable
fun InfoLayoutSwitchItem(
    modifier: Modifier = Modifier,
    title: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified
) {
    PclMenuCheckRow(
        modifier = modifier,
        text = title,
        checked = value,
        enabled = enabled,
        onCheckedChange = onValueChange,
    )
}

@Composable
fun <E> InfoLayoutSelectItem(
    modifier: Modifier = Modifier,
    title: String,
    options: List<E>,
    current: E,
    onClick: (E) -> Unit,
    label: @Composable SingleChoiceSegmentedButtonRowScope.(E) -> Unit,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified
) {
    val p = pclPalette
    InfoLayoutItem(
        modifier = modifier,
        onClick = {},
        color = color,
        contentColor = contentColor
    ) {
        Text(
            text = title,
            style = PclTextStyle.listTitle(p),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 保留 `SegmentedButton`（`label` 参数的接收者是它的 Scope），只把配色换成 PCL 的：
        // 选中 = 实心 ColorBrush3 + 白字，未选中 = 半透明白底 + ColorBrush1 字；并去掉 M3 的对勾图标。
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = current == option,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = p.color3,
                        activeContentColor = p.white,
                        activeBorderColor = p.color3,
                        inactiveContainerColor = p.halfWhite,
                        inactiveContentColor = p.color1,
                        inactiveBorderColor = p.color3,
                    ),
                    icon = {},
                    onClick = {
                        onClick(option)
                    },
                    label = {
                        label(option)
                    }
                )
            }
        }
    }
}

@Composable
fun InfoLayoutTextItem(
    modifier: Modifier = Modifier,
    title: String,
    onClick: () -> Unit,
    showArrow: Boolean = true,
    selected: Boolean = false,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    InfoLayoutTextItem(
        modifier = modifier,
        title = title,
        icon = {
            if (showArrow) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    painter = painterResource(R.drawable.ic_arrow_right_rounded),
                    contentDescription = null,
                    tint = pclPalette.color3,
                )
            }
        },
        onClick = onClick,
        selected = selected,
        color = color,
        contentColor = contentColor,
        enabled = enabled,
    )
}

@Composable
fun InfoLayoutTextItem(
    modifier: Modifier = Modifier,
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    selected: Boolean = false,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    InfoLayoutItem(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        color = color,
        contentColor = contentColor,
        enabled = enabled,
    ) {
        Text(
            text = title,
            style = PclTextStyle.listTitle(pclPalette),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        icon()
    }
}

@Composable
fun InfoLayoutColorItem(
    modifier: Modifier = Modifier,
    title: String,
    color: Color,
    onColorChanged: (Color) -> Unit
) {
    var showColorDialog by remember { mutableStateOf(false) }

    InfoLayoutTextItem(
        modifier = modifier,
        title = title,
        icon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(24.dp)) {
                    TransparentChecker(
                        modifier = Modifier.fillMaxSize(),
                        gridSize = 18f
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color = color)
                    )
                }

                Icon(
                    modifier = Modifier.size(20.dp),
                    painter = painterResource(R.drawable.ic_arrow_right_rounded),
                    contentDescription = null,
                    tint = pclPalette.color3,
                )
            }
        },
        onClick = {
            showColorDialog = true
        }
    )

    if (showColorDialog) {
        var tempColor by remember { mutableStateOf(color) }
        val colorController = rememberColorPickerController(initialColor = tempColor)

        val currentColor by remember(colorController) { colorController.color }

        LaunchedEffect(currentColor) {
            onColorChanged(currentColor)
        }

        ColorPickerDialog(
            colorController = colorController,
            onCancel = {
                onColorChanged(colorController.getOriginalColor())
                showColorDialog = false
            },
            onConfirm = { color ->
                showColorDialog = false
                onColorChanged(color)
            }
        )
    }
}

/**
 * 信息行底板 —— PCL 的列表项观感。
 *
 * [selected] 在 PCL 里不是描边，而是左侧一条 5px `ColorBrush3` 竖条；[content] 里的文字
 * 会继承 PCL 的正文色（调用处多半写的是 M3 字体的 `MarqueeText`，只给了字号没给颜色）。
 */
@Composable
fun InfoLayoutItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(6.dp),
    borderColor: Color = Color.Transparent,
    color: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val p = pclPalette
    CompositionLocalProvider(LocalContentColor provides p.color1) {
        PclMenuSurface(
            modifier = modifier,
            enabled = enabled,
            radius = 6.dp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            leadingBar = selected,
            onClick = onClick,
        ) {
            content()
        }
    }
}

/** 把实际值换算成 0..1 的滑杆比例。 */
private fun pclFractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** 把 0..1 的滑杆比例换算回实际值。 */
private fun pclValueOf(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return range.start + fraction.coerceIn(0f, 1f) * span
}
