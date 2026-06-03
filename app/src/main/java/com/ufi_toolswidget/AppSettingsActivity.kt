package com.ufi_toolswidget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil

class AppSettingsActivity : AppCompatActivity() {

    private data class ThemeCard(val index: Int, val previewId: Int, val cardId: Int, val labelId: Int, val checkId: Int)

    private val themeCards = listOf(
        ThemeCard(0, R.id.preview_default, R.id.card_theme_default, R.id.label_default, R.id.check_default),
        ThemeCard(1, R.id.preview_blue, R.id.card_theme_blue, R.id.label_blue, R.id.check_blue),
        ThemeCard(2, R.id.preview_mint, R.id.card_theme_mint, R.id.label_mint, R.id.check_mint),
        ThemeCard(3, R.id.preview_purple, R.id.card_theme_purple, R.id.label_purple, R.id.check_purple),
        ThemeCard(4, R.id.preview_orange, R.id.card_theme_orange, R.id.label_orange, R.id.check_orange),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)

        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToAppSettingsActivity(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        initThemeToggle()
        initColorThemeSelector()
        initCustomColor()
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToAppSettingsActivity(this)
    }

    // ==================== 软件主题 ====================
    private fun initThemeToggle() {
        val toggleApp = findViewById<MaterialButtonToggleGroup>(R.id.toggle_app_theme)
        when (SPUtil.getAppTheme(this)) {
            "light" -> toggleApp.check(R.id.btn_app_theme_light)
            "dark" -> toggleApp.check(R.id.btn_app_theme_dark)
            else -> toggleApp.check(R.id.btn_app_theme_system)
        }
        toggleApp.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newTheme = when (checkedId) {
                R.id.btn_app_theme_light -> "light"
                R.id.btn_app_theme_dark -> "dark"
                else -> "system"
            }
            val old = SPUtil.getAppTheme(this)
            if (old != newTheme) {
                SPUtil.setAppTheme(this, newTheme)
                AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
            }
        }
    }

    // ==================== 颜色主题选择 ====================
    private fun initColorThemeSelector() {
        val current = SPUtil.getColorThemeIndex(this)
        refreshAllCards(current)
        showCustomPanel(current == -1)

        themeCards.forEach { card ->
            val cardView = findViewById<LinearLayout>(card.cardId)
            cardView.setOnClickListener {
                SPUtil.setColorThemeIndex(this, card.index)
                refreshAllCards(card.index)
                showCustomPanel(false)
                BackgroundUtil.applyWindowBackground(this)
            }
        }

        // 自定义卡片点击
        findViewById<LinearLayout>(R.id.card_theme_custom).setOnClickListener {
            SPUtil.setColorThemeIndex(this, -1)
            refreshAllCards(-1)
            showCustomPanel(true)
            BackgroundUtil.applyWindowBackground(this)
        }
    }

    private fun showCustomPanel(show: Boolean) {
        findViewById<View>(R.id.custom_color_panel).visibility = if (show) View.VISIBLE else View.GONE
    }

    // ==================== 自定义颜色 ====================
    private fun initCustomColor() {
        val swatch = findViewById<View>(R.id.custom_color_swatch)
        val etColor = findViewById<EditText>(R.id.et_custom_color)

        // 初始化显示当前自定义色值
        val accentL = SPUtil.getCustomAccentLight(this)
        swatch.setBackgroundColor(accentL)
        etColor.setText(String.format("#%06X", 0xFFFFFF and accentL))

        // 输入时实时预览
        etColor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) tryParseAndPreview(etColor, swatch)
        }

        // 应用按钮
        findViewById<View>(R.id.btn_apply_custom_color).setOnClickListener {
            if (tryParseAndPreview(etColor, swatch)) {
                val color = parseColor(etColor.text.toString().trim())
                if (color != null) {
                    // 浅色和深色都用同一色系，深色模式自动变亮
                    val darkColor = adjustBrightness(color, 0.85f)
                    SPUtil.setCustomAccentLight(this, color)
                    SPUtil.setCustomAccentDark(this, darkColor)
                    SPUtil.setColorThemeIndex(this, -1)
                    refreshAllCards(-1)
                    BackgroundUtil.applyWindowBackground(this)
                    ThemeUtil.applyToAppSettingsActivity(this)
                    Toast.makeText(this, "自定义颜色已应用", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "颜色格式无效，请输入如 #FF5722 的格式", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tryParseAndPreview(etColor: EditText, swatch: View): Boolean {
        val color = parseColor(etColor.text.toString().trim())
        if (color != null) {
            swatch.setBackgroundColor(color)
            return true
        }
        return false
    }

    private fun parseColor(str: String): Int? {
        return try {
            Color.parseColor(str)
        } catch (e: Exception) {
            null
        }
    }

    /** 调整颜色亮度，factor>1 变亮，<1 变暗 */
    private fun adjustBrightness(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = (color and 0xFF)
        val nr = (r * factor).toInt().coerceIn(0, 255)
        val ng = (g * factor).toInt().coerceIn(0, 255)
        val nb = (b * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    // ==================== 卡片刷新 ====================
    private fun refreshAllCards(selectedIndex: Int) {
        // 预设主题卡片
        themeCards.forEach { card ->
            val preview = findViewById<View>(card.previewId)
            val label = findViewById<TextView>(card.labelId)
            val checkImg = findViewById<View>(card.checkId)

            val palette = ThemeColors.getById(card.index)
            setPreviewColors(preview, palette)
            label.text = palette.name
            checkImg.visibility = if (card.index == selectedIndex) View.VISIBLE else View.GONE
        }

        // 自定义卡片
        val customPreview = findViewById<View>(R.id.preview_custom)
        val customLabel = findViewById<TextView>(R.id.label_custom)
        val customCheck = findViewById<View>(R.id.check_custom)

        if (selectedIndex == -1) {
            val customPalette = ThemeColors.getById(this, -1)
            setPreviewColors(customPreview, customPalette)
            customCheck.visibility = View.VISIBLE
        } else {
            // 显示当前存储的自定义色预览
            val accentL = SPUtil.getCustomAccentLight(this)
            val accentD = SPUtil.getCustomAccentDark(this)
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * resources.displayMetrics.density
                setColors(intArrayOf(accentL, accentD))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            customPreview.background = gd
            customCheck.visibility = View.GONE
        }
        customLabel.text = "自定义"
    }

    private fun setPreviewColors(view: View, palette: ThemeColors.Palette) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * resources.displayMetrics.density
            setColors(intArrayOf(palette.accentLight, palette.accentSecondaryLight, palette.pageBgLight))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
        }
        view.background = gd
    }
}
