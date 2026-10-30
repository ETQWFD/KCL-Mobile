/*
 * KCL Mobile — 下载源测速
 *
 * 给每个可用的「游戏内容下载源」拉一个固定的小探针文件，实测「首字节延迟」与「吞吐」，
 * 然后把最快的那个记进设置（`AllSettings.gameDownloadFastestSource`）。
 *
 * 「游戏内容下载源 = 自动」这一档会优先采用这个实测结果
 * （见 `BMCLAPI.kt:mapBMCLMirrorUrls`），而不是只按「是否中国大陆」静态判定——
 * 静态判定在网络抖动、某个源临时变慢时会把整批下载拖垮。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.game.addons.mirror

import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.setting.enums.MirrorSourceType
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SourceSpeedTest"

/** 单个源的测速超时（连接与读取各自）。 */
private const val PROBE_TIMEOUT_MS = 8_000

/**
 * 各源用来测速的探针文件。
 *
 * 用版本清单（约 200 KB）：两个源都有、路径规则与真实下载一致
 * （BMCL 把 `piston-meta.mojang.com` 整个映射到自己的根路径，见 `BMCLAPI.kt` 的替换表），
 * 体积也够跑出有意义的吞吐。
 */
private val PROBE_URLS: Map<SourceType, String> = mapOf(
    SourceType.OFFICIAL to "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
    SourceType.BMCLAPI to "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
)

/** 一个源的测速结果。 */
data class SourceSpeedResult(
    val type: SourceType,
    /** 首字节延迟（毫秒）；失败时为 -1 */
    val latencyMs: Long = -1L,
    /** 平均吞吐（字节/秒）；失败时为 0 */
    val bytesPerSec: Long = 0L,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && bytesPerSec > 0

    /** 给界面用的简述，例如「BMCL API · 1.2 MB/s · 45 ms」。 */
    fun describe(): String = if (ok) {
        buildString {
            append(type.displayName)
            append(" · ")
            append(formatSpeed(bytesPerSec))
            append(" · ")
            append(latencyMs)
            append(" ms")
        }
    } else {
        "${type.displayName} · 不可用${error?.let { "（$it）" }.orEmpty()}"
    }
}

/** 对单个源测速。 */
suspend fun measureSourceSpeed(type: SourceType): SourceSpeedResult = withContext(Dispatchers.IO) {
    val url = PROBE_URLS[type]
        ?: return@withContext SourceSpeedResult(type, error = "没有可用的探针地址")

    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KCLMobile")
        }
        val start = System.currentTimeMillis()
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            return@withContext SourceSpeedResult(type, error = "HTTP $code")
        }

        var firstByteAt = -1L
        var bytes = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (firstByteAt < 0) firstByteAt = System.currentTimeMillis()
                bytes += read
            }
        }
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        SourceSpeedResult(
            type = type,
            latencyMs = if (firstByteAt > 0) firstByteAt - start else elapsed,
            bytesPerSec = bytes * 1000L / elapsed,
        )
    } catch (e: Exception) {
        SourceSpeedResult(type, error = e.message ?: e.javaClass.simpleName)
    } finally {
        runCatching { connection?.disconnect() }
    }
}

/**
 * 给所有源并行测速，并把实测最快的源记为「自动」档的选择。
 *
 * @return 每个源的测速结果（顺序与 [SourceType.entries] 一致）
 */
suspend fun probeAndApplyFastestSource(): List<SourceSpeedResult> {
    val results = coroutineScope {
        SourceType.entries.map { type -> async { measureSourceSpeed(type) } }.awaitAll()
    }
    val best = results.filter { it.ok }.maxByOrNull { it.bytesPerSec }
    AllSettings.gameDownloadFastestSource.save(
        when (best?.type) {
            SourceType.BMCLAPI -> MirrorSourceType.MIRROR
            SourceType.OFFICIAL -> MirrorSourceType.OFFICIAL
            else -> MirrorSourceType.AUTO
        }
    )
    AllSettings.gameDownloadProbeTime.save(System.currentTimeMillis())
    Logger.info(
        TAG,
        "测速完成：" + results.joinToString("，") { it.describe() } +
            "，选用 " + (best?.type?.displayName ?: "无（全部失败，保持静态判定）"),
    )
    return results
}

/** 把字节/秒格式化成可读文本。 */
private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSec / 1024.0 / 1024.0)
    bytesPerSec >= 1024L -> "%.0f KB/s".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}
