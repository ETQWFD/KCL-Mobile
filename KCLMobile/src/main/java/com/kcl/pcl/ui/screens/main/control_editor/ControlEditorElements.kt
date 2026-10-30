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

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movtery.guide.guideLazyList
import com.movtery.guide.guideNode
import com.movtery.layer_controller.data.HideLayerWhen
import com.movtery.layer_controller.data.JoystickTriggerMode
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.observable.ObservableButtonStyle
import com.movtery.layer_controller.observable.ObservableClickEventsProvider
import com.movtery.layer_controller.observable.ObservableControlLayer
import com.movtery.layer_controller.observable.ObservableJoystickStyle
import com.movtery.layer_controller.observable.ObservableTranslatableString
import com.movtery.layer_controller.observable.ObservableWidget
import com.movtery.layer_controller.utils.snap.SnapMode
import com.kcl.pcl.R
import com.kcl.pcl.bridge.CURSOR_DISABLED
import com.kcl.pcl.bridge.CURSOR_ENABLED
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.ui.components.DualMenuSubscreen
import com.kcl.pcl.ui.components.FloatingBall
import com.kcl.pcl.ui.components.MarqueeText
import com.kcl.pcl.ui.components.MenuListLayout
import com.kcl.pcl.ui.components.MenuState
import com.kcl.pcl.ui.components.MenuSwitchButton
import com.kcl.pcl.ui.components.MenuTextButton
import com.kcl.pcl.ui.components.ScalingActionButton
import com.kcl.pcl.ui.components.lazyScrollWithBar
import com.kcl.pcl.ui.guide.GuideKeys
import com.kcl.pcl.ui.pcl2.components.PclButton
import com.kcl.pcl.ui.pcl2.components.PclColorState
import com.kcl.pcl.ui.pcl2.components.PclMenuCheckRow
import com.kcl.pcl.ui.pcl2.components.PclMenuComboRow
import com.kcl.pcl.ui.pcl2.components.PclMenuRow
import com.kcl.pcl.ui.pcl2.components.PclPainterIconButton
import com.kcl.pcl.ui.pcl2.editor.PclEditorMenuPanels
import com.kcl.pcl.ui.pcl2.editor.PclLayerRow
import com.kcl.pcl.ui.pcl2.theme.LocalPclPalette
import com.kcl.pcl.ui.theme.cardColor
import com.kcl.pcl.ui.theme.onCardColor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 控制布局编辑器操作状态
 */
sealed interface EditorOperation {
    data object None : EditorOperation
    /** 选择了一个控件进行编辑 */
    data object SelectButton : EditorOperation
    /** 编辑控件层属性 */
    data class EditLayer(val layer: ObservableControlLayer) : EditorOperation
    /** 删除控件层 */
    data class DeleteLayer(val layer: ObservableControlLayer) : EditorOperation
    /** 打开控件外观列表 */
    data object OpenStyleList : EditorOperation
    /** 创建控件外观 */
    data object CreateStyle : EditorOperation
    /** 编辑控件外观 */
    data object EditButtonStyle : EditorOperation
    /** 删除控件外观 */
    data class DeleteButtonStyle(val style: ObservableButtonStyle) : EditorOperation
    /** 编辑摇杆样式 */
    data object EditJoystickStyle : EditorOperation
    /** 删除摇杆样式 */
    data class DeleteJoystickStyle(val style: ObservableJoystickStyle) : EditorOperation
    /** 打开摇杆样式列表 */
    data object OpenJoystickStyleList : EditorOperation
    /** 创建摇杆样式 */
    data object CreateJoystickStyle : EditorOperation
    /** 控制布局正在保存中 */
    data object Saving : EditorOperation
    /** 控制布局保存失败 */
    data class SaveFailed(val error: Throwable) : EditorOperation
}

/**
 * 控制布局编辑器对控件的操作状态
 */
