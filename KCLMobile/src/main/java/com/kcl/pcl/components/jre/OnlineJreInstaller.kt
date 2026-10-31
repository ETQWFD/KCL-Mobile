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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl.components.jre

import com.kcl.pcl.ZLApplication
import com.kcl.pcl.game.download.engine.Fetcher
import com.kcl.pcl.game.multirt.RuntimesManager
import com.kcl.pcl.path.PathManager
import com.kcl.pcl.utils.device.Architecture
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "OnlineJreInstaller"

/**
 * 体积优化后，APK 只内置 [Jre.JRE_8]。jre-17 / jre-21 / jre-25 不再打包进 APK，
 * 需要时由这里从 GitHub Release 在线补装：
 *
 * 1. 下载 `<base>/jre-<ver>-universal.tar.xz` 与 `<base>/jre-<ver>-bin-<abi>.tar.xz`；
 * 2. 下载完成后做 SHA-256 校验（同目录下的 `.sha256` sidecar，由发布方随包提供）。
 *    校验不过就删除重来一次，仍不过则抛 [IOException]（原因含「校验失败」）。
 *    sidecar 缺失（Release 未提供）时只记录实得哈希、放行，不阻断安装；
 * 3. 校验通过后走与内置包完全一致的解压链路：[RuntimesManager.installRuntimeBinPack]
 *    + [RuntimesManager.postPrepare]。
 *
 * 「版本标记」统一用 [ONLINE_BINPACK_VERSION]，与 [RuntimesManager.loadInternalRuntimeVersion]
 * 的已装版本比对口径一致。
 */
object OnlineJreInstaller {

    /** 在线补装包的 Release 地址（与内置包同一版本号）。 */
    private const val RELEASE_BASE =
        "https://github.com/ETQWFD/KCL-Mobile/releases/download/v1.3.1-kcl.5"

    /**
     * 在线下载包的版本标记。必须与发布方上传的 tar.xz 所对应的 Release 一致，
     * 否则下次自检会反复重下。
     */
    const val ONLINE_BINPACK_VERSION = "1.3.1-kcl.5"

    /** 失败原因里统一给用户的后续处理提示。 */
    const val HINT_RETRY_ONLINE = "请联网重试或到「设置 → Java 运行库」导入 .xz"

    /** jre-17 / jre-21 / jre-25 这类未内置、需要在线补装的运行库。 */
    fun isOnlineOnly(jre: Jre): Boolean = jre != Jre.JRE_8

    /**
     * 下载并安装一个未内置的 JRE。抛异常即失败，message 面向用户可读。
     */
    suspend fun downloadAndInstall(jre: Jre) = withContext(Dispatchers.IO) {
        val ver = jre.jrePath.substringAfterLast('/') // "runtimes/jre-17" -> "jre-17"
        val abi = "bin-" + Architecture.archAsString(ZLApplication.DEVICE_ARCHITECTURE) + ".tar.xz"

        val universalUrl = "$RELEASE_BASE/$ver-universal.tar.xz"
        val binUrl = "$RELEASE_BASE/$ver-$abi"

        val workDir = File(PathManager.DIR_CACHE, "online_jre").apply { mkdirs() }
        val universalFile = File(workDir, "$ver-universal.tar.xz")
        val binFile = File(workDir, "$ver-$abi")

        try {
            Logger.info(TAG, "Downloading $ver: universal=$universalUrl, bins=$binUrl")
            downloadVerified(universalUrl, universalFile)
            downloadVerified(binUrl, binFile)

            Logger.info(TAG, "Installing downloaded runtime ${jre.jreName}")
            FileInputStream(universalFile).use { uIn ->
                FileInputStream(binFile).use { bIn ->
                    RuntimesManager.installRuntimeBinPack(
                        universalFileInputStream = uIn,
                        platformBinsInputStream = bIn,
                        name = jre.jreName,
                        binPackVersion = ONLINE_BINPACK_VERSION
                    )
                }
            }
            RuntimesManager.postPrepare(jre.jreName)
        } finally {
            universalFile.delete()
            binFile.delete()
        }
    }

    /** 下载到 [dest]，并用同目录 `.sha256` sidecar 校验；不匹配删除重试一次，仍失败抛错。 */
    private suspend fun downloadVerified(url: String, dest: File) {
        var attempt = 0
        while (true) {
            attempt++
            dest.delete()
            Fetcher.downloadFile(listOf(url), dest)

            val expected = expectedSha256(url)
            if (expected == null) {
                Logger.info(TAG, "No .sha256 sidecar for $url (actual=${sha256(dest)}), skip strict check")
                return@downloadVerified
            }
            val actual = sha256(dest)
            if (actual.equals(expected, ignoreCase = true)) {
                Logger.info(TAG, "SHA-256 verified: $url")
                return@downloadVerified
            }
            Logger.warning(TAG, "SHA-256 mismatch for $url: expected=$expected, actual=$actual (attempt $attempt)")
            dest.delete()
            if (attempt >= 2) {
                throw IOException(
                    "校验失败：$url 下载内容 SHA-256 不匹配（期望 $expected，实得 $actual）。$HINT_RETRY_ONLINE"
                )
            }
        }
    }

    /** 拉取 `<url>.sha256` 并解析出期望哈希；拿不到（404 / 格式不符）返回 null。 */
    private suspend fun expectedSha256(assetUrl: String): String? {
        val sidecar = File(PathManager.DIR_CACHE, "sidecar-${System.nanoTime()}.sha256")
        return runCatching {
            Fetcher.downloadFile(listOf("$assetUrl.sha256"), sidecar, retry = 1)
            sidecar.readText().trim().split(Regex("\\s+"))
                .firstOrNull { it.matches(Regex("[a-fA-F0-9]{64}")) }
        }.onFailure {
            Logger.info(TAG, "No .sha256 sidecar available for $assetUrl (${it.message})")
        }.getOrNull().also { sidecar.delete() }
    }

    /** 计算文件的 SHA-256（小写 hex）。 */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
