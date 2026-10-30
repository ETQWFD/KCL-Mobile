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

package com.kcl.pcl.ui.pcl2.pages

import com.kcl.pcl.game.version.saves.unpackSaveZip
import com.kcl.pcl.ui.pcl2.PclHintHost
import com.kcl.pcl.ui.pcl2.components.PclHintTheme
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PclSaveInstall"

/**
 * 存档下载完成后要做的「安装」动作：**解压 + 删掉源 zip**。
 *
 * 背景：`downloadSingleForVersions` 的默认行为只是把文件原样复制进版本目录 —— 对 Mod /
 * 资源包 / 光影包来说这就够了（游戏自己会读），但**存档不行**：游戏只认
 * `saves/<存档名>/level.dat` 这种目录结构，一个躺在 `saves/xxx.zip` 的压缩包在游戏里
 * 根本看不见，玩家会以为下载失败。
 *
 * 所以存档这一类（`PlatformClasses.SAVES`）在文件复制完之后再走一遍这里：
 * `unpackSaveZip` 会找到 zip 里 `level.dat` 所在的层级（兼容「套一层目录」的打包方式），
 * 把它解到 `saves/<zip 文件名>/`，最后删除 zip —— 与 KCL 原生 `DownloadSavesScreen`、
 * 以及 PCL 的存档安装行为一致。
 *
 * 解压失败（不是合法存档包、磁盘满、压缩算法不支持……）时**保留 zip 并提示**，
 * 不让整个下载任务被判失败：文件其实已经下好了，留下压缩包玩家至少还能自己处理。
 */
internal suspend fun pclInstallDownloadedSave(zip: File, savesDir: File) {
    if (!zip.isFile) return
    if (!zip.name.endsWith(".zip", ignoreCase = true)) {
        // 个别站点会给 .rar / .7z 之类的包：解不了就别动它，但必须告诉玩家文件在哪，
        // 否则「下载完了但游戏里看不到存档」会变成无解的困惑。
        pclSaveHint("已下载存档包 ${zip.name}，但它不是 zip，需要手动解压到 saves/", PclHintTheme.Yellow)
        return
    }
    val saveName = zip.nameWithoutExtension
    runCatching { unpackSaveZip(zipFile = zip, targetPath = savesDir) }
        .onSuccess {
            Logger.info(TAG, "Save unpacked: ${zip.absolutePath} -> $saveName")
            pclSaveHint("存档已安装：saves/$saveName", PclHintTheme.Green)
        }
        .onFailure { e ->
            Logger.warning(TAG, "Failed to unpack save zip: ${zip.absolutePath}", e)
            pclSaveHint(
                "存档解压失败，已保留下载的 ${zip.name}：${e.message ?: e.javaClass.simpleName}",
                PclHintTheme.Yellow,
            )
        }
}

private suspend fun pclSaveHint(text: String, theme: PclHintTheme) {
    withContext(Dispatchers.Main) { PclHintHost.push(text, theme) }
}
