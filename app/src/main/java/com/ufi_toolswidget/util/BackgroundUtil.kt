package com.ufi_toolswidget.util

import android.app.Activity

/**
 * 全局窗口背景管理器。
 * 基于 ThemeColors 配色系统，自动适配浅/深色模式。
 */
object BackgroundUtil {

    fun applyWindowBackground(activity: Activity) {
        val color = ThemeColors.pageBg(activity)
        activity.window.decorView.setBackgroundColor(color)
    }
}
