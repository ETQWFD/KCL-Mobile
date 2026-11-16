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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

private const val TAG = "ModCompatGuard"

/**
 * 启动前模组兼容性守卫。
 *
 * 背景：Fabric / Quilt 在模组声明的 `depends.minecraft` 版本范围与当前游戏版本不符时，
 * 会在 `Mod resolution failed` 阶段直接抛 `FormattedException`，进程退出码 1。
 * 对玩家来说这看起来就是「点启动 → 闪一下 → 回桌面」，日志里全是英文异常，
 * 完全不知道是自己装的某个模组版本对不上。
 *
 * 这里在启动前先自己把 `mods` 里每个 jar 的 `fabric.mod.json` / `quilt.mod.json`
 * 读出来，解析 `depends.minecraft` 的版本范围并与当前游戏版本比对：
 * - 不兼容的模组自动改名为 `xxx.jar.disabled`（启动器里显示为「已禁用」，随时可手动改回）
 * - 返回一份中文报告，由调用方提示用户
 *
 * 只处理 Fabric / Quilt 的元数据。Forge 的 `mods.toml` 版本范围语义不同，
 * 且当前手机版实战中几乎都是 Fabric，先不猜。
 */
object ModCompatGuard {

    /** 一条不兼容记录 */
    data class IncompatibleMod(
        /** 文件名（禁用前的原始名） */
        val fileName: String,
        /** 模组 ID */
        val id: String,
        /** 展示名，取不到时回退 ID */
        val name: String,
        /** 模组版本 */
        val version: String?,
        /** 模组要求的 Minecraft 版本范围原文 */
        val requiredRange: String,
        /** 当前实际游戏版本 */
        val actualVersion: String,
        /** 是否已成功禁用 */
        val disabled: Boolean
    ) {
        /** 一行中文描述 */
        fun describe(): String = buildString {
            append(name)
            if (!version.isNullOrBlank()) append(" ").append(version)
            append("（").append(fileName).append("）")
            append(" 要求 Minecraft ").append(requiredRange)
            append("，当前 ").append(actualVersion)
            append(if (disabled) "，已自动禁用" else "，禁用失败，请手动移出 mods 文件夹")
        }
    }

