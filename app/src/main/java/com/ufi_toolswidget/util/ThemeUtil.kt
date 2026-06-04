package com.ufi_toolswidget.util

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
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
        val accentSecondary = ThemeColors.accentSecondary(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 设备名称（标题级，强调色）──
        activity.findViewById<TextView>(R.id.main_tv_model)?.setTextColor(accent)

        // ── 版本副标题（注释灰字）──
        activity.findViewById<TextView>(R.id.main_tv_subtitle)?.setTextColor(textSecondary)

        // ── 设置入口：图标强调色 + 标签主色 ──
        activity.findViewById<ImageView>(R.id.btn_settings_icon)?.setColorFilter(accent)
        activity.findViewById<TextView>(R.id.btn_settings_label)?.setTextColor(textPrimary)

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
            // 硬件参数内所有文字：调深一点，使用 Primary 色
            applyTextColors(cardDevice, textPrimary, textPrimary) 
        }

        // ── 检查更新按钮（重构后的容器） ──
        activity.findViewById<View>(R.id.btn_check_update_text)?.let { v ->
            v.background = makeCardBg(accent, 12f)
            if (v is TextView) {
                v.setTextColor(0xFFFFFFFF.toInt())
                v.isClickable = false // 确保不拦截父容器触摸
            }
        }

        // ── 数据卡片背景 ──
        activity.findViewById<View>(R.id.card_network)?.background = makeCardBg(cardBg)
    }

    /**
     * 对 AppSettingsActivity 布局应用当前主题色（文字 + 分段控件由 Activity 自行管理）。
     */
    fun applyToAppSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyTextColorsToContainer(root, textPrimary, textSecondary, accent, cardBg)

        // "应用" 按钮背景
        activity.findViewById<MaterialButton>(R.id.btn_apply_custom_color)?.apply {
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(0xFFFFFFFF.toInt())
        }
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

    /**
     * 对二级页面通用主题应用：文字色、返回按钮图标。
     * 各子 Activity 可额外调用控件级方法来处理 ToggleGroup/CheckBox/TextInputLayout 等。
     */
    fun applyToSecondaryPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)

        // 统一处理检查更新按钮（如果存在）
        activity.findViewById<View>(R.id.btn_check_update_text)?.let { v ->
            v.background = makeCardBg(accent, 12f)
            if (v is TextView) {
                v.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        // 处理可能的链接文字
        listOf(R.id.tv_github_link, R.id.tv_thanks_link)
            .mapNotNull { activity.findViewById<TextView>(it) }
            .forEach { it.setTextColor(accent) }
            
    }

    /** 对表单类页面（配置修改、初始化设置）应用主题：文字 + TextInputLayout + 保存按钮 */
    fun applyToFormPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)
        val dividerColor = ThemeColors.divider(ctx)
        val isDark = ThemeColors.isDark(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)

        // 统一处理主要提交按钮（保存配置、初始化开始）
        listOf(R.id.btn_save_text, R.id.btn_setup_confirm_text)
            .forEach { id ->
                activity.findViewById<View>(id)?.let { v ->
                    v.background = makeCardBg(accent, 12f)
                    if (v is TextView) {
                        v.setTextColor(0xFFFFFFFF.toInt())
                    }
                }
            }

        // 跳过按钮
        activity.findViewById<View>(R.id.tv_skip)?.let { v ->
            if (v is TextView) v.setTextColor(textSecondary)
        }

        // TextInputLayout 描边 (兼容旧版或其他页面)
        applyTextInputTheme(root, accent, textSecondary)

        // 新式输入框 (FrameLayout + EditText) 背景着色
        val inputBgColor = if (isDark) 0xFF333333.toInt() else 0xFFF2F2F2.toInt()
        listOf(R.id.container_device_address, R.id.container_token)
            .mapNotNull { activity.findViewById<View>(it) }
            .forEach { it.backgroundTintList = ColorStateList.valueOf(inputBgColor) }

        // EditText Hint 着色
        val hintColor = ColorStateList.valueOf(textSecondary)
        listOf(R.id.et_device_address, R.id.et_token)
            .mapNotNull { activity.findViewById<TextView>(it) }
            .forEach { (it as? android.widget.EditText)?.setHintTextColor(hintColor) }
            
        // 查找并处理分隔线
        findAndThemeDividers(root, dividerColor)
    }

    /** 递归查找并处理分隔线（基于 alpha 0.1/0.12 且高度 1dp 的 View） */
    private fun findAndThemeDividers(root: ViewGroup?, color: Int) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                findAndThemeDividers(child, color)
            } else if (child.id == View.NO_ID && child.alpha < 0.2f && child.height <= 2 * density) {
                // 可能是分隔线
                child.setBackgroundColor(color)
            }
        }
    }

    /** 对小组件设置页应用主题：文字 + CheckBox + TextInputLayout */
    fun applyToWidgetSettingsPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)

        // CheckBox
        applyCheckBoxesTheme(root, accent, textPrimary)

        // TextInputLayout
        applyTextInputTheme(root, accent, textSecondary)
    }

    // ==================== 控件级辅助 ====================

    /** 给 ToggleGroup 中的每个子按钮设置描边色和文字色 */
    private fun applyToggleGroupTheme(toggleGroup: MaterialButtonToggleGroup?, accent: Int, textColor: Int) {
        if (toggleGroup == null) return
        for (i in 0 until toggleGroup.childCount) {
            val child = toggleGroup.getChildAt(i)
            if (child is MaterialButton) {
                child.strokeColor = ColorStateList.valueOf(accent)
                child.setTextColor(textColor)
            }
        }
    }

    /** 遍历容器中所有 MaterialCheckBox，设置勾选框和文字主题色 */
    private fun applyCheckBoxesTheme(root: ViewGroup?, accent: Int, textColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is CheckBox) {
                child.buttonTintList = ColorStateList.valueOf(accent)
                child.setTextColor(textColor)
            } else if (child is ViewGroup) {
                applyCheckBoxesTheme(child, accent, textColor)
            }
        }
    }

    /** 遍历容器中所有 TextInputLayout，设置描边色和提示文字色 */
    private fun applyTextInputTheme(root: ViewGroup?, accent: Int, hintColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is TextInputLayout) {
                child.setBoxStrokeColorStateList(ColorStateList.valueOf(accent))
                child.hintTextColor = ColorStateList.valueOf(hintColor)
                child.defaultHintTextColor = ColorStateList.valueOf(hintColor)
            } else if (child is ViewGroup) {
                applyTextInputTheme(child, accent, hintColor)
            }
        }
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
        val density = root.resources.displayMetrics.density
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
                if (child.textSize > 20f * density) {
                    child.setTextColor(textPrimary)
                } else if (child.textSize <= 12.5f * density) {
                    child.setTextColor(textSecondary)
                }
            }
            // 图标 ImageView 着色
            if (child is ImageView) {
                // 跳过大图标（如关于页的应用图标，通常 > 48dp）
                val isLargeIcon = child.width > 150 || child.height > 150 || child.id == R.id.iv_app_icon
                if (!isLargeIcon) {
                    child.setColorFilter(textSecondary)
                } else {
                    child.clearColorFilter()
                }
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
        val density = root.resources.displayMetrics.density
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
                    child.textSize > 20f * density -> child.setTextColor(textPrimary)
                    child.textSize >= 14.5f * density -> child.setTextColor(textPrimary)
                    child.textSize <= 12.5f * density -> child.setTextColor(textSecondary)
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
     * 对二级页面递归着色文字和图标（通用）。
     * 规则：标题（>20sp）→ 主色，内容（14-20sp）→ 主色，注释（≤13sp）→ 副色
     */
    private fun applySecondaryTextColors(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                // 子卡片容器应用圆角背景
                try {
                    if (child.background != null && child is ViewGroup) {
                        child.background = makeCardBg(cardBg)
                    }
                } catch (_: Exception) {}
                applySecondaryTextColors(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView && child !is android.widget.Button && child.id != android.R.id.text1 && child.id != R.id.btn_check_update_text && child.id != R.id.btn_save_text && child.id != R.id.btn_setup_confirm_text) {
                // 跳过系统下拉列表项和自定义按钮文字
                when {
                    child.textSize > 20f * density -> child.setTextColor(textPrimary)
                    child.textSize <= 13.5f * density -> child.setTextColor(textSecondary)
                    else -> child.setTextColor(textPrimary)
                }
            }
            if (child is ImageView) {
                // 跳过大图标（如关于页的应用图标，通常 > 48dp）
                val isLargeIcon = child.width > 150 || child.height > 150 || child.id == R.id.iv_app_icon
                if (!isLargeIcon) {
                    child.setColorFilter(textSecondary)
                } else {
                    child.clearColorFilter()
                }
            }
            // 按钮主题应用
            if (child is android.widget.Button) {
                val mb = child as? MaterialButton
                when {
                    child.id == R.id.btn_back -> {
                        mb?.iconTint = ColorStateList.valueOf(textPrimary)
                    }
                    (mb?.strokeWidth ?: 0) > 0 -> {
                        // 描边按钮 (OutlinedButton)
                        child.setTextColor(textPrimary)
                        mb?.strokeColor = ColorStateList.valueOf(textSecondary)
                        mb?.iconTint = ColorStateList.valueOf(accent)
                    }
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
        val density = parent.resources.displayMetrics.density
        for (child in parent.children) {
            if (child is TextView && child.textSize <= 14f * density) {
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
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                applyTextColors(child, textPrimary, textSecondary)
            }
            if (child is TextView) {
                // 小字 → 辅色，大字 → 主色
                if (child.textSize <= 14f * density) {
                    child.setTextColor(textSecondary)
                } else {
                    child.setTextColor(textPrimary)
                }
            }
        }
    }

    /** 创建圆角卡片背景 */
    private fun makeCardBg(color: Int, radius: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }
}
