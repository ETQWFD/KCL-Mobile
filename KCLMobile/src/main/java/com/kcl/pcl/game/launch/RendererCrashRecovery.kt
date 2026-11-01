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
package com.kcl.pcl.game.launch

import com.kcl.pcl.bridge.LoggerBridge
import com.kcl.pcl.game.renderer.Renderers
import com.kcl.pcl.game.renderer.renderers.GL4ESRenderer
import com.kcl.pcl.setting.AllSettings
import java.io.File

/**
 * v5：渲染器崩溃自动切换。游戏异常退出且日志含「着色器编译失败」特征时，
 * 把全局渲染器自动切到 Pure GL4ES（更稳的 GLES 翻译层），下次启动生效。
 * 只处理这一种明确特征，不碰其他类型崩溃。
 */
object RendererCrashRecovery {

    private val SHADER_CRASH_PATTERNS = listOf(
        "Couldn't compile fragment program",
        "could not reload shaders",
        "Invalid shaders",
        "Compile failed",
        "Failed to create window with OpenGL context"
    )

    fun maybeFallbackRenderer(logFile: File?, exitCode: Int, isSignal: Boolean) {
        if (exitCode == 0 && !isSignal) return
        if (logFile == null || !logFile.isFile) return

        val tail = runCatching { logFile.readText().takeLast(200_000) }.getOrNull() ?: return
        if (SHADER_CRASH_PATTERNS.none { tail.contains(it, ignoreCase = true) }) return

        val current = runCatching { Renderers.getCurrentRenderer() }.getOrNull() ?: return
        if (current === GL4ESRenderer) return

        AllSettings.renderer.save(GL4ESRenderer.getUniqueIdentifier())
        LoggerBridge.append(
            "检测到渲染器 ${current.getRendererName()} 着色器编译失败导致崩溃，" +
                    "已自动切换为 ${GL4ESRenderer.getRendererName()}，下次启动生效（可在 设置→启动→渲染器 修改）。"
        )
    }
}
