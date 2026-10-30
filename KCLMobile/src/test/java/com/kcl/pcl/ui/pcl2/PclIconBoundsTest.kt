package com.kcl.pcl.ui.pcl2

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.kcl.pcl.ui.pcl2.components.PclIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * 图标包围盒回归测试。
 *
 * `pclVector` 拿路径的**紧密包围盒**当视口（对应 WPF 的 `Stretch="Uniform"`）。
 * 只要包围盒某一边偏小，图标就会被按错误长宽比缩放，还会被 Compose `Icon`
 * 内部的 `clipToBounds` 切掉多出来的那一条边 ——
 * 「搜索放大镜被切了一节」正是这么来的：`MySearchBox` 的放大镜实测
 * 视口宽 **888.59** 而高 **1023.18**，少掉的 134.54 恰好是那个圆环左侧圆弧的
 * 端点 x。原因是对称圆弧在数学上 `a == 0`，而 Float 累加残留的 ±1 ULP
 * （1.5e-5）越过了原先 `1e-6` 的绝对阈值，于是求根公式除一个极小数、
 * 吐出 ±2e7 的假根，真正的极值点 t = 0.5 被丢掉。
 *
 * 断言用 PCL 原始 SVG 的真实几何：外圆直径 918.72、联动长方形手柄，
 * 整体紧密包围盒约 1023.14 × 1023.18。
 */
class PclIconBoundsTest {

    @Test
    fun searchIconViewportIsTheFullPathBounds() {
        val iv = PclIcons.search
        // 宽高都必须接近 1023.14；此前宽只有 888.59（差 134.5）
        assertTrue(
            "viewportWidth=${iv.viewportWidth} 明显偏小，说明圆弧极值点又漏了",
            abs(iv.viewportWidth - 1023.14f) < 1f,
        )
        assertTrue(
            "viewportHeight=${iv.viewportHeight} 偏离预期",
            abs(iv.viewportHeight - 1023.18f) < 1f,
        )
        // 视口必须是正方形（路径本身就是方形的），宽高差不能超过 0.1
        assertTrue(
            "视口不再是正方形：${iv.viewportWidth} x ${iv.viewportHeight}",
            abs(iv.viewportWidth - iv.viewportHeight) < 0.1f,
        )
        // 平移应当在原点附近（紧密包围盒已经归零，groups 只做 −bounds.left 的微调）
        assertEquals("root.translationX", 0f, iv.root.translationX, 0.05f)
        assertEquals("root.translationY", 0f, iv.root.translationY, 0.05f)
    }

