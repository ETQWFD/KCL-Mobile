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

package com.kcl.pcl.game.download.jvm_server

import java.io.RandomAccessFile

/** 报错信息里附带 JVM 日志的行数上限 */
private const val LOG_TAIL_LINES = 25
/** 报错信息里附带 JVM 日志的字符数上限 */
private const val LOG_TAIL_CHARS = 1800
/** 读取 JVM 日志末尾时最多回读的字节数 */
private const val LOG_READ_BYTES = 64L * 1024L

/**
 * 安装用的 JVM 以非 0 退出码结束时抛出。
 *
 * 报错信息里会带上安装 JVM 的运行日志末尾：`:jvm` 进程里的异常（运行时缺失、类加载失败、
 * Java 层报错等）只会被写进那份日志，外面只报一个「异常代码 1」是看不出原因的。
 * 注意 JRE 会按 8 → 17 → 21 依次重试，所以这里取到的是最后一次（最高版本）尝试的日志。
 */
class JvmCrashException(val code: Int) : RuntimeException(buildMessage(code))

private fun buildMessage(code: Int): String {
    val title = "During installation, the JVM exited with exception code $code."
    val tail = readProcessLogTail() ?: return title
    return "$title\n\n——— JVM 日志末尾（$LOG_TAIL_LINES 行）———\n$tail"
}

/**
 * 读取安装 JVM 运行日志（`latest_process.log`）的末尾若干行
 */
private fun readProcessLogTail(): String? = runCatching {
    val file = LATEST_PROCESS_LOG_FILE
    if (!file.isFile) return@runCatching null
    val length = file.length()
    if (length <= 0L) return@runCatching null

    val start = (length - LOG_READ_BYTES).coerceAtLeast(0L)
    val bytes = ByteArray((length - start).toInt())
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(start)
        raf.readFully(bytes)
    }
    //从字节中间截断可能留下半个多字节字符，替换成乱码字符即可（只取末尾，不影响可读性）
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) return@runCatching null

    val tail = text.lineSequence()
        .map(String::trimEnd)
        .filter(String::isNotBlank)
        .toList()
        .takeLast(LOG_TAIL_LINES)
        .joinToString("\n")

    when {
        tail.isBlank() -> null
        tail.length > LOG_TAIL_CHARS -> tail.takeLast(LOG_TAIL_CHARS)
        else -> tail
    }
}.getOrNull()
