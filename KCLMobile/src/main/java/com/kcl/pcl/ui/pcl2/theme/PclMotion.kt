/*
 * KCL Mobile — PCL2 UI port
 *
 * PCL2 的页面动效参数。
 *
 * 全部取自源码，未做主观发挥：
 *   Controls/MyPageRight.vb  TriggerEnterAnimation / TriggerExitAnimation
 *   Controls/MyPageLeft.vb   TriggerShowAnimation  / TriggerHideAnimation
 *   缓动函数对应 Modules/Base 里的 AniEaseOutFluent / AniEaseOutBack / AniEaseInFluent
 *   （WPF 的缓动是自定义实现，这里等价映射到三次贝塞尔）
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** PCL2 的动效时长与缓动。 */
object PclMotion {

    // ------------------------------------------------------------ 缓动

    /** `AniEaseOutFluent`：起步快、收尾平缓。 */
    val easeOutFluent: Easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

    /** `AniEaseOutBack`：收尾轻微过冲，PCL 用它做入场回弹。 */
    val easeOutBack: Easing = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)

    /** `AniEaseInFluent`。 */
    val easeInFluent: Easing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

    // ------------------------------------------------------------ 右栏内容入场
    // MyPageRight.TriggerEnterAnimation（:392-428）：
    //   每个控件 Opacity = 0、RenderTransform = TranslateTransform(0, -16)
    //   然后 AaOpacity(+1, 100ms)、AaTranslateY(+5, 250ms)、AaTranslateY(+11, 350ms, EaseOutBack)
    //   net Y: -16 → -11 → 0；每个控件 Delay += 25
    //   滚动条 RenderTransform = TranslateTransform(10, 0) → AaTranslateX(-10, 350ms, EaseOutFluent)

    val pageEnterOffsetY: Dp = (-16).dp
    const val PAGE_ENTER_DURATION = 350
    const val PAGE_ENTER_FADE_DURATION = 100
    const val PAGE_ENTER_STAGGER = 25

    val scrollBarEnterOffsetX: Dp = 10.dp
    const val SCROLLBAR_ENTER_DURATION = 350

    // ------------------------------------------------------------ 右栏内容退场
    // MyPageRight.TriggerExitAnimation（:431-452）：
    //   AaOpacity(-1, 70ms)、AaTranslateY(-6, 70ms)；Delay += 15
    //   滚动条 AaTranslateX(+10, 90ms, EaseInFluent)

    val pageExitOffsetY: Dp = (-6).dp
    const val PAGE_EXIT_DURATION = 70
    const val PAGE_EXIT_STAGGER = 15
    const val SCROLLBAR_EXIT_DURATION = 90

    // ------------------------------------------------------------ 左栏条目入场
    // MyPageLeft.TriggerShowAnimation（AnimatedControl 分支）：
    //   Element.Opacity = 0、RenderTransform = TranslateTransform(-25, 0)
    //   AaOpacity(1，TextBlock 为 0.6, 100ms, Delay, EaseOutFluent-Weak)
    //   AaTranslateX(+5, 200ms, Delay, EaseOutFluent)、AaTranslateX(+20, 300ms, Delay, EaseOutBack)
    //   net X: -25 → -20 → 0；Delay += Math.Max(15 - Id, 7) * 2
    //   退场：AaOpacity(-1, 50ms, 70/Count*Id)、AaTranslateX(-6, 50ms, ...)

    val leftEnterOffsetX: Dp = (-25).dp
    const val LEFT_ENTER_FADE_DURATION = 100
    /** 源码是 200ms + 300ms 两段，合计 500ms；这里用单段 400ms + EaseOutBack 近似。 */
    const val LEFT_ENTER_SLIDE_DURATION = 400
    /** 分组标题（TextBlock）的入场透明度只到 0.6。 */
    const val LEFT_ENTER_LABEL_ALPHA = 0.6f

    val leftHideOffsetX: Dp = (-6).dp
    const val LEFT_HIDE_DURATION = 50
    const val LEFT_HIDE_TOTAL_DELAY = 70

    // ------------------------------------------------------------ 卡片折叠
    // MyCard.vb:304 —— 箭头 AaRotateTransform(..., 250ms, EaseOutFluent)
    const val CARD_SWAP_DURATION = 250

    /**
     * 左栏第 [index] 个条目的入场延迟。
     *
     * `Delay += Math.Max(15 - Id, 7) * 2`，累加后依次为
     * 0、30、58、84、108、130、150、168、184、198…（第 9 项之后每项再 +14）。
     */
    fun leftEnterDelay(index: Int): Int {
        var delay = 0
        for (i in 0 until index) delay += maxOf(15 - i, 7) * 2
        return delay
    }

    /** 左栏退场延迟：`70 / Count * Id`。 */
    fun leftHideDelay(index: Int, count: Int): Int =
        if (count <= 0) 0 else LEFT_HIDE_TOTAL_DELAY / count * index
}

