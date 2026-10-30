package com.kcl.pcl.game.addons.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 镜像前缀表的回归。
 *
 * 这张表决定「哪些下载链接会被换成国内镜像」。它漏一个前缀不会有任何编译期或运行期提示，
 * 只表现为**某一整类文件全部下载失败** —— 而且只在「中国大陆」这个运行时分支里才会走到
 * （`mapBMCLMirrorUrls` 第一行就把非大陆直接返回官方链），所以非大陆的机器永远测不出来。
 *
 * 实际踩过的坑：`maven.neoforged.net` 原先只映射了 `net/neoforged/forge` 与
 * `net/neoforged/neoforge` 两个 artifact 前缀。NeoForge 的 `install_profile.json` 里
 * 还引用着同一仓库下的 `installertools`、`JarJarFileSystems`、`accesstransformers`、
 * `org.jline` 等依赖，它们都落不到那两个前缀上，于是 installer 本体下载成功、
 * 安装依赖却全部直连墙外域名，最终抛出一串 `net/neoforged/...` 的 Failed downloads。
 */
class BMCLAPIMirrorTest {

    private val base = "https://bmclapi2.bangbang93.com"
    private val maven = "$base/maven"

    private fun assertMirrors(path: String, officialPrefix: String, mirrorPrefix: String) {
        val official = "$officialPrefix/$path"
        assertEquals(path, "$mirrorPrefix/$path", official.mirrorUrlFor())
    }

    @Test
    fun everyNeoForgeReleasesPathIsMirrored() {
        // 这一组就是线上失败的那几个文件，加 issue 里常见的同仓库依赖。
        val paths = listOf(
            "net/neoforged/installertools/binarypatcher/2.1.2/binarypatcher-2.1.2-fatjar.jar",
            "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar",
            "net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1.jar",
            "net/neoforged/installertools/jarsplitter/1.0.2/jarsplitter-1.0.2-fatjar.jar",
            "org/jline/jline-terminal/3.20.0/jline-terminal-3.20.0.jar",
            // 被合并掉的那两条专用前缀，行为必须一模一样，不能为了收口而回归
            "net/neoforged/neoforge/21.1.72/neoforge-21.1.72-installer.jar",
            "net/neoforged/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar",
        )
        paths.forEach { assertMirrors(it, "https://maven.neoforged.net/releases", maven) }
    }

    @Test
    fun mojangAndForgeLikeReposAreMirrored() {
        assertMirrors(
            "net/neoforged/installertools/binarypatcher/2.1.2/binarypatcher-2.1.2-fatjar.jar",
            "https://maven.minecraftforge.net",
            maven,
        )
        assertMirrors("com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar", "https://libraries.minecraft.net", "$base/libraries")
        assertMirrors("net/fabricmc/loader/0.15.0/loader-0.15.0.jar", "https://maven.fabricmc.net", maven)
    }

    @Test
    fun mojangMetadataIsMirrored() {
        // piston-meta 是整域替换，路径原样接到 BMCLAPI 根后面
        assertEquals(
            "$base/mc/game/version_manifest_v2.json",
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json".mirrorUrlFor(),
        )
        assertEquals(
            "指向 BMCLAPI 自身的链接必须原样返回，不能被二次拼接",
            "$base/version_manifest_v2.json",
            "$base/version_manifest_v2.json".mirrorUrlFor(),
        )
    }

    @Test
    fun unknownHostKeepsNoMirror() {
        // 没有镜像的链接必须返回 null，让调用方回退到官方源而不是拼出一个 404 链接
        assertNull("https://example.com/libraries/foo.jar".mirrorUrlFor())
        assertNull("https://maven.neoforged.net/snapshots/foo.jar".mirrorUrlFor())
        assertNull("https://maven.parchmentmc.org/releases/foo.jar".mirrorUrlFor())
    }

    @Test
    fun moreSpecificPrefixMustBeDeclaredFirst() {
        // 实现是**按声明顺序**取第一个前缀匹配（不是最长匹配），所以更具体的前缀必须排在前面。
        // liteloader 的 versions.json 是一条比它的父前缀更深的前缀，顺序反了就会拼出
        // `/maven/versions.json` 这种不存在的路径 —— liteloader 装不上，而且没有报错线索。
        assertEquals(
            "$maven/com/mumfrey/liteloader/versions.json",
            "http://dl.liteloader.com/versions/versions.json".mirrorUrlFor(),
        )
        assertEquals(
            "$maven/liteloader/versions.json",
            "http://dl.liteloader.com/versions/liteloader/versions.json".mirrorUrlFor(),
        )
    }
}
