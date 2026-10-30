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
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl.game.version.export.data

import com.kcl.pcl.R
import com.kcl.pcl.BuildKeys
import java.io.File

/**
 * 构建导出整合包用的文件树。
 *
 * 原来这套逻辑写在 `VersionExportScreen` 的 ViewModel 里（私有），
 * 抽到顶层之后，原生界面与 PCL2 界面（版本设置 → 导出）共用同一份实现。
 *
 * @param root 要打包的根目录（一般是版本的 .minecraft 目录）
 * @param blackList 这些文件名的文件不参与导出（版本自身的 json / jar）
 */
fun buildExportFileTree(
    root: File,
    blackList: List<String> = emptyList(),
    alias: (String) -> Int? = ::exportFileAlias
): List<FileSelectionData> {
    if (!root.isDirectory) return emptyList()

    data class StackNode(
        val dir: File,
        val depth: Int,
        val container: MutableList<FileSelectionData>
    )

    val result = mutableListOf<FileSelectionData>()
    val stack = ArrayDeque<StackNode>()
    stack.add(StackNode(root, 1, result))

    while (stack.isNotEmpty()) {
        val (dir, currentDepth, container) = stack.removeLast()
        val files = dir.listFiles() ?: continue

        val tempList = ArrayList<FileSelectionData>(files.size)
        for (file in files) {
            if (blackList.any { pattern -> file.name.contains(pattern) }) continue
            //只有根目录的文件才能设置别名
            val nodeAlias = if (currentDepth == 1) alias(file.name) else null

            if (file.isDirectory) {
                val childList = mutableListOf<FileSelectionData>()
                tempList.add(
                    FileSelectionData(file = file, alias = nodeAlias, child = childList)
                )
                stack.add(StackNode(dir = file, depth = currentDepth + 1, container = childList))
            } else {
                tempList.add(FileSelectionData(file = file, alias = nodeAlias, child = null))
            }
        }

        tempList.sort()
        container.addAll(tempList)
    }

    return result
}

/**
 * 获取文件/文件夹的别称（玩家看得懂的名称）
 */
fun exportFileAlias(fileName: String): Int? = when (fileName) {
    "options.txt" -> R.string.versions_export_alias_options
    "resourcepacks" -> R.string.versions_export_alias_resource_packs
    "mods" -> R.string.versions_export_alias_mods
    "saves" -> R.string.versions_export_alias_saves
    "shaderpacks" -> R.string.versions_export_alias_shaderpacks
    "config" -> R.string.versions_export_alias_config
    BuildKeys.LAUNCHER_IDENTIFIER -> R.string.versions_export_alias_launcher
    else -> null
}
