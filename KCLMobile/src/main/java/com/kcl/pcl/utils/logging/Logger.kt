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

package com.kcl.pcl.utils.logging

import android.content.Context
import android.util.Log
import com.kcl.pcl.BuildKeys
import com.kcl.pcl.path.PathManager
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.utils.file.zipDirectory
import com.kcl.pcl.utils.printLauncherInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * [Modified from HMCL](https://github.com/HMCL-dev/HMCL/blob/57018bef47417108b75e2298ab61f89a7586b1b9/HMCLCore/src/main/java/org/jackhuang/hmcl/util/logging/Logger.java)
 */
object Logger : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.Default + Job()

    private lateinit var PACKAGE_PREFIX: String

    /**
     * 当前进程的标识
     */
    private lateinit var PROCESS_TAG: String
    private val isInitialized = AtomicBoolean(false)
    private val channel = Channel<LogMessage>(Channel.UNLIMITED)

    private val logRetentionDays: Int
        get() = AllSettings.launcherLogRetentionDays.getValue()

    private var currentLogFile: File? = null
    private var logWriter: PrintWriter? = null
    private var inMemoryLogs: ByteArrayOutputStream? = null

    /**
     * 写文件与清空操作的互斥锁，避免「清除日志」与正在写入的日志行交错。
     */
    private val logLock = Any()

    /**
     * 单个日志文件的体积上限：超过后滚动一次（旧文件改名为 .1 备份，再开新文件追加），
     * 防止长期不清理时日志无限增长。
     */
    private val maxLogFileLength: Long get() = 5L * 1024 * 1024

    /**
     * 初始化日志
     */
    fun initialize(context: Context) {
        PACKAGE_PREFIX = "${context.packageName}."
        PROCESS_TAG = getProcessTag(context)

        if (!isInitialized.compareAndSet(false, true)) return

        launch(Dispatchers.IO) {
            setupLogWriter()

            //由于安卓不存在“退出”这种设置
            //所以清理旧的日志的工作需要放到初始化阶段
            deleteOldLogs()

            printLauncherInfo()

            processEvents()
        }
    }

    /**
     * 获取当前进程标识
     */
    private fun getProcessTag(context: Context): String {
        val processName = context.applicationInfo.processName
        val separatorIndex = processName.lastIndexOf(':')
        return if (separatorIndex < 0) "main" else processName.substring(separatorIndex + 1)
    }

    private suspend fun printLauncherInfo() {
        logWriter?.apply {
            withContext(Dispatchers.IO) {
                println("================ ${BuildKeys.LAUNCHER_IDENTIFIER} Log ================")
                printLauncherInfo { println(it) }
                println("====================================================")
                flush()
            }
        }
    }

    private suspend fun setupLogWriter() = withContext(Dispatchers.IO) {
        try {
            currentLogFile = resolveLogFile()
            openLogWriter()
        } catch (e: IOException) {
            val logMessage = LogMessage(
                System.currentTimeMillis(),
                "Logger.setupLogWriter",
                Level.WARNING,
                "Failed to create log file", e
            )
            channel.send(logMessage)
            inMemoryLogs = ByteArrayOutputStream(1024 * 1024) // 1MB buffer
            logWriter = PrintWriter(inMemoryLogs!!)
        }
    }

    /**
     * 持久化日志文件：每个进程一个固定文件（log_<进程名>.log），
     * 跨启动以追加方式写入同一文件；仅在用户手动「清除日志」或超过体积上限滚动时重置内容。
     */
    private fun resolveLogFile(): File {
        val file = File(PathManager.DIR_LAUNCHER_LOGS, "log_$PROCESS_TAG.log")
        if (file.exists() && file.length() > maxLogFileLength) {
            //滚动：上一轮日志保留为 .1 备份（已有的旧 .1 直接覆盖），随后新建空文件继续追加
            val backup = File(PathManager.DIR_LAUNCHER_LOGS, "log_$PROCESS_TAG.log.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
        if (!file.exists() && !file.createNewFile()) {
            throw IOException("Failed to create log file: ${file.absolutePath}")
        }
        return file
    }

    /**
     * 以追加模式打开当前日志文件。
     */
    private fun openLogWriter() {
        val target = currentLogFile ?: return
        logWriter?.close()
        logWriter = PrintWriter(OutputStreamWriter(FileOutputStream(target, true), StandardCharsets.UTF_8))
    }

    private suspend fun processEvents() = withContext(Dispatchers.IO) {
        for (message in channel) {
            handleLogMessage(message)
        }
    }

    private fun handleLogMessage(message: LogMessage) {
        val formatted = formatMessage(message)

        //输出到 Logcat
        printToLogcat(message.level, formatted)

        synchronized(logLock) {
            logWriter?.apply {
                println(formatted)
                message.throwable?.also { th ->
                    th.printStackTrace(this@apply)
                    printToLogcat(message.level, th)
                }
                flush()
            }
        }
    }

    private fun printToLogcat(level: Level, message: String) {
        when (level) {
            Level.ERROR -> Log.e("AppLog", message)
            Level.WARNING -> Log.w("AppLog", message)
            Level.INFO -> Log.i("AppLog", message)
            Level.DEBUG -> Log.d("AppLog", message)
            Level.TRACE -> Log.v("AppLog", message)
        }
    }

    private fun printToLogcat(level: Level, throwable: Throwable) {
        val thMessage = Log.getStackTraceString(throwable)

        when (level) {
            Level.ERROR -> Log.e("AppLog", thMessage)
            Level.WARNING -> Log.w("AppLog", thMessage)
            Level.INFO -> Log.i("AppLog", thMessage)
            Level.DEBUG -> Log.d("AppLog", thMessage)
            Level.TRACE -> Log.v("AppLog", thMessage)
        }
    }

    private fun formatMessage(message: LogMessage): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(message.time)
        return buildString {
            append("[$time] [")
            append(message.tag)
            append("/")
            append(message.level.name)
            append("] ")
            append(message.message)
        }
    }

    private suspend fun deleteOldLogs() = withContext(Dispatchers.IO) {
        PathManager.DIR_LAUNCHER_LOGS.listFiles()?.let { files ->
            val cutoff = System.currentTimeMillis() - logRetentionDays * 86400000L
            //清理遗留的 0kb 空日志
            val emptyLogCutoff = System.currentTimeMillis() - 60 * 1000L
            files.filter {
                it.lastModified() < cutoff ||
                    (it != currentLogFile && it.length() == 0L && it.lastModified() < emptyLogCutoff)
            }.forEach {
                FileUtils.deleteQuietly(it)
            }
        }
    }

    /**
     * 清空当前日志文件内容（truncate），随后继续以追加方式写入同一文件。
     * 供「设置→日志→清除日志」调用；清空后日志读取方读到空文件即可，不抛异常。
     */
    suspend fun clearLog() = withContext(Dispatchers.IO) {
        //先在锁内完成文件操作；send 是挂起点，不能放在 synchronized 临界区内，
        //故先把异常记到局部变量，退出临界区后再投递到 channel。
        var failure: IOException? = null
        synchronized(logLock) {
            try {
                //先关闭旧写入流再清空，避免旧文件偏移在截断后打出空洞
                logWriter?.close()
                logWriter = null
                currentLogFile?.takeIf { it.exists() }?.writeText("")
                openLogWriter()
            } catch (e: IOException) {
                failure = e
            }
        }
        failure?.let { e ->
            channel.send(
                LogMessage(
                    System.currentTimeMillis(),
                    "Logger.clearLog",
                    Level.WARNING,
                    "Failed to clear log file", e
                )
            )
        }
    }

    fun error(tag: String, msg: String, t: Throwable? = null) =
        log(Level.ERROR, tag, msg, t)

    fun warning(tag: String, msg: String, t: Throwable? = null) =
        log(Level.WARNING, tag, msg, t)

    fun info(tag: String, msg: String, t: Throwable? = null) =
        log(Level.INFO, tag, msg, t)

    fun debug(tag: String, msg: String, t: Throwable? = null) =
        log(Level.DEBUG, tag, msg, t)

    /**
     * 输出日志
     */
    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!isInitialized.get()) return

        val logMessage = LogMessage(
            time = System.currentTimeMillis(),
            tag = tag,
            level = level,
            message = message,
            throwable = throwable
        )

        launch {
            channel.send(logMessage)
        }
    }

    /**
     * 打包所有日志文件
     */
    suspend fun pack(target: File) {
        withContext(Dispatchers.IO) {
            zipDirectory(
                sourceDir = PathManager.DIR_LAUNCHER_LOGS,
                outputZipFile = target
            )
        }
    }
}