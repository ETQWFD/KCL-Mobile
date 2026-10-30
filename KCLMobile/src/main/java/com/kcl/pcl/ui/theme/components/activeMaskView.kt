package com.kcl.pcl.ui.theme.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.addListener
import androidx.core.graphics.createBitmap
import kotlin.math.hypot

/**
 * 切换深/浅色时，用「圆形扩散」把整窗截图遮住：遮罩贴上去后立刻回调 [maskComplete] 换配色，
 * 动画播完再回调 [maskAnimFinish]。
 *
 * **截屏只是装饰，任何一步失败都必须照常回调 [maskComplete]** —— 否则调用方
 * （Theme.kt 的 LaunchedEffect）会永远停在旧配色上，表现为「点了深色没反应」。
 *
 * 旧实现有三个问题，都在这里修掉：
 * 1. Android 15/16 起 `PixelCopy.sourceForWindow()` 在窗口没有有效 surface 时会**同步抛**
 *    `IllegalArgumentException: Window doesn't have a backing surface!`，旧代码没有 try/catch，
 *    直接崩溃（真机 `launcher_crash.log` 即此栈）。
 * 2. 异步回调里 `result != SUCCESS` 时什么都不做，`maskComplete` 永不回调 → 主题静默不生效。
 * 3. `(this as Activity)` 强转，Context 不是 Activity 时抛 ClassCastException。
 */
fun Context.activeMaskView(
    animTime: Long = 800,
    maskComplete: () -> Unit,
    maskAnimFinish: () -> Unit
) {
    val window = (this as? Activity)?.window
    val rootView = window?.decorView?.rootView as? ViewGroup
    if (window == null || rootView == null) {
        // 拿不到窗口就别做动画了，直接换配色。
        maskComplete()
        maskAnimFinish()
        return
    }
    captureView(rootView, window) { bitmap ->
        if (bitmap == null) {
            // 截不到（窗口还没 surface / 已销毁 / 退到后台）：跳过动画，但配色照样要换。
            maskComplete()
            maskAnimFinish()
            return@captureView
        }
        val maskView = MaskView(this, bitmap)
        rootView.addView(maskView)
        maskComplete()
        maskView.animActive(animTime) {
            rootView.removeView(maskView)
            maskAnimFinish()
        }
    }
}

/** 截屏失败时最多再等几帧重试，等窗口 surface 就绪。 */
private const val CAPTURE_MAX_RETRY = 2

/** 重试间隔，约两帧。 */
private const val CAPTURE_RETRY_DELAY_MS = 48L

private fun captureView(
    view: View,
    window: Window,
    attempt: Int = 0,
    bitmapCallback: (Bitmap?) -> Unit
) {
    // PixelCopy 必须在带 Looper 的线程上调用，统一回主线程。
    if (Looper.myLooper() != Looper.getMainLooper()) {
        Handler(Looper.getMainLooper()).post { captureView(view, window, attempt, bitmapCallback) }
        return
    }
    if (!view.isAttachedToWindow || view.windowToken == null || view.width <= 0 || view.height <= 0) {
        retryOrGiveUp(view, window, attempt, bitmapCallback)
        return
    }

    val bitmap = try {
        createBitmap(view.width, view.height)
    } catch (e: Throwable) {
        null
    }
    if (bitmap == null) {
        bitmapCallback(null)
        return
    }

    val location = IntArray(2)
    view.getLocationInWindow(location)
    try {
        PixelCopy.request(
            window,
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    bitmapCallback(bitmap)
                } else {
                    retryOrGiveUp(view, window, attempt, bitmapCallback)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (e: IllegalArgumentException) {
        // 「Window doesn't have a backing surface!」以及同类参数异常：等下一帧再试。
        retryOrGiveUp(view, window, attempt, bitmapCallback)
    } catch (e: Throwable) {
        // 其余情况（如 OOM）直接放弃动画，不能再往上抛。
        bitmapCallback(null)
    }
}

private fun retryOrGiveUp(
    view: View,
    window: Window,
    attempt: Int,
    bitmapCallback: (Bitmap?) -> Unit
) {
    if (attempt >= CAPTURE_MAX_RETRY) {
        bitmapCallback(null)
        return
    }
    // 用主线程 Handler 而不是 view.post：view 未 attach 时 post 会一直挂着，回调永远不来。
    Handler(Looper.getMainLooper()).postDelayed({
        captureView(view, window, attempt + 1, bitmapCallback)
    }, CAPTURE_RETRY_DELAY_MS)
}

@SuppressLint("ViewConstructor")
private class MaskView(
    context: Context,
    private var bitmap: Bitmap
) : View(context) {
    private var maskRadius = 0f
    private val paint = Paint(ANTI_ALIAS_FLAG)

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) = with(canvas) {
        val layer = saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        drawBitmap(bitmap, 0f, 0f, null)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        paint.maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
        drawCircle(0f, height.toFloat() / 2f, maskRadius, paint)
        paint.xfermode = null
        restoreToCount(layer)
    }

    fun animActive(animTime: Long, animFinish: () -> Unit) {
        ValueAnimator.ofFloat(
            0f,
            hypot(rootView.width.toFloat(), rootView.height.toFloat())
        ).apply {
            duration = animTime
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                maskRadius = valueAnimator.animatedValue as Float
                invalidate()
            }
            addListener(onEnd = { animFinish() })
        }.start()
    }
}
