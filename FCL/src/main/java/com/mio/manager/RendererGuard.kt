package com.mio.manager

import com.mio.data.Renderer
import com.tungsten.fcl.FCLApp
import java.io.File
import java.util.Properties

/**
 * 渲染器兼容性守卫（跨进程）。
 *
 * 游戏运行在独立进程，崩溃时主界面无法直接切换渲染器。这里用 filesDir 下的一个
 * Properties 文件做进程间传递：
 *  - 主进程启动前记录本次使用的渲染器；
 *  - 游戏进程退出时，若为图形/驱动类崩溃，则按兼容顺序选出下一个后备渲染器并写入请求；
 *  - 主进程下次启动读取到请求后自动切换并提示，玩家无需懂渲染器即可恢复游戏。
 *
 * 正常退出（exitCode == 0）或玩家手动选择渲染器时重置，避免无限循环切换。
 */
object RendererGuard {

    private const val FILE_NAME = "renderer_guard.properties"
    private const val KEY_LAST = "lastRenderer"
    private const val KEY_REQUEST = "requestRenderer"
    private const val KEY_COUNT = "graphicsCrashCount"

    /**
     * 后备兼容顺序：默认新版 Krypton 包装器在部分 GPU 上不兼容时，
     * 依次回退到经典 Holy-GL4ES（兼容性最广）→ VirGL/OSMesa → Zink。
     */
    private val FALLBACK_CHAIN: List<String> = listOf(
        Renderer.ID_NGGL4ES,
        Renderer.ID_GL4ES,
        Renderer.ID_VIRGL,
        Renderer.ID_ZINK
    )

    /** 强图形/驱动特征，命中其一即判定为渲染器不兼容导致的崩溃。 */
    private val GRAPHICS_TOKENS: List<String> = listOf(
        "libgl4es", "libng_gl4es", "libegl", "libosmesa", "libglxshim", "libvgpu",
        "libgl.so", "gl4es", "osmesa",
        "egl_", "eglget", "eglinitialize", "eglmakecurrent", "eglchooseconfig",
        "opengl es", "opengl context", "gl context", "gles2", "gles3",
        "failed to create opengl", "could not create opengl", "unable to create opengl",
        "glx", "swapchain", "gralloc", "pixel format",
        "virglrenderer", "virgl", "zink", "libvulkan", "vulkan-1",
        "unsupported renderer", "renderer not supported", "gfx error",
        "glfw error 65543", "glfw error 65545", "glfw error 65542"
    )

    private fun file(): File = File(FCLApp.getAppContext().filesDir, FILE_NAME)

    private fun load(): Properties {
        val p = Properties()
        val f = file()
        if (f.exists()) {
            try {
                f.inputStream().use { p.load(it) }
            } catch (e: Exception) {
                // 文件损坏则当作无状态
            }
        }
        return p
    }

    /** 原子写入，避免两个进程同时写导致配置损坏。 */
    private fun store(p: Properties) {
        val f = file()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        try {
            tmp.outputStream().use { p.store(it, "KCL renderer guard") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // 守卫失效不应影响游戏启动
        }
    }

    private fun isRendererAvailable(id: String): Boolean =
        RendererManager.rendererList.any { it.id == id }

    /** 主进程：记录本次启动使用的渲染器。 */
    @JvmStatic
    fun recordLaunchRenderer(id: String?) {
        if (id.isNullOrBlank()) return
        val p = load()
        p.setProperty(KEY_LAST, id)
        store(p)
    }

    /**
     * 主进程：若上次因图形崩溃请求了后备渲染器，消费并返回新渲染器 id（同时持久化到设置由调用方完成）；
     * 否则返回 null。
     */
    @JvmStatic
    fun consumeRequestedFallback(currentId: String?): String? {
        val p = load()
        val req = p.getProperty(KEY_REQUEST)?.takeIf { it.isNotBlank() }
        p.remove(KEY_REQUEST)
        store(p)
        if (req.isNullOrEmpty() || req == currentId) return null
        return if (isRendererAvailable(req)) req else null
    }

    /** 玩家手动选择渲染器或正常退出时调用，清空崩溃计数与待处理请求。 */
    @JvmStatic
    fun reset() {
        val p = load()
        p.remove(KEY_REQUEST)
        p.remove(KEY_COUNT)
        store(p)
    }

    /**
     * 游戏进程：退出回调。exitCode == 0 视为正常退出并重置；非 0 且日志含图形特征时推进后备链。
     */
    @JvmStatic
    fun onGameExit(exitCode: Int, log: String?) {
        if (exitCode == 0) {
            reset()
            return
        }
        if (!isGraphicsCrash(log)) return

        val p = load()
        val last = p.getProperty(KEY_LAST)?.takeIf { it.isNotBlank() }
        val count = p.getProperty(KEY_COUNT)?.toIntOrNull() ?: 0
        val next = nextFallback(last)

        if (next == null) {
            // 整条兼容链都已尝试，放弃自动切换，避免死循环；玩家可手动选择。
            p.remove(KEY_REQUEST)
            p.setProperty(KEY_COUNT, "0")
        } else {
            p.setProperty(KEY_REQUEST, next)
            p.setProperty(KEY_LAST, next)
            p.setProperty(KEY_COUNT, (count + 1).toString())
        }
        store(p)
    }

    private fun nextFallback(currentId: String?): String? {
        // 当前渲染器在链中的位置；不在链中（如插件渲染器）则从最兼容的 Holy-GL4ES 开始。
        val idx = if (currentId != null) FALLBACK_CHAIN.indexOf(currentId) else -1
        val start = if (idx >= 0) idx + 1 else 1
        for (i in start until FALLBACK_CHAIN.size) {
            val id = FALLBACK_CHAIN[i]
            if (isRendererAvailable(id)) return id
        }
        // 当前不在链中时，上面从 1 开始；再补一次链首（Krypton）兜底。
        if (idx < 0 && isRendererAvailable(Renderer.ID_NGGL4ES)) return Renderer.ID_NGGL4ES
        return null
    }

    private fun isGraphicsCrash(log: String?): Boolean {
        if (log.isNullOrEmpty()) return false
        val lower = log.lowercase()
        // 只检查日志末尾一段，避免长日志前面的无关 GL 字样误判。
        val tail = if (lower.length > 20000) lower.substring(lower.length - 20000) else lower
        return GRAPHICS_TOKENS.any { tail.contains(it) }
    }
}
