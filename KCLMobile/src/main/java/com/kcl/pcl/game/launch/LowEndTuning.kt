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

import android.app.ActivityManager
import android.content.Context
import com.kcl.pcl.game.renderer.RendererCompat
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LowEndTuning"

/**
 * 低配设备流畅度预设。
 *
 * 目标：让 4GB 以内的老手机（典型 Helio P35 + PowerVR GE8320）也能跑到稳定帧率、
 * 不因为画面设置过高而卡成幻灯片甚至 OOM 崩溃。
 *
 * 策略：**只降不升**。只把明显过高的画质项压到低配档位，玩家自己在游戏里
 * 手动调低过的设置不会被改回去；每个存档只做一次，写标记文件后不再干预。
 */
object LowEndTuning {

    /** 标记文件放在实例根目录，与 options.txt 同目录，随存档走 */
    private const val MARKER_NAME = ".kcl_lowend_profile_v7"

    /** 低配档位：键 → 上限值（只降低，不提高） */
    private val CLAMP: Map<String, String> = linkedMapOf(
        "renderDistance" to "6",
        "simulationDistance" to "4",
        "particles" to "2",        // 2 = Minimal
        "mipmapLevels" to "0",
        "ao" to "0",
        "entityShadows" to "false",
        "fancyGraphics" to "false",
        "maxFps" to "60"
    )

    /** 是否低配设备：系统低内存标记 + 渲染器侧的弱 GPU/低堆判断并集 */
    fun isLowEndDevice(context: Context): Boolean {
        val bySystem = runCatching {
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        }.getOrDefault(false)
        if (bySystem) {
            Logger.info(TAG, "系统自报低内存设备，启用低配画面预设")
            return true
        }
        val byRenderer = RendererCompat.isLowEndDevice()
        if (byRenderer) {
            Logger.info(TAG, "渲染器侧判定为低配设备（低堆或老 GPU），启用低配画面预设")
        }
        return byRenderer
    }

    /**
     * 首次启动时把 options.txt 压到低配档位。
     * 非低配设备、已应用过、没有 options.txt 时直接跳过。
     */
    suspend fun applyPerformanceProfile(context: Context, gameDir: File) = withContext(Dispatchers.IO) {
        if (!isLowEndDevice(context)) return@withContext

        val marker = File(gameDir, MARKER_NAME)
        if (marker.exists()) return@withContext

        val optionsFile = File(gameDir, "options.txt")
        if (!optionsFile.exists()) return@withContext

        try {
            val lines = optionsFile.readLines()
            val changed = mutableListOf<String>()
            val output = lines.map { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@map line

                val key = line.take(idx)
                val value = line.substring(idx + 1)
                val limit = CLAMP[key] ?: return@map line

                val clamped = clampValue(key, value, limit)
                if (clamped != null && clamped != value) {
                    changed += "$key $value → $clamped"
                    "$key:$clamped"
                } else {
                    line
                }
            }

            if (changed.isEmpty()) {
                marker.writeText("no-change")
                return@withContext
            }

            optionsFile.writeText(output.joinToString("\n"))
            marker.writeText(changed.joinToString("\n"))
            Logger.info(TAG, "低配画面预设已应用：\n" + changed.joinToString("\n"))
        } catch (e: Throwable) {
            Logger.warning(TAG, "应用低配画面预设失败", e)
        }
    }

    /** 返回需要写入的新值，null 表示保持原值（原值已经比档位更低） */
    private fun clampValue(key: String, current: String, limit: String): String? {
        val currentNum = current.trim().toIntOrNull()
        val limitNum = limit.toIntOrNull()

        // 数值项：只往下压
        if (currentNum != null && limitNum != null) {
            return if (currentNum > limitNum) limit else null
        }

        // 布尔项：只把 true 压成 false
        if (limit == "false" && current.trim().equals("true", ignoreCase = true)) {
            return "false"
        }

        // 玄幻兜底：不认识的值不动
        return null
    }
}