sealed interface EditorWidgetOperation {
    data object None : EditorWidgetOperation
    /** 选择了一个控件, 并询问用户将其复制到哪些控制层 */
    data class CloneButton(val data: ObservableWidget, val layer: ObservableControlLayer) : EditorWidgetOperation
    /** 删除一个控件 */
    data class DeleteButton(val data: ObservableWidget, val layer: ObservableControlLayer) : EditorWidgetOperation
    /** 编辑控件的显示文本 */
    data class EditWidgetText(val string: ObservableTranslatableString) : EditorWidgetOperation
    /** 编辑切换控件层可见性事件 */
    data class SwitchLayersVisibility(
        val data: ObservableClickEventsProvider,
        val type: ClickEvent.Type
    ) : EditorWidgetOperation
    /** 编辑发送的文本 */
    data class SendText(val data: ObservableClickEventsProvider) : EditorWidgetOperation
}

/**
 * 控制布局编辑器的一些警告的操作状态
 */
sealed interface EditorWarningOperation {
    data object None : EditorWarningOperation
    /** 没有控件层，提醒用户添加 */
    data object WarningNoLayers : EditorWarningOperation
    /** 没有选择控件层，提醒用户选择 */
    data object WarningNoSelectLayer : EditorWarningOperation
}

/**
 * 预览控制布局的场景
 */
enum class PreviewScenario(
    val textRes: Int,
    val cursorMode: Int,
    val isCursorGrabbing: Boolean = cursorMode == CURSOR_DISABLED
) {
    InGame(R.string.control_editor_menu_preview_mode_in_game, cursorMode = CURSOR_DISABLED),
    InMenu(R.string.control_editor_menu_preview_mode_in_menu, cursorMode = CURSOR_ENABLED)
}

@Composable
fun VisibilityType.getVisibilityText(): String {
    val textRes = when (this) {
        VisibilityType.ALWAYS -> R.string.control_editor_edit_visibility_always
        VisibilityType.IN_GAME -> R.string.control_editor_edit_visibility_in_game
        VisibilityType.IN_MENU -> R.string.control_editor_edit_visibility_in_menu
    }
    return stringResource(textRes)
}

@Composable
fun JoystickTriggerMode.getTriggerModeText(): String {
    val textRes = when (this) {
        JoystickTriggerMode.DRAG -> R.string.control_editor_edit_joystick_trigger_mode_drag
        JoystickTriggerMode.TOUCH -> R.string.control_editor_edit_joystick_trigger_mode_touch
    }
    return stringResource(textRes)
}

