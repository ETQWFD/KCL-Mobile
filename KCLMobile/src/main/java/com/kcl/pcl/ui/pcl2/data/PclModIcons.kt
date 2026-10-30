/*
 * KCL Mobile — PCL2 UI port
 *
 * 本地 Mod 图标读取。
 *
 * PCL 的 `MyLocalModItem` 在能匹配到远端项目时用项目图标，否则退回 `Icons/NoIcon.png`。
 * 手机上没有「Mod 平台项目」这层在线数据，所以这里改成**直接读模组包自带图标**
 * （这也是模组作者真正想让你看到的那张图）：
 *
 *   1. `META-INF/mods.toml`      → `logoFile = "..."`      （Forge 1.13+）
 *   2. `mcmod.info`              → `"logoFile": "..."`      （Forge 1.12-）
 *   3. `fabric.mod.json`         → `"icon": "..."` / `{"128": "..."}`
 *   4. `quilt.mod.json`          → 同上
 *   5. `litemod.json`            → `"logoFile": "..."`
 *   6. 回退：`assets/<modid>/icon.png`、`icon.png`、`logo.png`、`pack.png`
 *
 * 全部取不到时才用 PCL 的 `NoIcon.png` 占位（由调用方决定）。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kcl.pcl.game.version.mod.LocalMod
import java.util.zip.ZipFile

object PclModIcons {

    /** 缓存：key = 路径 + 大小 + 修改时间（文件被换掉会自动失效）。 */
    private val cache = HashMap<String, ImageBitmap?>()

    /** `icon` / `logoFile` 这类字段的取值。 */
    private val IMAGE_EXT = "(?:png|jpg|jpeg|webp|bmp)"

    private val FORGE_TOML_LOGO = Regex("""logoFile\s*=\s*["']([^"']+)["']""")
    private val JSON_LOGO = Regex(""""logoFile"\s*:\s*"([^"]+)"""")
    private val JSON_ICON = Regex(""""icon"\s*:\s*"([^"]+)"""")
    private val JSON_ICON_OBJ = Regex(""""icon"\s*:\s*\{([^}]*)\}""")
    private val JSON_IMAGE = Regex("\"([^\"]+\\.$IMAGE_EXT)\"")

    /**
     * 批量读取图标（在 IO 线程调用）。
     * @return `mod 文件绝对路径` to 图标
     */
    fun loadAll(mods: List<LocalMod>): Map<String, ImageBitmap> {
        val result = HashMap<String, ImageBitmap>()
        val alive = HashSet<String>()
        mods.forEach { mod ->
            val file = mod.file
            val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
            alive += key
            val hit = cache[key]
            if (hit != null) {
                result[file.absolutePath] = hit
            } else if (!cache.containsKey(key)) {
                val bitmap = runCatching { extract(mod) }.getOrNull()
                cache[key] = bitmap
                if (bitmap != null) result[file.absolutePath] = bitmap
            }
        }
        // 清掉已经不在列表里的缓存，避免长时间使用后内存里攒一堆贴图
        cache.keys.retainAll(alive)
        return result
    }

    /** 单个 mod 的图标；读不到返回 null。 */
    private fun extract(mod: LocalMod): ImageBitmap? {
        if (mod.notMod) return null
        val file = mod.file
        if (!file.isFile || !file.canRead()) return null
        return ZipFile(file).use { zip ->
            val candidates = mutableListOf<String>()
            readEntryText(zip, "META-INF/mods.toml")?.let { text ->
                FORGE_TOML_LOGO.find(text)?.groupValues?.get(1)?.let(candidates::add)
            }
            readEntryText(zip, "mcmod.info")?.let { text ->
                JSON_LOGO.find(text)?.groupValues?.get(1)?.let(candidates::add)
            }
            readEntryText(zip, "fabric.mod.json")?.let { collectJsonIcon(it, candidates) }
            readEntryText(zip, "quilt.mod.json")?.let { collectJsonIcon(it, candidates) }
            readEntryText(zip, "litemod.json")?.let { text ->
                JSON_LOGO.find(text)?.groupValues?.get(1)?.let(candidates::add)
            }

            val id = mod.id.trim()
            if (id.isNotEmpty()) {
                candidates += "assets/$id/icon.png"
                candidates += "assets/$id/logo.png"
                candidates += "assets/$id/icon.jpg"
            }
            candidates += "icon.png"
            candidates += "logo.png"
            candidates += "pack.png"

            candidates.asSequence()
                .map { it.trim().removePrefix("/") }
                .filter { it.isNotEmpty() }
                .mapNotNull { path ->
                    val entry = zip.getEntry(path) ?: return@mapNotNull null
                    if (entry.isDirectory) return@mapNotNull null
                    runCatching {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }.getOrNull()
                }
                .mapNotNull { bytes ->
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                .firstOrNull()
        }
    }

    /** 从 `fabric.mod.json` / `quilt.mod.json` 里抠出 `"icon"` 的值（字符串或以尺寸为键的对象）。 */
    private fun collectJsonIcon(text: String, out: MutableList<String>) {
        JSON_ICON_OBJ.find(text)?.groupValues?.get(1)?.let { body ->
            JSON_IMAGE.find(body)?.groupValues?.get(1)?.let(out::add)
        }
        JSON_ICON.find(text)?.groupValues?.get(1)?.let(out::add)
    }

    private fun readEntryText(zip: ZipFile, path: String): String? = runCatching {
        val entry = zip.getEntry(path) ?: zip.getEntry("/$path") ?: return null
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** 目录里是否还有别的东西需要刷新缓存（换文件夹时调用）。 */
    fun invalidate() = cache.clear()
}
