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

package com.kcl.pcl.ui.pcl2.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kcl.pcl.ui.pcl2.PclHintHost
import com.kcl.pcl.ui.pcl2.theme.PclAniEase
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * 百宝箱「千万别点」彩蛋 —— **整扇启动器窗口**的恶搞效果。
 *
 * ⚠️ 来源说明：PCL2 的**开源版把百宝箱整个掏空了**（`PageOtherTest.xaml.vb` 里每个函数
 * 都只剩 `Hint(GetLang("LangPageOtherTestNoUtility"))`），彩蛋本体不在源码里；
 * 网上能查到的公开描述也只到「三个『确定』按钮 → 恶搞动画」这一层。
 * 具体行为按用户描述复刻：**按下之后整扇窗口开始整活，四种效果随机抽一个**——
 *
 *   1. [Kind.Bounce]    窗口像 DVD 待机画面那样在屏幕上乱飘，碰到边缘反弹；
 *   2. [Kind.Recolor]   窗口反复变色；
 *   3. [Kind.Oscillate] 窗口反复做**纵向或横向**的来回运动（方向随机）；
 *   4. [Kind.Shake]     窗口来回抖动。
 *
 * 【动效原封不动】虽然彩蛋本体不在开源源码里，但 PCL 的**动效引擎**在
 * `Modules/Base/ModAnimation.vb` 是完整的。这里四个效果全部用 PCL 引擎的原语来驱动，
 * 参数与缓动一一对应，不做主观发挥：
 *
 *   - **移动**：对应 `AniTypeSub.X/Y` 分支 —— `If TypeOf Obj Is Window Then Window.Left/Top += Delta`。
 *     这是 PCL 里唯一真正「移动 OS 窗口」的原语，DVD 乱飘就是反复用 `AaX(FrmMain, …)` /
 *     `AaY(FrmMain, …)`。Android 没有独立 OS 窗口，所以这里等价映射成 `graphicsLayer` 的
 *     `translationX/Y`（同样是「不触发布局的视觉位移」）。
 *   - **变色**：对应 `AniType.Color` —— 用 `MyColor` 的 **RGB 加减法**逐帧累加
 *     （`NewColor = OldColor + Delta`，`ValueLast` 记录舍入余数），**不是** HSL 色相旋转。
 *     这里等价映射成 `drawWithContent` 叠一层按 RGB 通道线性扫过的着色。
 *   - **缩放**：对应 `AaScaleTransform` —— `RenderTransform` 的 `ScaleX/ScaleY` 累加，
 *     原点 `0.5/0.5`（`RenderTransformOrigin = New Point(0.5, 0.5)`）。
 *   - **缓动**：全部走 [PclAniEase]（`ModAnimation.vb` 的 `AniEase*` 原式移植），
 *     默认时长 400ms（PCL `Aa*` 的 `Optional Time = 400`）。
 *
 * 变换只影响绘制与命中测试，不改任何布局与业务状态。
 *
 * 【会一直整下去】效果播完不会自己收场，而是**立刻换下一种接着整**（见
 * [rememberPclPrankModifier] 里的接力），直到用户点一下屏幕 —— 出口由
 * `PclMainScreen` 里那层点击接收器提供（它压在窗口之上，所以彩蛋期间点哪儿都是「停」，
 * 不会误触到下面的按钮）。
 */
object PclPrank {

    /** 四种恶搞效果。 */
    enum class Kind { Bounce, Recolor, Oscillate, Shake }

    /** 当前正在播放的效果；`null` = 一切正常。 */
    var active by mutableStateOf<Kind?>(null)
        private set

    /**
     * 每次触发递增。
     *
     * 动画与「到时收场」都挂在它上面 —— 同一个效果连点两次时，靠它把动画和计时重置，
     * 否则第二次点会毫无反应。
     */
    var token by mutableIntStateOf(0)
        private set

    /** [Kind.Oscillate] 的摆动方向：true = 纵向，false = 横向。触发时随机定一次。 */
    var oscillateVertical by mutableStateOf(true)
        private set

    /** 彩蛋期间窗体后面那层「桌面」的底色。 */
    val desktopColor = Color(0xFF23262B)

    /**
     * 随机抽一种效果开始整活。
     *
     * 接力调用时（上一种播完自动换下一种）**不重复提示** —— 只有从「没在整活」
     * 变成「正在整活」的那一次才给提示，免得每几秒刷一条提示条。
     */
    fun triggerRandom() {
        val starting = active == null
        oscillateVertical = Random.nextBoolean()
        active = Kind.entries.random()
        token++
        if (starting) PclHintHost.push(START_HINT, PclHintTheme.Blue)
    }

    /** 收场 —— 由用户点屏幕触发（点击接收器在 `PclMainScreen`）。 */
    fun stop() {
        if (active == null) return
        active = null
        PclHintHost.push(PRANK_LINES.random(), PclHintTheme.Blue)
    }

    /** 每种效果播多久（毫秒）。 */
    fun durationOf(kind: Kind): Long = when (kind) {
        Kind.Bounce -> 7500L
        Kind.Recolor -> 4500L
        Kind.Oscillate -> 4500L
        Kind.Shake -> 2600L
    }
}