@Composable
fun MenuBox(
    modifier: Modifier = Modifier,
    position: Offset,
    onPositionChanged: (Offset) -> Unit,
    opened: Boolean,
    onClick: () -> Unit
) {
    // 悬浮球的配色也统一到 PCL：`ColorBrush3` 蓝底 + 白图标，圆角用 PCL 的按钮圆角（3）。
    // 原生默认是「黑 25% 底 + 白图标」，在一张全白的 PCL 界面里像另一套设计。
    val p = LocalPclPalette.current
    FloatingBall(
        modifier = modifier,
        position = position,
        onPositionChanged = onPositionChanged,
        onClick = onClick,
        color = p.color3,
        contentColor = p.color8,
        shape = RoundedCornerShape(3.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(all = 2.dp)
                .size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(opened) { state ->
                Icon(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(
                        if (state) {
                            R.drawable.ic_menu_open
                        } else {
                            R.drawable.ic_menu
                        }
                    ),
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
fun EditorMenu(
    state: MenuState,
    closeScreen: () -> Unit,
    layers: List<ObservableControlLayer>,
    onReorder: (from: Int, to: Int) -> Unit,
    selectedLayer: ObservableControlLayer?,
    onLayerSelected: (ObservableControlLayer?) -> Unit,
    createLayer: () -> Unit,
    onAttribute: (ObservableControlLayer) -> Unit,
    onHideSwitch: (ObservableControlLayer) -> Unit,
    addNewButton: () -> Unit,
    addNewText: () -> Unit,
    addNewJoystick: () -> Unit,
    openStyleList: () -> Unit,
    openJoystickStyleList: () -> Unit,
    isLayerFocus: Boolean,
    onLayerFocusChanged: (Boolean) -> Unit,
    isPreviewMode: Boolean,
    onPreviewChanged: (Boolean) -> Unit,
    previewScenario: PreviewScenario,
    onPreviewScenarioChanged: (PreviewScenario) -> Unit,
    previewHideLayerWhen: HideLayerWhen,
    onPreviewHideLayerChanged: (HideLayerWhen) -> Unit,
    onSave: () -> Unit,
    saveAndExit: () -> Unit,
    onExit: () -> Unit
) {
    PclEditorMenuPanels(
        visible = state == MenuState.SHOW,
        onClose = closeScreen,
        leftTitle = stringResource(R.string.control_editor_menu_title),
        leftContent = {
            EditorMenuContent(
                modifier = Modifier.weight(1f),
                closeScreen = closeScreen,
                addNewButton = addNewButton,
                addNewText = addNewText,
                addNewJoystick = addNewJoystick,
                openStyleList = openStyleList,
                openJoystickStyleList = openJoystickStyleList,
                isPreviewMode = isPreviewMode,
                onPreviewChanged = onPreviewChanged,
                previewScenario = previewScenario,
                onPreviewScenarioChanged = onPreviewScenarioChanged,
                previewHideLayerWhen = previewHideLayerWhen,
                onPreviewHideLayerChanged = onPreviewHideLayerChanged,
                onSave = onSave,
                saveAndExit = saveAndExit,
                onExit = onExit
            )
        },
        rightTitle = stringResource(R.string.control_editor_layers_title),
        rightTitleAccessory = {
            //切换控件层聚焦
            PclPainterIconButton(
                modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                painter = painterResource(
                    if (isLayerFocus) {
                        R.drawable.ic_center_focus_strong_filled
                    } else {
                        R.drawable.ic_center_focus_strong_outlined
                    }
                ),
                enabled = isPreviewMode.not() && selectedLayer != null,
                onClick = {
                    onLayerFocusChanged(isLayerFocus.not())
                }
            )
        },
        rightContent = {
            ControlLayerMenu(
                layers = layers,
                onReorder = onReorder,
                selectedLayer = selectedLayer,
                onLayerSelected = onLayerSelected,
                createLayer = createLayer,
                onAttribute = onAttribute,
                onHideSwitch = onHideSwitch,
                enabled = isPreviewMode.not()
            )
        }
    )
}

@Composable
private fun EditorMenuContent(
    closeScreen: () -> Unit,
    addNewButton: () -> Unit,
    addNewText: () -> Unit,
    addNewJoystick: () -> Unit,
    openStyleList: () -> Unit,
    openJoystickStyleList: () -> Unit,
    isPreviewMode: Boolean,
    onPreviewChanged: (Boolean) -> Unit,
    previewScenario: PreviewScenario,
    onPreviewScenarioChanged: (PreviewScenario) -> Unit,
    previewHideLayerWhen: HideLayerWhen,
    onPreviewHideLayerChanged: (HideLayerWhen) -> Unit,
    onSave: () -> Unit,
    saveAndExit: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `stringResource` 是 @Composable，塞不进 `labelOf` 这种普通 lambda，先在组合里把标签算好
    val scenarioLabels = PreviewScenario.entries.associateWith { stringResource(it.textRes) }
    val snapModeLabels = SnapMode.entries.associateWith { mode ->
        stringResource(
            when (mode) {
                SnapMode.FullScreen -> R.string.control_editor_menu_widget_snap_mode_fullscreen
                SnapMode.Local -> R.string.control_editor_menu_widget_snap_mode_local
            }
        )
    }

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier
            .guideLazyList(listState) { key ->
                when (key) {
                    GuideKeys.Editor.Step.CreateLayer -> "add_button"
                    GuideKeys.Editor.Step.AddStyles -> "joystick_style_list"
                    GuideKeys.Editor.Step.Preview -> "using_gamepad"
                    GuideKeys.Editor.Step.Save -> "save_and_exit"
                    else -> null
                }
            }
            .lazyScrollWithBar(listState),
        state = listState,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //添加按钮
        item(key = "add_button") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.AddButtons,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_new_widget_button),
                enabled = isPreviewMode.not(),
                onClick = addNewButton,
            )
        }

        //添加文本框
        item(key = "add_textbox") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.AddButtons,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_new_widget_text),
                enabled = isPreviewMode.not(),
                onClick = addNewText,
            )
        }

        //添加摇杆
        item(key = "add_joystick") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.AddButtons,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_new_widget_joystick),
                enabled = isPreviewMode.not(),
                onClick = addNewJoystick,
            )
        }

        //控件外观列表
        item(key = "control_appearance_list") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.AddStyles,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_edit_style_config),
                enabled = isPreviewMode.not(),
                onClick = {
                    openStyleList()
                    closeScreen()
                },
            )
        }

        //摇杆样式列表
        item(key = "joystick_style_list") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.AddStyles,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_edit_joystick_style_list),
                enabled = isPreviewMode.not(),
                onClick = {
                    openJoystickStyleList()
                    closeScreen()
                },
            )
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        //预览控制布局
        item(key = "preview_control_layout") {
            PclMenuCheckRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Preview,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_preview_mode),
                checked = isPreviewMode,
                onCheckedChange = { onPreviewChanged(it) },
            )
        }

        //预览场景
        item(key = "preview_scenario") {
            PclMenuComboRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Preview,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                label = stringResource(R.string.control_editor_menu_preview_mode_scenario),
                items = PreviewScenario.entries,
                selected = previewScenario,
                labelOf = { scenarioLabels.getValue(it) },
                enabled = isPreviewMode,
                onSelect = onPreviewScenarioChanged,
            )
        }

        //正在使用实体鼠标
        item(key = "using_physical_mouse") {
            PclMenuCheckRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Preview,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_preview_is_mouse),
                checked = previewHideLayerWhen == HideLayerWhen.WhenMouse,
                enabled = isPreviewMode,
                onCheckedChange = { value ->
                    onPreviewHideLayerChanged(
                        if (value) HideLayerWhen.WhenMouse
                        else HideLayerWhen.None
                    )
                },
            )
        }

        //正在使用手柄
        item(key = "using_gamepad") {
            PclMenuCheckRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Preview,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_preview_is_gamepad),
                checked = previewHideLayerWhen == HideLayerWhen.WhenGamepad,
                enabled = isPreviewMode,
                onCheckedChange = { value ->
                    onPreviewHideLayerChanged(
                        if (value) HideLayerWhen.WhenGamepad
                        else HideLayerWhen.None
                    )
                },
            )
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        //控件吸附
        item {
            PclMenuCheckRow(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_widget_snap),
                checked = AllSettings.editorEnableWidgetSnap.state,
                onCheckedChange = { AllSettings.editorEnableWidgetSnap.save(it) },
            )
        }

        //所有控制层范围吸附
        item {
            PclMenuCheckRow(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_widget_snap_all_layers),
                checked = AllSettings.editorSnapInAllLayers.state,
                onCheckedChange = { AllSettings.editorSnapInAllLayers.save(it) },
            )
        }

        //控件吸附模式
        item {
            PclMenuComboRow(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.control_editor_menu_widget_snap_mode),
                items = SnapMode.entries,
                selected = AllSettings.editorWidgetSnapMode.state,
                labelOf = { snapModeLabels.getValue(it) },
                onSelect = { AllSettings.editorWidgetSnapMode.save(it) },
            )
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        //保存
        item(key = "save") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Save,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.generic_save),
                onClick = onSave,
            )
        }

        //保存并退出
        item(key = "save_and_exit") {
            PclMenuRow(
                modifier = Modifier
                    .guideNode(
                        key = GuideKeys.Editor.Step.Save,
                        holeRadius = 16.dp
                    )
                    .fillMaxWidth(),
                text = stringResource(R.string.control_editor_menu_save_and_exit),
                onClick = saveAndExit,
            )
        }

        //直接退出
        item(key = "exit_directly") {
            PclMenuRow(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.control_editor_exit_confirm),
                onClick = onExit,
            )
        }
    }
}

