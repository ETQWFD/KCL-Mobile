/*
 * KCL Mobile — PCL2 UI port
 *
 * 背景高斯模糊的纯算法测试（不碰 Android 位图，只测卷积本身）。
 * 这三条断言覆盖了最容易写错、又最容易在画面上暴露的地方：
 *   · 核归一化 —— 不归一化整张图会整体变暗/变亮；
 *   · 常量图不变 —— 定点移位（>>16）写错一位就会在这里炸；
 *   · 冲激扩散 —— 权重真正是「高斯」的（对称、中心最大、向四周衰减）。
 */

package com.kcl.pcl.ui.pcl2

import com.kcl.pcl.ui.pcl2.components.PclGaussianBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PclGaussianBlurTest {

    @Test
    fun `kernel is normalized and symmetric`() {
        for (sigma in listOf(0.6f, 2f, 5.5f, 8.3f)) {
            val kernel = PclGaussianBlur.gaussianKernel(sigma)
            // 定点权重的总和必须精确等于 1.0（用 65536 表示）
            assertEquals("sigma=$sigma", 65536, kernel.sum())
            assertTrue("sigma=$sigma 有负权重", kernel.all { it >= 0 })
            assertEquals("sigma=$sigma 不是对称核", kernel.toList(), kernel.reversed())
            // 中心权重最大
            val center = kernel.size / 2
            assertEquals("sigma=$sigma 中心不是最大值", kernel.max(), kernel[center])
        }
    }

    @Test
    fun `constant image stays constant including borders`() {
        val width = 32
        val height = 16
        val color = 0xFF336699.toInt()
        val pixels = IntArray(width * height) { color }
        PclGaussianBlur.convolve(pixels, width, height, PclGaussianBlur.gaussianKernel(4f))
        pixels.forEachIndexed { index, p ->
            assertEquals("像素 #$index 变了", color, p)
        }
    }

    @Test
    fun `impulse spreads symmetrically`() {
        val size = 41
        val center = size / 2
        val pixels = IntArray(size * size) { 0xFF000000.toInt() }
        pixels[center * size + center] = 0xFFFFFFFF.toInt()

        // sigma 别取太大：二维高斯是「横竖两趟」叠出来的，中心值 ≈ 255 × (核中心权重)²。
        // sigma=3 时只剩个位数，量化误差会把「中心 > 邻格」抹平，测不出东西。
        PclGaussianBlur.convolve(pixels, size, size, PclGaussianBlur.gaussianKernel(1.5f))

        fun redAt(x: Int, y: Int) = pixels[y * size + x] and 0xFF

        val mid = redAt(center, center)
        val left = redAt(center - 1, center)
        val right = redAt(center + 1, center)
        val up = redAt(center, center - 1)
        val down = redAt(center, center + 1)
        val far = redAt(center + 8, center)

        assertTrue("中心应该比邻像素亮（mid=$mid, left=$left）", mid > left)
        assertTrue("邻像素应该被染亮（left=$left）", left > 0)
        assertEquals("左右应该对称", left, right)
        assertEquals("上下应该对称", up, down)
        assertTrue("能量应该随距离衰减（left=$left, far=$far）", left > far)
        assertEquals("远离冲激的地方应当仍是黑色", 0, redAt(center + 19, center))
    }

    @Test
    fun `one pixel wide image does not crash`() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt())
        PclGaussianBlur.convolve(pixels, width = 1, height = 2, kernel = PclGaussianBlur.gaussianKernel(5f))
        // 只有两个像素时上下会被钳位平均，颜色不应变成 0（发黑）
        assertTrue(pixels[0] ushr 24 and 0xFF == 0xFF)
        assertTrue(pixels[1] ushr 24 and 0xFF == 0xFF)
    }
}
