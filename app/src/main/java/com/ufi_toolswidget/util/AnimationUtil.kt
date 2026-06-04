package com.ufi_toolswidget.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import android.widget.TextView

/**
 * 全局动画工具类，提供统一的模糊起伏切换和弹窗渐变效果。
 */
object AnimationUtil {

    /**
     * 柔和更新文本内容：平滑模糊起伏渐变（无边框，带步进）。
     * Android 12+ 使用 RenderEffect + DECAL 模式。
     * 低版本使用 Alpha + Scale 回退。
     */
    fun smoothUpdateText(tv: TextView, newText: String) {
        if (tv.text == newText) {
            tv.alpha = 1f
            return
        }

        tv.animate().cancel()
        tv.alpha = 1f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 降低半径以减小边缘压力，增加步进提升丝滑度
            val targetBlur = 10f
            val blurAnim = ValueAnimator.ofFloat(0f, targetBlur)
            blurAnim.duration = 260
            blurAnim.addUpdateListener { anim ->
                val radius = anim.animatedValue as Float
                if (radius > 0.1f) {
                    tv.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
                } else {
                    tv.setRenderEffect(null)
                }
            }
            blurAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tv.text = newText
                    val unblurAnim = ValueAnimator.ofFloat(targetBlur, 0f)
                    unblurAnim.duration = 320
                    unblurAnim.addUpdateListener { anim ->
                        val radius = anim.animatedValue as Float
                        if (radius > 0.1f) {
                            tv.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
                        } else {
                            tv.setRenderEffect(null)
                        }
                    }
                    unblurAnim.start()
                }
            })
            blurAnim.start()
        } else {
            tv.animate()
                .alpha(0.3f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(260)
                .withEndAction {
                    tv.text = newText
                    tv.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(320)
                        .start()
                }
                .start()
        }
    }

    /**
     * 为 Dialog 应用背景模糊入场步进动画 (Android 12+)
     */
    fun applyDialogBlurIn(dialog: Dialog, targetBlur: Int = 110, targetDim: Float = 0.08f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            setDimAmount(0f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                addUpdateListener { anim ->
                    val ratio = anim.animatedValue as Float
                    attributes = attributes?.apply {
                        blurBehindRadius = (targetBlur * ratio).toInt()
                    }
                    setDimAmount(targetDim * ratio)
                }
                start()
            }
        }
    }

    /**
     * 为 Dialog 执行背景模糊退场步进动画 (Android 12+)，
     * 同时通过动画缩放和淡出 Dialog 内容，最后调用 onComplete。
     */
    fun applyDialogBlurOut(dialog: Dialog, onComplete: () -> Unit) {
        val window = dialog.window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || window == null) {
            onComplete()
            return
        }

        val contentView = window.findViewById<android.view.View>(android.R.id.content)
        val attrs = window.attributes
        val startBlur = attrs.blurBehindRadius
        val startDim = window.attributes.dimAmount

        // 统一动画：退场动画节奏优化
        // 缩短时长至 260ms，解决关闭过慢的问题，节奏更加轻快
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260
            interpolator = android.view.animation.AccelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val ratio = anim.animatedValue as Float
                window.attributes = window.attributes?.apply {
                    blurBehindRadius = (startBlur * ratio).toInt()
                }
                window.setDimAmount(startDim * ratio)
                
                contentView?.alpha = ratio
                contentView?.scaleX = 0.96f + 0.04f * ratio
                contentView?.scaleY = 0.96f + 0.04f * ratio
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    window.setWindowAnimations(0)
                    onComplete()
                }
            })
            start()
        }
    }

    fun applyScaleClickAnimation(view: android.view.View, onClick: () -> Unit) {
        view.stateListAnimator = null
        view.isClickable = true
        view.isFocusable = true

        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.animate().cancel()
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3.0f))
                        .start()
                    
                    if (event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height) {
                        v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onClick()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            true
        }
    }
}