@Composable
private fun ColumnScope.ControlLayerMenu(
    layers: List<ObservableControlLayer>,
    onReorder: (from: Int, to: Int) -> Unit,
    selectedLayer: ObservableControlLayer?,
    onLayerSelected: (ObservableControlLayer?) -> Unit,
    createLayer: () -> Unit,
    onAttribute: (ObservableControlLayer) -> Unit,
    onHideSwitch: (ObservableControlLayer) -> Unit,
    enabled: Boolean = true
) {
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            onReorder(from.index, to.index)
        }
    )

    LaunchedEffect(Unit) {
        runCatching {
            val index = layers.indexOfFirst { it == selectedLayer }
            if (index >= 0 && index < layers.size) {
                lazyListState.animateScrollToItem(index)
            }
        }
    }

    //检查列表新增情况，自动滚动到顶部
    var previousSize by remember { mutableIntStateOf(0) }
    val currentSize = layers.size
    LaunchedEffect(currentSize) {
        if (currentSize != previousSize) {
            if (currentSize - previousSize > 0) {
                runCatching {
                    lazyListState.animateScrollToItem(0)
                }
            }
            previousSize = currentSize
        }
    }

    LazyColumn(
        modifier = Modifier
            .guideNode(GuideKeys.Editor.Step.LayerList)
            .weight(1f)
            .lazyScrollWithBar(lazyListState),
        state = lazyListState,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(layers, { it.uuid }) { layer ->
            ReorderableItem(
                state = reorderableLazyListState,
                key = layer.uuid,
                enabled = enabled,
            ) {
                ControlLayerItem(
                    modifier = Modifier.fillMaxWidth(),
                    layer = layer,
                    dragButtonModifier = Modifier.draggableHandle(),
                    selected = selectedLayer == layer,
                    onSelected = {
                        onLayerSelected(layer)
                    },
                    onUnSelected = {
                        onLayerSelected(null)
                    },
                    onAttribute = {
                        onAttribute(layer)
                    },
                    onHideSwitch = {
                        onHideSwitch(layer)
                    },
                    enabled = enabled
                )
            }
        }
    }
    //「新建」是这一栏唯一的主动作，用 PCL 的 MyButton（Highlight 配色：深蓝字 + 半透明白底）
    PclButton(
        modifier = Modifier
            .guideNode(
                key = GuideKeys.Editor.Step.CreateLayer,
                holeRadius = 50.dp
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth(),
        text = stringResource(R.string.control_editor_layers_create),
        colorState = PclColorState.Highlight,
        onClick = createLayer,
    )
}

/**
 * 一行控制层 —— 版式已迁到 `PclEditorChrome` 的 [PclLayerRow]（PCL 列表项写法），
 * 这里只负责把 `ObservableControlLayer` 的数据与回调接上去。
 */
@Composable
private fun ControlLayerItem(
    modifier: Modifier = Modifier,
    layer: ObservableControlLayer,
    dragButtonModifier: Modifier,
    selected: Boolean,
    onSelected: () -> Unit,
    onUnSelected: () -> Unit,
    onAttribute: () -> Unit,
    onHideSwitch: () -> Unit,
    enabled: Boolean = true
) {
    PclLayerRow(
        modifier = modifier,
        name = layer.name,
        selected = selected,
        hidden = layer.editorHide,
        enabled = enabled,
        dragHandleModifier = dragButtonModifier,
        onToggleHide = onHideSwitch,
        onAttribute = onAttribute,
        onClick = {
            if (selected) onUnSelected() else onSelected()
        },
    )
}
