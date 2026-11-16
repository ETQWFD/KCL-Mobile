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
 * Krypton Wrapper 低配性能版。
 *
 * 与标准 [NGGL4ESRenderer] 共用 `libng_gl4es.so`，区别只在翻译层参数：
 * - `LIBGL_ES=2` + `LIBGL_GL=21`：按 GLES2 能力档翻译桌面 GL 2.1，
 *   老 GPU 的着色器编译与状态切换开销显著下降；
 * - `LIBGL_NOERROR=1`：跳过每帧 `glGetError` 往返；
 * - `LIBGL_NOBANNER=1`：不打印横幅。
 *
 * 注意：PowerVR / Imagination 设备上，本渲染器与标准 Krypton 一样
 * 存在着色器编译风险，自动选型会把这类设备让给 GL4ES 家族。
 */
object NGGL4ESPerfRenderer : RendererInterface {
    override fun getRendererId(): String = "opengles3"

    override fun getUniqueIdentifier(): String = "2f7b90c4-58a1-4d0e-8c92-74be1d6a90f3"

    override fun getRendererName(): String = "Krypton 低配性能版"

    override fun getRendererSummary(): String =
        "GLES2 能力档翻译，老 GPU 提升帧率，兼容性略低于标准版"

    override fun getMaxMCVersion(): String = "26.3"

    override fun getDisplayMaxMCVersion(): String = "26.3"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        buildMap {
            put("LIBGL_USE_MC_COLOR", "1")
            put("LIBGL_ES", "2")
            put("LIBGL_GL", "21")
            put("LIBGL_NOERROR", "1")
            put("LIBGL_NOBANNER", "1")
            put("LIBGL_NORMALIZE", "1")
        }
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libng_gl4es.so"
}
