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

package com.kcl.pcl.game.version.export

import android.content.Context
import com.kcl.pcl.coroutine.TitledTask
import com.kcl.pcl.game.version.installed.Version
import java.io.File

abstract class AbstractExporter(
    val type: PackType
) {
    /**
     * 构建所需的导出任务
     */
    abstract fun MutableList<TitledTask>.buildTasks(
        context: Context,
        version: Version,
        info: ExportInfo,
        tempPath: File
    )

    /**
     * 整合包文件后缀
     */
    abstract val fileSuffix: String

    protected fun generateTargetRoot(
        file: File,
        rootPath: String,
        targetPath: String
    ): File {
        return File(targetPath, relativePath(file, rootPath))
    }

    /**
     * 获取一个文件的相对路径
     */
    protected fun relativePath(
        file: File,
        rootPath: String
    ): String {
        return file.absolutePath
            .removePrefix(rootPath)
            .removePrefix("/")
    }

    /**
     * 把 [this] 复制到 [target]，并在复制前把 [target] 的父目录建出来。
     *
     * Kotlin 标准库的 [File.copyTo] 底层就是 `FileOutputStream(target)`，
     * **不会**自动 `mkdirs`。整合包里 `relativePath` 形如
     * `mods/foo.jar`、`config/jei/config.json`，目标父目录在 temp 里并不存在，
     * 直接 `copyTo` 会抛 `FileNotFoundException: ... (No such file or directory)`，
     * 表现就是「导出整合包失败」。这里统一兜底。
     */
    protected fun File.copyToEnsured(
        target: File,
        overwrite: Boolean = true
    ) {
        target.parentFile?.mkdirs()
        this.copyTo(target, overwrite = overwrite)
    }
}