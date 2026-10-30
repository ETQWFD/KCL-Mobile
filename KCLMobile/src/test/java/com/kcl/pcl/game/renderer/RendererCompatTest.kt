package com.kcl.pcl.game.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 渲染器自动兼容的判据回归。
 *
 * 两条判据决定「要不要自动把渲染器换成 Kopper Zink」：
 * 1. **mods 里有没有需要桌面 OpenGL 3.3+ 的模组** —— 靠文件名分词匹配，
 *    必须认得 `sodium-fabric-0.5.13+mc1.20.1.jar` 这种正常命名，同时不能
 *    把 `desodium-x.jar` 这种「只是碰巧含子串」的 jar 也算进来；
 * 2. **当前渲染器是不是 GL4ES 系** —— 靠渲染器库名里有没有 `gl4es`。
 *
 * 判错任何一条都会让用户白卡一次（漏判）或者被无故换掉能用的渲染器（误判），
 * 所以这里把两侧边界都钉住。
 */
class RendererCompatTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 造一个 `gameDir/mods`，返回 gameDir。 */
    private fun gameDirWithMods(vararg modNames: String): File {
        val mods = temp.newFolder("game", "mods")
        modNames.forEach { File(mods, it).writeText("") }
        return mods.parentFile!!
    }

    @Test
    fun `recognises normally named sodium and iris jars`() {
        val dir = gameDirWithMods(
            "sodium-fabric-0.5.13+mc1.20.1.jar",
            "iris-mc1.20.1-1.6.5.jar",
            "fabric-api-0.92.2+1.20.1.jar",
            "lithium-fabric-mc1.20.1-0.11.2.jar",
        )
        assertEquals(listOf("Sodium", "Iris"), RendererCompat.scanDesktopGlMods(dir))
    }

    @Test
    fun `recognises the whole family including embeds and addons`() {
        val dir = gameDirWithMods(
            "embeddium-0.3.31+mc1.20.1.jar",
            "oculus-mc1.20.1-1.7.0.jar",
            "reeses-sodium-options-mc1.20.1-1.7.0.jar",
        )
        // 结果顺序固定按内部声明顺序（Sodium → Embeddium → … → Oculus），
        // 与 listFiles() 的枚举顺序无关
        assertEquals(listOf("Sodium", "Embeddium", "Oculus"), RendererCompat.scanDesktopGlMods(dir))
    }

    @Test
    fun `does not fire on mere substring matches`() {
        val dir = gameDirWithMods(
            "desodium-x.jar",
            "miris-helper.jar",
            "sodiumlike-loader.jar",
        )
        // 关键字必须作为一个完整 token 出现，`desodium` / `miris` / `sodiumlike` 都不算
        assertEquals(emptyList<String>(), RendererCompat.scanDesktopGlMods(dir))
    }

    @Test
    fun `ignores disabled jars and non-jar files`() {
        val dir = gameDirWithMods(
            "sodium-fabric-0.5.13.jar.disabled",
            "iris-1.6.5.jar.bak",
            "sodium-notes.txt",
        )
        assertEquals(emptyList<String>(), RendererCompat.scanDesktopGlMods(dir))
    }

    @Test
    fun `returns empty when there is no mods folder at all`() {
        val dir = temp.newFolder("empty-game")
        assertEquals(emptyList<String>(), RendererCompat.scanDesktopGlMods(dir))
    }

    @Test
    fun `only gl4es backed renderers count as the gl4es family`() {
        assertTrue(RendererCompat.isGL4ESFamily(fakeRenderer("libng_gl4es.so")))
        assertTrue(RendererCompat.isGL4ESFamily(fakeRenderer("libgl4es_114.so")))
        assertFalse(RendererCompat.isGL4ESFamily(fakeRenderer("libglxshim.so")))
        assertFalse(RendererCompat.isGL4ESFamily(fakeRenderer("/data/app/lwjgl/libLTW.so")))
    }

    private fun fakeRenderer(library: String): RendererInterface = object : RendererInterface {
        override fun getRendererId(): String = "test"
        override fun getUniqueIdentifier(): String = "test"
        override fun getRendererName(): String = "Test"
        override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }
        override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }
        override fun getRendererLibrary(): String = library
    }
}
