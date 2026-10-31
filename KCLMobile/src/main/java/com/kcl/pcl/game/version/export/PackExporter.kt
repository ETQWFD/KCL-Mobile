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
import android.net.Uri
import com.kcl.pcl.R
import com.kcl.pcl.context.writeLocalFile
import com.kcl.pcl.coroutine.TaskFlowExecutor
import com.kcl.pcl.coroutine.TitledTask
import com.kcl.pcl.coroutine.addTask
import com.kcl.pcl.coroutine.buildPhase
import com.kcl.pcl.game.version.export.platform.CurseForgePackExporter
import com.kcl.pcl.game.version.export.platform.MCBBSPackExporter
import com.kcl.pcl.game.version.export.platform.ModrinthPackExporter
import com.kcl.pcl.game.version.export.platform.MultiMCPackExporter
import com.kcl.pcl.game.version.installed.Version
import com.kcl.pcl.path.PathManager
import com.kcl.pcl.ui.androidText
import com.kcl.pcl.utils.file.zipDirectory
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "PackExporter"

/**
 * 整合包导出器
 * @param exportInfo 要导出的整合包的必要信息
 * @param scope 在有生命周期管理的scope中执行安装任务
 */
class PackExporter(
    val context: Context,
    val exportInfo: ExportInfo,
    private val scope: CoroutineScope,
) {
    private val taskExecutor = TaskFlowExecutor(scope)
    val taskFlow: StateFlow<List<TitledTask>> = taskExecutor.tasksFlow

    private val exporter: AbstractExporter = when (exportInfo.packType) {
        PackType.MCBBS -> MCBBSPackExporter()
        PackType.Modrinth -> ModrinthPackExporter()
        PackType.CurseForge -> CurseForgePackExporter()
        PackType.MultiMC -> MultiMCPackExporter()
    }

    /**
     * 开始导出整合包
     */
    fun startExport(
        outputUri: Uri,
        version: Version,
        isRunning: () -> Unit = {},
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (taskExecutor.isRunning()) {
            isRunning()
            return //正在运行中，拒绝导出
        }

        taskExecutor.executePhasesAsync(
            onStart = {
                val tasks = getTaskPhases(outputUri, version)
                taskExecutor.addPhases(tasks)
            },
            onComplete = {
                onFinished()
            },
            onError = { e ->
                onError(e)
            }
        )
    }

    /**
     * 给 SAF `CreateDocument` 用的默认文件名：`清洗后的名字.后缀`。
     *
     * UI 层在拉起「另存为」对话框前需要把这个名字传给系统，让用户一打开就能看到
     * 正确的文件名与扩展名（比如选 Modrinth 格式就应该是 `.mrpack`，而不是 `.zip`）。
     */
    fun defaultOutputFileName(): String {
        return "${cacheFileName(exportInfo.name)}.${exporter.fileSuffix}"
    }

    private suspend fun getTaskPhases(
        outputUri: Uri,
        version: Version
    ) = withContext(Dispatchers.IO) {
        // 兜底：UI 层按钮已经在「零选中」时禁用，但 PCL2 页或未来入口可能绕过这层，
        // 这里再挡一次，避免静默产出一个只含 manifest 的空 zip（导入方会报损坏包）。
        if (exportInfo.selectedFiles.isEmpty()) {
            throw IllegalStateException("没有选中任何要导出的文件，请先在文件选择页至少勾选一个文件。")
        }

        val exportCachePath = PathManager.DIR_CACHE_MODPACK_EXPORTER
        val tempPath = File(exportCachePath, "temp")
        // 缓存里的中间文件名必须清洗：`exportInfo.name` 是用户手填的，
        // 带 `/`、`:`、`*` 这类字符（「正式版/1.0」这种写法很常见）时，
        // `FileOutputStream` 会抛 `FileNotFoundException: ... (Not a directory)`，
        // 出口是「导出失败」，而且提示里完全看不出是名字的问题。
        val pack = File(exportCachePath, "${cacheFileName(exportInfo.name)}.${exporter.fileSuffix}")

        listOf(
            buildPhase {
                //清除上一次导出的缓存
                addTask(
                    id = "ExportModpack.Cleanup",
                    title = androidText(R.string.download_install_clear_temp),
                    icon = R.drawable.ic_auto_delete_outlined
                ) {
                    clearTempModPackDir()
                    tempPath.createDirAndLog()
                }

                with(exporter) {
                    buildTasks(
                        context = context,
                        version = version,
                        info = exportInfo,
                        tempPath = tempPath
                    )
                }

                addTask(
                    id = "ExportModpack.Pack",
                    title = androidText(R.string.versions_export_task_generate_pack),
                    icon = R.drawable.ic_build_outlined
                ) {
                    zipDirectory(
                        sourceDir = tempPath,
                        outputZipFile = pack,
                        preserveFileTime = false
                    )

                    context.writeLocalFile(
                        inputFile = pack,
                        outputUri = outputUri,
                        mimeType = "application/zip"
                    )
                }

                addTask(
                    id = "ExportModpack.Cleanup_Finished",
                    title = androidText(R.string.download_install_clear_temp),
                    icon = R.drawable.ic_auto_delete_outlined
                ) {
                    clearTempModPackDir()
                }
            }
        )
    }

    /**
     * 清理临时整合包导出目录
     */
    private suspend fun clearTempModPackDir() = withContext(Dispatchers.IO) {
        PathManager.DIR_CACHE_MODPACK_EXPORTER.takeIf { it.exists() }?.let { folder ->
            FileUtils.deleteQuietly(folder)
            Logger.info(TAG, "Temporary modpack export directory cleared.")
        }
    }

    /**
     * 取消整合包导入
     */
    fun cancel() {
        taskExecutor.cancel()
    }

    private fun File.createDirAndLog(): File {
        this.mkdirs()
        Logger.debug(TAG, "Created directory: $this")
        return this
    }
}

/**
 * 落盘用的文件名：把文件系统不认的字符换成下划线。
 *
 * 只清洗**中间产物 / 保存用的名字**；写进 `manifest.json` / `modrinth.index.json`
 * 的 `name` 字段仍然是用户原文，不受影响。
 *
 * 改 internal 是为了让 UI 层（VersionExportScreen / PclInstanceExportPage）
 * 在拉起 SAF `CreateDocument` 之前，能拼出跟最终落盘一致的默认文件名。
 */
internal fun cacheFileName(raw: String): String {
    val cleaned = raw
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
        .trim()
        .trimEnd('.')
    return cleaned.ifBlank { "modpack" }
}