/**
 * PCL2 `Modules/Base/ModAnimation.vb` 里 `AniEase*` 系列缓动函数的**原式移植**。
 *
 * PCL 的缓动不是三次贝塞尔，而是一组自定义数学公式（见源码 `#Region "缓动函数"`）：
 *
 *   - `AniEaseLinear`            : `t`
 *   - `AniEaseInFluent(p)`       : `t^p`
 *   - `AniEaseOutFluent(p)`      : `1 - (1-t)^p`
 *   - `AniEaseInBack(p)`         : `t^p · cos(1.5π(1-t))`，`p = 3 - Power*0.5`
 *   - `AniEaseOutBack(p)`        : `1 - (1-t)^p · cos(1.5πt)`
 *   - `AniEaseInCar` / `AniEaseOutCar` / `AniEaseInoutFluent`：`AniEaseInout` 组合
 *   - `AniEaseInElastic(p)`      : `t^((p-1)·0.25) · cos((p-3.5)π(1-t)^1.5)`，`p = Power+4`
 *   - `AniEaseOutElastic(p)`     : `1 - (1-t)^((p-1)·0.25) · cos((p-3.5)π(1-t)^1.5)`
 *
 * 其中 `AniEasePower` 是 `Weak=2 / Middle=3 / Strong=4 / ExtraStrong=5`（默认 Middle）。
 * `t` 全程 `Clamp(0,1)`。
 *
 * `PclMotion` 里那三个 `CubicBezierEasing` 只是给**普通入场/退场**用的等价近似；
 * 需要「原封不动」对齐 PCL 的地方（比如百宝箱彩蛋），应当改用这里 [PclAniEase] 的公式。
 */
object PclAniEase {

    /** 对应 `AniEasePower`：Weak=2 / Middle=3 / Strong=4 / ExtraStrong=5。 */
    enum class Power(val v: Double) { Weak(2.0), Middle(3.0), Strong(4.0), ExtraStrong(5.0) }

    private fun clamp01(t: Double): Double = t.coerceIn(0.0, 1.0)

    /** `AniEaseLinear`：线性。 */
    fun linear(t: Double): Double = clamp01(t)

    /** `AniEaseInFluent`：`t^p`。 */
    fun inFluent(t: Double, power: Power = Power.Middle): Double = clamp01(t).pow(power.v)

    /** `AniEaseOutFluent`：`1 - (1-t)^p`。 */
    fun outFluent(t: Double, power: Power = Power.Middle): Double =
        1.0 - (1.0 - clamp01(t)).pow(power.v)

    /** `AniEaseInoutFluent`：`AniEaseInout` 组合（In 与 Out 各占 [middle]）。 */
    fun inoutFluent(t: Double, power: Power = Power.Middle, middle: Double = 0.5): Double =
        inout(t, power, middle)

    /** `AniEaseInBack`：`t^p · cos(1.5π(1-t))`，`p = 3 - Power*0.5`。 */
    fun inBack(t: Double, power: Power = Power.Middle): Double {
        val x = clamp01(t)
        val p = 3.0 - power.v * 0.5
        return x.pow(p) * cos(1.5 * PI * (1.0 - x))
    }