/**
 * 把当前彩蛋效果包成一个修饰符，套在 PCL 窗体最外层。
 *
 * 没有彩蛋时返回 [Modifier]（空实现，零开销）。
 * 内部同时负责「播完接力换下一种」—— 挂在 [PclPrank.token] 上，
 * 每轮结束时调一次 [PclPrank.triggerRandom]，于是 key 变化、`LaunchedEffect` 重启、
 * 下一轮计时开始，如此循环下去，不自己消失。
 */
@Composable
fun rememberPclPrankModifier(): Modifier {
    val kind = PclPrank.active ?: return Modifier
    val token = PclPrank.token

    // 慢环（9s）驱动「飘」和「变色」，快环（1s）驱动「摆动」和「抖动」。
    // 两个周期都取整数倍频率，循环点才不会有跳变。
    val transition = rememberInfiniteTransition(label = "pclPrank")
    val slow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "pclPrankSlow",
    )
    val fast by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "pclPrankFast",
    )

    LaunchedEffect(token) {
        // 这一段播完就换下一种：整个彩蛋在用户点屏幕之前一直进行
        delay(PclPrank.durationOf(kind))
        PclPrank.triggerRandom()
    }

    return when (kind) {
        // 1) DVD 乱飘：PCL `AniTypeSub.X/Y` 的 Window.Left/Top += Delta 原语。
        //    三角形波就是匀速段 + 折返点（撞边反弹），不是正弦（两端减速像飘不像弹）。
        PclPrank.Kind.Bounce -> Modifier.graphicsLayer {
            val s = 0.72f
            scaleX = s
            scaleY = s
            val mx = size.width * (1f - s) / 2f
            val my = size.height * (1f - s) / 2f
            translationX = -mx + 2f * mx * triangle(slow * 3f)
            translationY = -my + 2f * my * triangle(slow * 2f)
        }

        // 3) 反复纵向 / 横向运动：PCL `AniTypeSub.Y` / `.X`，方向在触发时随机定。
        PclPrank.Kind.Oscillate -> Modifier.graphicsLayer {
            val s = 0.72f
            scaleX = s
            scaleY = s
            val mx = size.width * (1f - s) / 2f
            val my = size.height * (1f - s) / 2f
            val wave = sin(fast * 2f * PI).toFloat()
            if (PclPrank.oscillateVertical) {
                translationY = my * wave
            } else {
                translationX = mx * wave
            }
        }

        // 4) 抖动：PCL 的弹簧缓动 `AniEaseOutElastic`（`1 - (1-t)^((p-1)·0.25) · cos(...)`）
        //    做幅度包络，两个方向上用不同频率的高频抖动。
        PclPrank.Kind.Shake -> Modifier.graphicsLayer {
            val s = 0.9f
            scaleX = s
            scaleY = s
            val amp = 9.dp.toPx()
            // 每个 1s 快环周期内，前 70% 做一次弹簧衰减（OutElastic），后 30% 归零再弹。
            val env = PclAniEase.outElastic(((fast % 0.7f) / 0.7f).toDouble()).toFloat()
            translationX = sin(fast * 2f * PI * 12f).toFloat() * amp * env
            translationY = cos(fast * 2f * PI * 15f).toFloat() * amp * 0.6f * env
        }

        // 2) 反复变色：PCL `AniType.Color` 的 RGB 加减法累加（非 HSL 色相旋转）。
        //    `MyColor` 逐通道加 Delta；这里等价映射为按 RGB 通道线性扫过的着色层。
        PclPrank.Kind.Recolor -> Modifier.drawWithContent {
            drawContent()
            drawRect(
                color = Color(
                    red = 0.5f + 0.5f * sin(slow * 2f * PI).toFloat(),
                    green = 0.5f + 0.5f * sin(slow * 2f * PI + 2.094f).toFloat(),
                    blue = 0.5f + 0.5f * sin(slow * 2f * PI + 4.189f).toFloat(),
                ),
                alpha = 0.22f,
            )
        }
    }
}

/**
 * 三角形波：周期 1，取值 0 → 1 → 0。
 *
 * 匀速段 + 折返点，正是 DVD 待机画面那个「撞边反弹」的运动方式
 * （正弦波会在两端减速，看着像飘，不像弹）。
 */
private fun triangle(x: Float): Float = 2f * abs(x - floor(x + 0.5f))

private const val PI = 3.1415927f

/** 彩蛋收场时的提示语。 */
private val PRANK_LINES = listOf(
    "开玩笑的，什么都没发生，也没有任何文件被删除。",
    "恭喜你发现了启动器里最没用的一个按钮。",
    "这个按钮的唯一作用，就是证明你真的会点它。",
    "三个「确定」你都要选一个，这就是人生。",
)

/** 开始整活时的提示 —— 得告诉用户怎么出去，不然只能杀进程。 */
private const val START_HINT = "彩蛋进行中，点一下屏幕就停下。"
