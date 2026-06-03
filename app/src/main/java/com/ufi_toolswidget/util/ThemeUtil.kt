package com.ufi_toolswidget.util

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R

/**
 * 主题色动态应用工具。
 * 将 ThemeColors 的配色方案写入到 Activity 的所有控件上。
 */
object ThemeUtil {

    /**
     * 对 Activity 的主页布局应用当前主题色。
     */
    fun applyToMainActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 设备名称（标题级，强调色）──
        activity.findViewById<TextView>(R.id.main_tv_model)?.setTextColor(accent)

        // ── 版本副标题（注释灰字）──
        activity.findViewById<TextView>(R.id.main_tv_subtitle)?.setTextColor(textSecondary)

        // ── 检查更新按钮 ──
        activity.findViewById<TextView>(R.id.btn_check_update)?.setTextColor(textPrimary)

        // ── 设置图标按钮 ──
        val btnSettings = activity.findViewById<MaterialButton>(R.id.btn_settings)
        btnSettings?.iconTint = ColorStateList.valueOf(accent)

        // ── 数据网格标签（信号/温度/CPU/内存） → 注释灰字 ──
        val gridLabels = activity.findViewById<ViewGroup>(R.id.card_network)
        applyTextColorToLabels(gridLabels, textSecondary)

        // ── 数据网格图标 → 强调色（随主题切换）──
        activity.findViewById<ImageView>(R.id.main_iv_antenna)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_temp)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_cpu)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_chip)?.setColorFilter(accent)

        // ── 数据网格数值 → 正文 ──
        activity.findViewById<TextView>(R.id.main_tv_net_signal)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_temp)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_cpu)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_mem)?.setTextColor(textPrimary)

        // ── 今日已用 / 本月累计数字 → 强调色 ──
        activity.findViewById<TextView>(R.id.main_tv_daily)?.setTextColor(accent)
        activity.findViewById<TextView>(R.id.main_tv_flow)?.setTextColor(accent)

        // ── 硬件参数区域 ──
        val cardDevice = activity.findViewById<ViewGroup>(R.id.card_device)
        if (cardDevice != null) {
            // 卡片背景
            cardDevice.background = makeCardBg(cardBg)
            // 硬件参数内所有文字
            applyTextColors(cardDevice, textPrimary, textSecondary)
        }

        // ── 数据卡片背景 ──
        activity.findViewById<View>(R.id.card_network)?.background = makeCardBg(cardBg)
    }

    /**
     * 对 AppSettingsActivity 布局应用当前主题色。
     */
    fun applyToAppSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 标题和副标题 ──
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyTextColorsToContainer(root, textPrimary, textSecondary, accent, cardBg)
    }

    /**
     * 对 SettingsActivity 布局应用当前主题色。
     */
    fun applyToSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyCardListTheme(root, textPrimary, textSecondary, accent, cardBg)
    }

    // ==================== 内部辅助 ====================

    /**
     * 递归遍历容器，根据 textSize 自动区分标题/正文/注释
     */
    private fun applyTextColorsToContainer(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                // 判断是否为卡片容器（有背景）
                val bg = child.background
                if (bg != null) {
                    try { child.background = makeCardBg(cardBg) } catch (_: Exception) {}
                }
                applyTextColorsToContainer(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView) {
                if (child.textSize > 20f) {
                    child.setTextColor(textPrimary)
                } else if (child.textSize <= 12f) {
                    child.setTextColor(textSecondary)
                }
            }
            // 图标 ImageView 着色
            if (child is ImageView) {
                child.setColorFilter(textSecondary)
            }
        }
    }

    /**
     * 对卡片列表布局（SettingsActivity）应用主题色。
     * 特点：卡片容器有 heading + description + 后箭头图标
     */
    private fun applyCardListTheme(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                val isCard = child.id != android.R.id.content && child.childCount >= 2
                if (isCard) {
                    // 尝试应用卡片背景
                    try {
                        if (child.background != null) child.background = makeCardBg(cardBg)
                    } catch (_: Exception) {}
                }
                applyCardListTheme(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView) {
                when {
                    child.textSize > 20f -> child.setTextColor(textPrimary)
                    child.textSize >= 15f -> child.setTextColor(textPrimary)
                    child.textSize <= 12f -> child.setTextColor(textSecondary)
                }
            }
            if (child is ImageView) {
                // 卡片左边的功能图标用强调色，右边箭头用次要色
                // 简单判断：有 rotation=180 的是箭头
                if (child.rotation == 180f || child.alpha < 0.5f) {
                    child.setColorFilter(textSecondary)
                } else {
                    child.setColorFilter(accent)
                }
            }
        }
    }

    /**
     * 在指定容器中递归查找所有标签级 TextView（小字），着色为 subText 色。
     * 规则：每个叶子分支的第一个小字(≤13sp) TextView 视为标签。
     */
    private fun applyTextColorToLabels(root: ViewGroup?, labelColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                colorFirstLabelInBranch(child, labelColor)
            }
        }
    }

    /** 在 ViewGroup 子树中找到第一个小字 TextView 并着色，找到后返回 true */
    private fun colorFirstLabelInBranch(parent: ViewGroup, color: Int): Boolean {
        for (child in parent.children) {
            if (child is TextView && child.textSize <= 13f) {
                child.setTextColor(color)
                return true
            }
            if (child is ViewGroup) {
                if (colorFirstLabelInBranch(child, color)) return true
            }
        }
        return false
    }

    /**
     * 遍历容器中所有 TextView，根据 textSize 自动区分主/辅色。
     */
    private fun applyTextColors(root: ViewGroup?, textPrimary: Int, textSecondary: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                applyTextColors(child, textPrimary, textSecondary)
            }
            if (child is TextView) {
                // 小字 → 辅色，大字 → 主色
                if (child.textSize <= 13f) {
                    child.setTextColor(textSecondary)
                } else {
                    child.setTextColor(textPrimary)
                }
            }
        }
    }

    /** 创建圆角卡片背景 */
    private fun makeCardBg(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 16f
        }
    }
}