    /** `AniEaseOutBack`：`1 - (1-t)^p · cos(1.5πt)`。 */
    fun outBack(t: Double, power: Power = Power.Middle): Double {
        val x = clamp01(t)
        val p = 3.0 - power.v * 0.5
        return 1.0 - (1.0 - x).pow(p) * cos(1.5 * PI * x)
    }

    /** `AniEaseInCar`：回弹开始 + 短平滑结束（`Middle=0.7`）。 */
    fun inCar(t: Double, power: Power = Power.Middle, middle: Double = 0.7): Double =
        inout(t, power, middle, useBackForIn = true)

    /** `AniEaseOutCar`：短平滑开始 + 回弹结束（`Middle=0.3`）。 */
    fun outCar(t: Double, power: Power = Power.Middle, middle: Double = 0.3): Double =
        inout(t, power, middle, useBackForOut = true)

    /** `AniEaseInElastic`：`t^((p-1)·0.25) · cos((p-3.5)π(1-t)^1.5)`，`p = Power+4`。 */
    fun inElastic(t: Double, power: Power = Power.Middle): Double {
        val x = clamp01(t)
        val p = power.v + 4.0
        return x.pow((p - 1.0) * 0.25) * cos((p - 3.5) * PI * (1.0 - x).pow(1.5))
    }

    /** `AniEaseOutElastic`：`1 - (1-t)^((p-1)·0.25) · cos((p-3.5)π(1-t)^1.5)`。 */
    fun outElastic(t: Double, power: Power = Power.Middle): Double {
        val x = 1.0 - clamp01(t)
        val p = power.v + 4.0
        return 1.0 - x.pow((p - 1.0) * 0.25) * cos((p - 3.5) * PI * (1.0 - x).pow(1.5))
    }

    /**
     * `AniEaseInout` 组合：前半段用 In、后半段用 Out，在 [middle] 处衔接。
     *
     * 源码：
     * ```
     * If t < EaseInPercent Then
     *     Return EaseInPercent * EaseIn.GetValue(t / EaseInPercent)
     * Else
     *     Return (1 - EaseInPercent) * EaseOut.GetValue((t - EaseInPercent) / (1 - EaseInPercent)) + EaseInPercent
     * End If
     * ```
     */
    private fun inout(
        t: Double,
        power: Power,
        middle: Double,
        useBackForIn: Boolean = false,
        useBackForOut: Boolean = false,
    ): Double {
        val x = clamp01(t)
        return if (x < middle) {
            val v = if (useBackForIn) inBack(x / middle, power) else inFluent(x / middle, power)
            middle * v
        } else {
            val v = if (useBackForOut) outBack((x - middle) / (1.0 - middle), power)
            else outFluent((x - middle) / (1.0 - middle), power)
            (1.0 - middle) * v + middle
        }
    }
}

/**
 * 把 [PclAniEase] 的公式包装成 Compose 的 [Easing]，让 PCL 的原式缓动曲线
 * 能直接喂给 `animate*AsState` / `Animatable` / `tween`。
 *
 * 注意：`AniEasePower.ExtraStrong = 5`，而 `MyScrollViewer.PerformVerticalOffsetDelta`
 * 用的是 `New AniEaseOutFluent(6)`（源码里直接传了 6，越过枚举范围），
 * 所以 [PclEase.outFluentSmooth] 单独按 `p = 6` 实现。
 *
 * 全部实例都是**提前构造好的单例**，动画每帧只做一次乘法/幂运算，不产生分配。
 */
object PclEase {

    private fun of(f: (Double) -> Double): Easing = Easing { t -> f(t.toDouble()).toFloat() }

    /** `AniEaseLinear`。 */
    val linear: Easing = of(PclAniEase::linear)

    // ---------------------------------------------------------- Fluent