    @Test
    fun everyIconHasSaneViewport() {
        val problems = mutableListOf<String>()
        var checked = 0
        for (m in PclIcons::class.java.methods) {
            if (m.parameterCount != 0 || m.returnType != ImageVector::class.java) continue
            val iv = runCatching { m.invoke(PclIcons) as ImageVector }.getOrNull() ?: continue
            checked++
            val w = iv.viewportWidth
            val h = iv.viewportHeight
            if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) {
                problems += "${iv.name}: 视口非法 ${w} x ${h}"
                continue
            }
            if (!iv.defaultWidth.value.isFinite() || iv.defaultWidth.value <= 0f ||
                !iv.defaultHeight.value.isFinite() || iv.defaultHeight.value <= 0f
            ) {
                problems += "${iv.name}: defaultWidth/Height 非法 " +
                    "${iv.defaultWidth.value} x ${iv.defaultHeight.value}"
            }
            if (abs(iv.root.translationX) > max(w, h) || abs(iv.root.translationY) > max(w, h)) {
                problems += "${iv.name}: 组平移异常 (${iv.root.translationX}, ${iv.root.translationY})"
            }
        }
        println("PclIcons 共检查 $checked 个图标")
        assertTrue("反射没抓到图标，检查数 = $checked", checked >= 50)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * 通用对拍：把每个图标的路径**只靠密集采样**重算一遍包围盒（512 段），
     * 跟 `viewportWidth/Height`（解析极值算出来的）比较。
     *
     * 这是对所有图标生效的回归网：只要 `tightBounds` 的解析极值再漏一个点，
     * 视口就会比采样包围盒小，测试立刻红。含圆弧（`A`）的图标跳过 ——
     * 测试里没有实现椭圆参数式，跳过比误报好。
     */
    @Test
    fun everyIconViewportMatchesDenseSampling() {
        val problems = mutableListOf<String>()
        var checked = 0
        var skipped = 0
        for (m in PclIcons::class.java.methods) {
            if (m.parameterCount != 0 || m.returnType != ImageVector::class.java) continue
            val iv = runCatching { m.invoke(PclIcons) as ImageVector }.getOrNull() ?: continue
            if (iv.hasArc()) { skipped++; continue }
            checked++
            val b = Bounds()
            walk(iv.root, 0f, 0f, b)
            if (!b.valid) continue
            val tol = max(2f, 0.006f * max(iv.viewportWidth, iv.viewportHeight))
            if (abs(iv.viewportWidth - b.width) > tol || abs(iv.viewportHeight - b.height) > tol) {
                problems += "${iv.name}: viewport=${iv.viewportWidth}x${iv.viewportHeight} " +
                    "但密集采样只有 ${b.width}x${b.height}"
            }
        }
        println("对拍 $checked 个图标（跳过含圆弧的 $skipped 个）")
        assertTrue("对拍数太少：$checked", checked >= 20)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private class Bounds {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val width get() = maxX - minX
        val height get() = maxY - minY
        val valid get() = maxX >= minX && maxY >= minY

        fun acc(x: Float, y: Float) {
            if (x.isFinite() && y.isFinite()) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
    }

    private fun PathNode.isArcNode(): Boolean = when (this) {
        is PathNode.ArcTo, is PathNode.RelativeArcTo -> true
        else -> false
    }

    private fun ImageVector.hasArc(): Boolean {
        var found = false
        fun scan(n: VectorNode) {
            if (found) return
            when (n) {
                is VectorGroup -> for (c in n) scan(c)
                is VectorPath -> if (n.pathData.any { it.isArcNode() }) found = true
                else -> Unit
            }
        }
        scan(root)
        return found
    }

    private fun walk(node: VectorNode, dx: Float, dy: Float, out: Bounds) {
        when (node) {
            is VectorGroup -> {
                val nx = dx + node.translationX
                val ny = dy + node.translationY
                for (child in node) walk(child, nx, ny, out)
            }

            is VectorPath -> samplePath(node.pathData, dx, dy, out)
            else -> Unit
        }
    }

    private fun samplePath(nodes: List<PathNode>, dx: Float, dy: Float, out: Bounds) {
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        for (n in nodes) {
            when (n) {
                is PathNode.MoveTo -> {
                    cx = n.x; cy = n.y; startX = cx; startY = cy; out.acc(cx + dx, cy + dy)
                }

                is PathNode.RelativeMoveTo -> {
                    cx += n.dx; cy += n.dy; startX = cx; startY = cy; out.acc(cx + dx, cy + dy)
                }

                is PathNode.LineTo -> { cx = n.x; cy = n.y; out.acc(cx + dx, cy + dy) }
                is PathNode.RelativeLineTo -> { cx += n.dx; cy += n.dy; out.acc(cx + dx, cy + dy) }
                is PathNode.HorizontalTo -> { cx = n.x; out.acc(cx + dx, cy + dy) }
                is PathNode.RelativeHorizontalTo -> { cx += n.dx; out.acc(cx + dx, cy + dy) }
                is PathNode.VerticalTo -> { cy = n.y; out.acc(cx + dx, cy + dy) }
                is PathNode.RelativeVerticalTo -> { cy += n.dy; out.acc(cx + dx, cy + dy) }

                is PathNode.CurveTo -> {
                    cubic(cx, cy, n.x1, n.y1, n.x2, n.y2, n.x3, n.y3, dx, dy, out)
                    cx = n.x3; cy = n.y3
                }

                is PathNode.RelativeCurveTo -> {
                    val x1 = cx + n.dx1; val y1 = cy + n.dy1
                    val x2 = cx + n.dx2; val y2 = cy + n.dy2
                    val x3 = cx + n.dx3; val y3 = cy + n.dy3
                    cubic(cx, cy, x1, y1, x2, y2, x3, y3, dx, dy, out)
                    cx = x3; cy = y3
                }

                is PathNode.QuadTo -> {
                    quad(cx, cy, n.x1, n.y1, n.x2, n.y2, dx, dy, out)
                    cx = n.x2; cy = n.y2
                }

                is PathNode.RelativeQuadTo -> {
                    val x1 = cx + n.dx1; val y1 = cy + n.dy1
                    val x2 = cx + n.dx2; val y2 = cy + n.dy2
                    quad(cx, cy, x1, y1, x2, y2, dx, dy, out)
                    cx = x2; cy = y2
                }

                is PathNode.Close -> { cx = startX; cy = startY; out.acc(cx + dx, cy + dy) }

                // 反射型控制点只在描边图标里出现，这里按「直接落到终点」粗略处理
                is PathNode.ReflectiveCurveTo -> { cx = n.x2; cy = n.y2; out.acc(cx + dx, cy + dy) }
                is PathNode.RelativeReflectiveCurveTo -> {
                    cx += n.dx2; cy += n.dy2; out.acc(cx + dx, cy + dy)
                }

                is PathNode.ReflectiveQuadTo -> { cx = n.x; cy = n.y; out.acc(cx + dx, cy + dy) }
                is PathNode.RelativeReflectiveQuadTo -> {
                    cx += n.dx; cy += n.dy; out.acc(cx + dx, cy + dy)
                }

                else -> Unit
            }
        }
    }

    private fun cubic(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        dx: Float, dy: Float, out: Bounds,
    ) {
        for (i in 0..SAMPLES) {
            val t = i.toFloat() / SAMPLES
            val u = 1f - t
            val x = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
            val y = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
            out.acc(x + dx, y + dy)
        }
    }

    private fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
        dx: Float, dy: Float, out: Bounds,
    ) {
        for (i in 0..SAMPLES) {
            val t = i.toFloat() / SAMPLES
            val u = 1f - t
            out.acc(u * u * x0 + 2f * u * t * x1 + t * t * x2 + dx, u * u * y0 + 2f * u * t * y1 + t * t * y2 + dy)
        }
    }

    private companion object {
        const val SAMPLES = 512
    }
}
