/*
 * KCL Mobile — PCL2 UI port
 *
 * 整套界面的色板来源。
 *
 * 这里原本是 PCL2 的「个性化 → 基础 → 主题」：五个内置主题 + 自定义色相/饱和度滑杆 +
 * 四个隐藏主题，用户可切换并存进 MMKV。该功能已按要求整块移除，
 * 现在色板固定为 PCL2 的默认主题（龙猫蓝，色相 210 / 饱和度 85），
 * 不再有任何用户可改的入口，也不读写 MMKV。
 *
 * 保留这个对象是因为 `PclShell` 的 `palette` 默认参数、`UnpackScreen` 的配色都从这里取；
 * 全工程几十处 `pclPalette` 读的仍是同一块 [palette]。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import com.kcl.pcl.ui.pcl2.theme.PclPalette
import com.kcl.pcl.ui.pcl2.theme.PclPalettes

object PclThemeState {

    /**
     * 当前色板；外壳直接用它。
     *
     * `PclMainScreen(palette = PclThemeState.palette)` 的默认参数会在**每次重组时**被求值，
     * 而生成一次色板要跑十几遍 HSL→RGB 换算、分配两个渐变 List 和一整个 `PclPalette`。
     * 换版块 / 进二级页 / 返回都会让外壳重组，所以这里必须是一个已算好的常量
     * （[PclPalettes.blue] 本身就是 `val`，只算一次，引用也稳定）。
     */
    val palette: PclPalette = PclPalettes.blue
}