    /** `AniEaseInFluent`（默认 Middle）。 */
    val inFluent: Easing = of { PclAniEase.inFluent(it) }

    /** `AniEaseOutFluent`（默认 Middle）。 */
    val outFluent: Easing = of { PclAniEase.outFluent(it) }

    /** `AniEaseInFluent(Weak)`。 */
    val inFluentWeak: Easing = of { PclAniEase.inFluent(it, PclAniEase.Power.Weak) }

    /** `AniEaseOutFluent(Weak)`。 */
    val outFluentWeak: Easing = of { PclAniEase.outFluent(it, PclAniEase.Power.Weak) }

    /** `AniEaseOutFluent(Strong)`。 */
    val outFluentStrong: Easing = of { PclAniEase.outFluent(it, PclAniEase.Power.Strong) }

    /** `AniEaseOutFluent(ExtraStrong)`。 */
    val outFluentExtraStrong: Easing = of { PclAniEase.outFluent(it, PclAniEase.Power.ExtraStrong) }

    /** `AniEaseOutFluent(6)` —— `MyScrollViewer` 平滑滚动专用（`1-(1-t)^6`）。 */
    val outFluentSmooth: Easing = of { 1.0 - (1.0 - it.coerceIn(0.0, 1.0)).pow(6.0) }

    /** `AniEaseInoutFluent(Strong, 0.3)` —— `MyExtraButton` 涟漪扩散专用。 */
    val inoutFluentStrongShort: Easing = of {
        PclAniEase.inoutFluent(it, PclAniEase.Power.Strong, 0.3)
    }

    // ---------------------------------------------------------- Back

    /** `AniEaseOutBack`（默认 Middle）。 */
    val outBack: Easing = of { PclAniEase.outBack(it) }

    /** `AniEaseOutBack(Weak)` —— 对话框/勾选回弹专用。 */
    val outBackWeak: Easing = of { PclAniEase.outBack(it, PclAniEase.Power.Weak) }

    /** `AniEaseInBack(Weak)` —— 镐子蓄力专用。 */
    val inBackWeak: Easing = of { PclAniEase.inBack(it, PclAniEase.Power.Weak) }

    // ---------------------------------------------------------- Elastic

    /** `AniEaseOutElastic(Weak)` —— 镐子回弹专用。 */
    val outElasticWeak: Easing = of { PclAniEase.outElastic(it, PclAniEase.Power.Weak) }
}

/**
 * 左栏条目的逐个入场动画（`MyPageLeft.TriggerShowAnimation`）。
 *
 * 只在左栏整个页面被重建时重播——所以调用方要用 `key(nav.sectionToken)` 把整个左栏包起来。
 * 点击左栏条目本身不会重建左栏，这与 PCL 一致（PCL 只重建 `PanMainLeft.Child`）。
 *
 * @param index 该条目在同栏里的序号，决定延迟；分组标题也占一个序号。
 * @param labelAlpha 分组标题传 [PclMotion.LEFT_ENTER_LABEL_ALPHA]，普通条目传 1f。
 */
@Composable
fun PclStaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    labelAlpha: Float = 1f,
    fadeDuration: Int = PclMotion.LEFT_ENTER_FADE_DURATION,
    slideDuration: Int = PclMotion.LEFT_ENTER_SLIDE_DURATION,
    offsetX: Dp = PclMotion.leftEnterOffsetX,
    content: @Composable () -> Unit,
) {
    val delay = PclMotion.leftEnterDelay(index)
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val fade by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(
            durationMillis = fadeDuration,
            delayMillis = delay,
            easing = PclMotion.easeOutFluent,
        ),
        label = "pclLeftFade",
    )
    val slide by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(
            durationMillis = slideDuration,
            delayMillis = delay,
            easing = PclMotion.easeOutBack,
        ),
        label = "pclLeftSlide",
    )

    Box(
        modifier = modifier.graphicsLayer {
            alpha = fade * labelAlpha
            translationX = offsetX.toPx() * (1f - slide)
        },
    ) {
        content()
    }
}
