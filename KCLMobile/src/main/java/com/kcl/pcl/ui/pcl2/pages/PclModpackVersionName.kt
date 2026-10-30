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

import com.kcl.pcl.game.path.getVersionsHome
import com.kcl.pcl.utils.logging.Logger
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "PclModpackVersionName"

/** 版本名里不能用作路径的字符（`/` 在 Android 上是硬禁，其余为照顾跨平台导入的整合包）。 */
private val ILLEGAL_SEGMENT_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

/** 文件名长度上限。整合包清单里的名字可以任意长，超了 ext4/F2FS 会直接 ENAMETOOLONG。 */
private const val MAX_NAME_LENGTH = 100

/**
 * 把整合包清单里的名字收拾成一个能当目录名用的版本名。
 */
internal fun pclSanitizeVersionName(raw: String): String {
    val cleaned = raw.replace(ILLEGAL_SEGMENT_CHARS, "_").trim().trimEnd('.', ' ')
    if (cleaned.isBlank()) return "整合包"
    return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH).trimEnd() else cleaned
}

/**
 * 给整合包导入挑一个**当前没被占用**的版本名。
 *
 * 【为什么必须有这一步】`GameInstaller.createPathConfig()` 一旦发现
 * `versions/<名字>/<名字>.json` 已经存在，就抛 `GameAlreadyInstalledException` 把整个
 * 任务流掐掉。这条规则在「下载版本」那条链上是对的 —— 用户点的是一个已经装过的版本，
 * 提示一句就行。但整合包导入是另一回事：
 *
 *   · 版本名来自整合包清单，**界面上没有任何地方能让用户改**；
 *   · 上一次导入失败时留在 `versions/` 里的半成品（安装器只在 `cancelInstall()` 里清理目标
 *     目录，任务流因异常结束时是**不清理**的）会把这个名字永久占住。
 *
 * 两者叠加的结果就是「导一次失败，之后再也导不进来」，界面上只看到一句
 * `GameAlreadyInstalledException`。这里改为自动让开已占用的名字：`名字 (2)`、`名字 (3)`…
 * 保证导入一定能往下走。PCL 在导入整合包时会先弹框让用户输版本名，这里不问，
 * 用后缀让开更省事，完成提示里也会报出最终用的名字。
 */
internal fun pclUniqueVersionName(raw: String): String {
    val base = pclSanitizeVersionName(raw)
    val versionsHome = File(getVersionsHome())
    if (pclVersionNameIsFree(versionsHome, base)) return base
    for (index in 2..999) {
        val candidate = "$base ($index)"
        if (pclVersionNameIsFree(versionsHome, candidate)) return candidate
    }
    return "$base (${System.currentTimeMillis()})"
}

/**
 * 这个名字还能不能用。
 *
 * 除了版本目录本身，也顺便避开 `versions/<名字>.json` 这种散落的 json —— 那是
 * KCL 在「版本改名」中途被打断时会留下的形状，同样会被版本列表认成一个版本。
 */
private fun pclVersionNameIsFree(versionsHome: File, name: String): Boolean =
    !File(versionsHome, name).exists() && !File(versionsHome, "$name.json").exists()

/**
 * 导入失败 / 被用户取消时，把这次导入没能装完的版本目录清掉。
 *
 * 调用方传进来的名字是 [pclUniqueVersionName] 当场算出来的 —— 也就是说导入开工前它
 * 一定不存在，这个目录只可能是本次导入建的，删掉不会碰到用户已有的东西。
 * 与 `GameInstaller.cancelInstall(clearTarget = true)` 的约定保持一致：装失败就别留尸体。
 *
 * @param versionName 本次导入实际使用的版本名；为 null（还没走到起名那一步）时什么也不做
 * @return 真的删掉了东西时返回 true，调用方据此决定要不要刷新版本列表
 */
internal fun pclDiscardIncompleteVersion(versionName: String?): Boolean {
    if (versionName.isNullOrBlank()) return false
    val versionsHome = File(getVersionsHome())
    val dir = File(versionsHome, versionName)
    // 双保险：拼出来的路径必须真的落在 versions/ 下面，别因为名字里带分隔符删到别处
    if (dir.path != versionsHome.path + File.separator + versionName) {
        Logger.warning(TAG, "Refused to clean up suspicious version path: ${dir.absolutePath}")
        return false
    }
    if (!dir.exists()) return false
    return try {
        FileUtils.deleteDirectory(dir)
        Logger.info(TAG, "Removed incomplete version: ${dir.absolutePath}")
        true
    } catch (e: Exception) {
        Logger.warning(TAG, "Failed to remove incomplete version: ${dir.absolutePath}", e)
        false
    }
}