    /**
     * 扫描并禁用与当前游戏版本不兼容的模组
     * @param modsDir 实例的 mods 目录
     * @param minecraftVersion 当前要启动的 Minecraft 版本，例如 `1.21.1`
     * @return 被判定为不兼容的模组列表，空表示没问题
     */
    suspend fun checkAndDisable(
        modsDir: File,
        minecraftVersion: String
    ): List<IncompatibleMod> = withContext(Dispatchers.IO) {
        if (minecraftVersion.isBlank()) return@withContext emptyList()
        if (!modsDir.isDirectory) return@withContext emptyList()

        val files = runCatching {
            modsDir.listFiles { file -> file.isFile && file.name.lowercase().endsWith(".jar") }
        }.getOrNull() ?: return@withContext emptyList()

        val result = mutableListOf<IncompatibleMod>()

        files.forEach { file ->
            try {
                val meta = readMetadata(file) ?: return@forEach
                val range = meta.minecraftRange ?: return@forEach
                if (range.isBlank() || range.trim() == "*") return@forEach
                if (VersionRange.matches(range, minecraftVersion)) return@forEach

                // 不兼容，尝试禁用
                val disabled = disableFile(file)
                val record = IncompatibleMod(
                    fileName = file.name,
                    id = meta.id,
                    name = meta.name.ifBlank { meta.id },
                    version = meta.version,
                    requiredRange = range,
                    actualVersion = minecraftVersion,
                    disabled = disabled
                )
                result += record
                Logger.warning(
                    TAG,
                    "模组与当前游戏版本不兼容，已${if (disabled) "自动禁用" else "尝试禁用失败"}：${record.describe()}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 单个 jar 读不了（损坏、加密、不是模组）不影响其他模组
                Logger.warning(TAG, "读取模组元数据失败，已跳过：${file.name}", e)
            }
        }

        result
    }

    /** 生成给玩家看的中文报告，null 表示没有不兼容模组 */
    fun buildReport(list: List<IncompatibleMod>): String? {
        if (list.isEmpty()) return null
        return buildString {
            append("发现 ")
            append(list.size)
            append(" 个模组与当前游戏版本不兼容，已自动禁用：")
            list.forEach { append("\n· ").append(it.describe()) }
            append("\n\n如需恢复，把 mods 文件夹里对应的 .jar.disabled 改回 .jar 即可；")
            append("也可以在启动器「版本设置 → 模组管理」里重新启用（需要先把游戏版本换成模组要求的版本）。")
        }
    }

    private data class ModMeta(
        val id: String,
        val name: String,
        val version: String?,
        val minecraftRange: String?
    )

    private fun readMetadata(file: File): ModMeta? {
        ZipFile(file).use { zip ->
            for (entryName in listOf("fabric.mod.json", "quilt.mod.json")) {
                val entry = zip.getEntry(entryName) ?: continue
                val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val root = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val meta = parseMeta(root) ?: continue
                return meta
            }
        }
        return null
    }

    private fun parseMeta(root: JsonObject): ModMeta? {
        val id = root.stringOrNull("id") ?: return null
        val name = root.stringOrNull("name") ?: id
        val version = root.stringOrNull("version")

        val depends = root.get("depends")?.takeIf { it.isJsonObject }?.asJsonObject
        val mcNode = depends?.get("minecraft")
        val mcRange = jsonToString(mcNode)

        return ModMeta(
            id = id,
            name = name,
            version = version,
            minecraftRange = mcRange
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** depends.minecraft 既可能是字符串，也可能是字符串数组（多个候选范围，任一满足即可） */
    private fun jsonToString(node: JsonElement?): String? {
        if (node == null || node.isJsonNull) return null
        return when {
            node.isJsonPrimitive -> node.asString
            node is JsonArray -> node.mapNotNull {
                if (it.isJsonPrimitive) it.asString else null
            }.takeIf { it.isNotEmpty() }?.joinToString(" || ")
            else -> null
        }
    }

    private fun disableFile(file: File): Boolean {
        return try {
            val target = File(file.absolutePath + ".disabled")
            Files.move(
                file.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Throwable) {
            Logger.warning(TAG, "禁用模组失败：${file.name}", e)
            false
        }
    }
}

/**
 * Minecraft 模组版本范围判定（Maven / Fabric 语义的实用子集）。
 *
 * 支持写法：
 * - `*`、空串：任意版本
 * - `1.20.1`：精确该版本
 * - `>=1.20.1`、`<=1.21`、`>1.20`、`<1.21.2`
 * - `>=1.20.1 <1.21`：空格分隔的「且」
 * - `~1.20.1`：>= 1.20.1 且 < 1.21（次版本上界）
 * - `^1.20`：>= 1.20 且 < 2.0
 * - `1.20.x`、`1.20.*`：前缀匹配
 * - `[1.20.1,1.21)`、`(,1.21]`、`[1.20,1.21]`：区间
 * - 多个候选范围用 ` || ` 连接时，任一满足即通过（由 [matches] 处理）
 */
object VersionRange {

    fun matches(range: String, version: String): Boolean {
        val candidates = range.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return true
        return candidates.any { matchesSingle(it, version) }
    }

    private fun matchesSingle(expr: String, version: String): Boolean {
        val trimmed = expr.trim()
        if (trimmed.isEmpty() || trimmed == "*") return true

        // 区间写法
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            return matchesInterval(trimmed, version)
        }

        // 空格分隔的「且」
        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size > 1) {
            return parts.all { matchesSingle(it, version) }
        }

        val token = parts.first()
        return when {
            token.startsWith(">=") -> compare(version, token.removePrefix(">=")) >= 0
            token.startsWith("<=") -> compare(version, token.removePrefix("<=")) <= 0
            token.startsWith(">") -> compare(version, token.removePrefix(">")) > 0
            token.startsWith("<") -> compare(version, token.removePrefix("<")) < 0
            token.startsWith("~") -> matchesTilde(token.removePrefix("~"), version)
            token.startsWith("^") -> matchesCaret(token.removePrefix("^"), version)
            token.contains('*') || token.contains('x') -> matchesWildcard(token, version)
            else -> compare(version, token) == 0
        }
    }

    private fun matchesInterval(expr: String, version: String): Boolean {
        val body = expr.removePrefix("[").removePrefix("(")
        val closing = expr.last()
        val content = body.removeSuffix("]").removeSuffix(")")
        val tokens = content.split(",").map { it.trim() }
        if (tokens.size != 2) return false

        val lower = tokens[0]
        val upper = tokens[1]
        val lowerInclusive = expr.startsWith("[")
        val upperInclusive = closing == ']'

        if (lower.isNotEmpty()) {
            val cmp = compare(version, lower)
            if (if (lowerInclusive) cmp < 0 else cmp <= 0) return false
        }
        if (upper.isNotEmpty()) {
            val cmp = compare(version, upper)
            if (if (upperInclusive) cmp > 0 else cmp >= 0) return false
        }
        return true
    }

    private fun matchesTilde(base: String, version: String): Boolean {
        if (compare(version, base) < 0) return false
        val segments = splitVersion(base)
        if (segments.isEmpty()) return true
        val upperSegments = if (segments.size <= 1) {
            listOf((segments[0].toIntOrNull() ?: 0) + 1)
        } else {
            val major = segments[0]
            val minor = (segments[1].toIntOrNull() ?: 0) + 1
            listOf(major, minor.toString())
        }
        val upper = upperSegments.joinToString(".")
        return compare(version, upper) < 0
    }

    private fun matchesCaret(base: String, version: String): Boolean {
        if (compare(version, base) < 0) return false
        val segments = splitVersion(base)
        if (segments.isEmpty()) return true
        val major = (segments[0].toIntOrNull() ?: 0) + 1
        return compare(version, "$major.0") < 0
    }

    private fun matchesWildcard(pattern: String, version: String): Boolean {
        val prefix = splitVersion(pattern).takeWhile { it != "*" && it != "x" && it != "X" }
        if (prefix.isEmpty()) return true
        val versionSegments = splitVersion(version)
        if (versionSegments.size < prefix.size) return false
        return prefix.indices.all { versionSegments[it] == prefix[it] }
    }

    private fun splitVersion(version: String): List<String> =
        version.trim().split('.', '-', '+').filter { it.isNotBlank() }

    /**
     * 比较两个版本号，返回负数表示 a < b。
     * 带预发布后缀（`1.21-rc1`、`1.20.1-pre2`）的按「数字段优先、同段字符串按字典序」处理，
     * 对 Minecraft 的版本号足够用。
     */
    fun compare(a: String, b: String): Int {
        val left = splitVersion(a)
        val right = splitVersion(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { "0" }
            val r = right.getOrElse(i) { "0" }
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val cmp = when {
                ln != null && rn != null -> ln.compareTo(rn)
                ln != null -> 1     // 纯数字段比预发布后缀大
                rn != null -> -1
                else -> l.compareTo(r, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
