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

package com.kcl.pcl.game.addons.mirror

import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.setting.enums.MirrorSourceType
import com.kcl.pcl.utils.isChinaMainland

private const val ROOT = "https://bmclapi2.bangbang93.com"

enum class BMCLAPI(val url: String) {
    BASE_URL(ROOT),
    MAVEN("$ROOT/maven"),
    ASSETS("$ROOT/assets"),
    LIBRARIES("$ROOT/libraries")
}

/**
 * [Modified from HMCL](https://github.com/HMCL-dev/HMCL/blob/9aa1367/HMCLCore/src/main/java/org/jackhuang/hmcl/download/BMCLAPIDownloadProvider.java#L64-L83)
 */
private val REPLACE_MIRROR_HOLDERS = mapOf(
    Pair(BMCLAPI.BASE_URL.url, BMCLAPI.BASE_URL.url),
    Pair("https://launchermeta.mojang.com", BMCLAPI.BASE_URL.url),
    Pair("https://piston-meta.mojang.com", BMCLAPI.BASE_URL.url),
    Pair("https://piston-data.mojang.com", BMCLAPI.BASE_URL.url),
    Pair("https://launcher.mojang.com", BMCLAPI.BASE_URL.url),
    Pair("https://libraries.minecraft.net", BMCLAPI.LIBRARIES.url),
    Pair("https://resources.download.minecraft.net", BMCLAPI.ASSETS.url),
    Pair("http://files.minecraftforge.net/maven", BMCLAPI.MAVEN.url),
    Pair("https://files.minecraftforge.net/maven", BMCLAPI.MAVEN.url),
    Pair("https://maven.minecraftforge.net", BMCLAPI.MAVEN.url),
    // Forge / NeoForge 必须按**整个 releases 仓库**映射，不能只映射 forge 与 neoforge 两个 artifact：
    // 它们的 `install_profile.json` 里还引用着同一个仓库下的
    // installertools / JarJarFileSystems / accesstransformers / org.jline 等一堆依赖，
    // 只映射两个 artifact 就会漏掉这些，表现为「加载器装到一半，Failed downloads 里列出一串
    // net/neoforged/... 的路径」—— installer 本体下得下来（它正好匹配那两个前缀），
    // 它自己的安装依赖却全部直连墙外的 maven.neoforged.net，在国内必然全军覆没。
    Pair("https://maven.neoforged.net/releases", BMCLAPI.MAVEN.url),
    Pair("http://dl.liteloader.com/versions/versions.json", BMCLAPI.MAVEN.url + "/com/mumfrey/liteloader/versions.json"),
    Pair("http://dl.liteloader.com/versions", BMCLAPI.MAVEN.url),
    Pair("https://meta.fabricmc.net", BMCLAPI.BASE_URL.url + "/fabric-meta"),
    Pair("https://maven.fabricmc.net", BMCLAPI.MAVEN.url),
    Pair("https://authlib-injector.yushi.moe", BMCLAPI.BASE_URL.url + "/mirrors/authlib-injector"),
    Pair("https://repo1.maven.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
    Pair("https://repo.maven.apache.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
    Pair("https://hmcl-dev.github.io/metadata/cleanroom", "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/cleanroom")
)

/**
 * 按前缀查出这个链接对应的镜像链接，不做地区判定、不读用户设置。
 *
 * 单独抽出来是为了能被单元测试直接调用 —— [REPLACE_MIRROR_HOLDERS] 漏一个前缀，
 * 就会让整类下载直连墙外源头，而它只在「中国大陆」这个运行时分支里才生效，
 * 实机上很难每轮都覆盖到（`maven.neoforged.net` 的 installertools 依赖就是这么漏的）。
 *
 * @return 替换后的镜像链接；没有命中任何前缀时返回 null（调用方需回退到官方源）
 */
internal fun String.mirrorUrlFor(): String? =
    REPLACE_MIRROR_HOLDERS.entries
        .firstOrNull { (origin, _) -> startsWith(origin) }
        ?.let { (origin, mirror) -> replaceFirst(origin, mirror) }

/**
 * 替换为镜像源链接并按用户偏好排序；非中国大陆不注入任何镜像。
 *
 * 「自动」档优先采用 [SourceSpeedTest] 的实测最快源
 * （`AllSettings.gameDownloadFastestSource`），没测过或测速开关关掉时
 * 退回原来的静态判定（是否中国大陆）。
 */
fun String.mapBMCLMirrorUrls(): List<String> {
    if (!isChinaMainland()) return listOf(this)

    val mirrorUrl = mirrorUrlFor()

    val source = AllSettings.gameDownloadSource.getValue()
    val measured = AllSettings.gameDownloadFastestSource.getValue()

    // 只有「自动」档才用实测结果：用户明确选了官方/镜像时尊重用户的选择
    if (source == MirrorSourceType.AUTO &&
        AllSettings.gameDownloadSourceProbe.getValue() &&
        measured != MirrorSourceType.AUTO &&
        mirrorUrl != null
    ) {
        // 实测档同时保留另一个源作为兜底（下载引擎会按顺序换源）
        return if (measured == MirrorSourceType.MIRROR) {
            listOf(mirrorUrl, this)
        } else {
            listOf(this, mirrorUrl)
        }
    }

    return orderCandidates(
        official = this,
        mirror = mirrorUrl,
        priority = resolveMirrorPriority(source = source, mainland = true)
    )
}