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

package com.kcl.pcl.game.renderer.renderers

import com.kcl.pcl.game.renderer.RendererInterface

/**
 * GL4ES 低配性能版。
 *
 * 与标准 [GL4ESRenderer] 共用 `libgl4es_114.so`，区别只在翻译层参数：
 * - `LIBGL_ES=2`：固定走 GLES2 后端，老 GPU（PowerVR GE8320 / Mali-400 这类）
 *   不需要为 ES3 的能力做额外分支与着色器编译，帧率更稳；
 * - `LIBGL_NOERROR=1`：跳过每次 `glGetError`，省掉大量驱动往返；
 * - `LIBGL_NOBANNER=1`：不打印翻译层横幅（日志更干净，也少一次开销）；
 * - `LIBGL_NORMALIZE=1`：老驱动不保证法线单位化，保持与标准版一致的行为。
 *
 * 定位：给「只要 60 帧、不折腾高级效果」的老手机用。需要桌面 GL 3.3 的区块模组
 * （Sodium 等）依然不适用于 GL4ES 家族，这一点与标准版相同。
 */
object GL4ESPerfRenderer : RendererInterface {
    override fun getRendererId(): String = "opengles2"

    override fun getUniqueIdentifier(): String = "6c1f3d8a-1b7e-4f4b-9d61-2a08d0e4b7c3"

    override fun getRendererName(): String = "GL4ES 低配性能版"

    override fun getRendererSummary(): String =
        "老手机推荐：GLES2 后端 + 关错误检查，优先 60 帧"

    override fun getMaxMCVersion(): String = "1.21.4"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        buildMap {
            put("LIBGL_ES", "2")
            put("LIBGL_GL", "21")
            put("LIBGL_NOERROR", "1")
            put("LIBGL_NOBANNER", "1")
            put("LIBGL_NORMALIZE", "1")
        }
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libgl4es_114.so"
}
