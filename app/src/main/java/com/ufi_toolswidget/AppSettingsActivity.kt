package com.ufi_toolswidget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.widget.BaseWifiWidget

class AppSettingsActivity : AppCompatActivity() {

    /** 主界面刷新间隔预设选项（秒），-1 表示自定义 */
    private val presetIntervals = listOf(
        5, 10, 15, 30, 0
    )

    private val presetChipIds = listOf(
        R.id.chip_main_5, R.id.chip_main_10, R.id.chip_main_15, R.id.chip_main_30, R.id.chip_main_off
    )

    /** 主题 Chip：chipId=容器, dotId=色点, labelId=名称 */
    private data class ThemeChip(val index: Int, val chipId: Int, val dotId: Int, val labelId: Int)

    private val themeChips = listOf(
        ThemeChip(0, R.id.chip_theme_default, R.id.dot_default, R.id.label_default),
        ThemeChip(1, R.id.chip_theme_blue,   R.id.dot_blue,   R.id.label_blue),
        ThemeChip(2, R.id.chip_theme_mint,   R.id.dot_mint,   R.id.label_mint),
        ThemeChip(3, R.id.chip_theme_purple, R.id.dot_purple, R.id.label_purple),
        ThemeChip(4, R.id.chip_theme_orange, R.id.dot_orange, R.id.label_orange),
    )

    /** 自定义 Chip */
    private val customChip = ThemeChip(-1, R.id.chip_theme_custom, R.id.dot_custom, R.id.label_custom)

    private var mainIntervalSeconds: Int = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_app_settings)

        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToAppSettingsActivity(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        initThemeToggle()
        initColorThemeSelector()
        initCustomColor()
        initMainRefreshInterval()
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToAppSettingsActivity(this)
        if (::appThemeSegments.isInitialized) refreshAppThemeSegments()
        if (::mainIntervalChips.isInitialized) refreshMainIntervalChips()
        // 重新刷新主题chip（防止ThemeUtil递归覆盖chip背景和颜色）
        val currentThemeIndex = SPUtil.getColorThemeIndex(this)
        refreshThemeChips(currentThemeIndex)
        showCustomPanel(currentThemeIndex == -1)
    }

    // ==================== 软件主题分段控件 ====================
    private lateinit var appThemeSegments: List<TextView>
    private var currentAppTheme: String = "system"

    private fun initThemeToggle() {
        val segSystem = findViewById<TextView>(R.id.seg_app_system)
        val segLight = findViewById<TextView>(R.id.seg_app_light)
        val segDark = findViewById<TextView>(R.id.seg_app_dark)
        appThemeSegments = listOf(segSystem, segLight, segDark)

        currentAppTheme = SPUtil.getAppTheme(this)

        fun selectSegment(theme: String, apply: Boolean) {
            currentAppTheme = theme
            refreshAppThemeSegments()
            if (::mainIntervalChips.isInitialized) refreshMainIntervalChips()
            if (apply && SPUtil.getAppTheme(this) != theme) {
                SPUtil.setAppTheme(this, theme)
                AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
                BaseWifiWidget.renderAllWidgets(this)
            }
        }

        segSystem.setOnClickListener { selectSegment("system", true) }
        segLight.setOnClickListener { selectSegment("light", true) }
        segDark.setOnClickListener { selectSegment("dark", true) }

        refreshAppThemeSegments()
    }

    private fun refreshAppThemeSegments() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)

        val segmentBgSelected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = 20f * resources.displayMetrics.density
        }
        val segmentBgUnselected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = 20f * resources.displayMetrics.density
        }

        findViewById<TextView>(R.id.seg_app_system).apply {
            background = if (currentAppTheme == "system") segmentBgSelected else segmentBgUnselected
            setTextColor(if (currentAppTheme == "system") 0xFFFFFFFF.toInt() else textPrimary)
        }
        findViewById<TextView>(R.id.seg_app_light).apply {
            background = if (currentAppTheme == "light") segmentBgSelected else segmentBgUnselected
            setTextColor(if (currentAppTheme == "light") 0xFFFFFFFF.toInt() else textPrimary)
        }
        findViewById<TextView>(R.id.seg_app_dark).apply {
            background = if (currentAppTheme == "dark") segmentBgSelected else segmentBgUnselected
            setTextColor(if (currentAppTheme == "dark") 0xFFFFFFFF.toInt() else textPrimary)
        }
    }

    // ==================== 颜色主题选择 ====================
    private fun initColorThemeSelector() {
        val current = SPUtil.getColorThemeIndex(this)
        refreshThemeChips(current)
        showCustomPanel(current == -1)

        themeChips.forEach { chip ->
            findViewById<LinearLayout>(chip.chipId).setOnClickListener {
                SPUtil.setColorThemeIndex(this, chip.index)
                refreshThemeChips(chip.index)
                showCustomPanel(false)
                BackgroundUtil.applyWindowBackground(this)
                refreshAppThemeSegments()
                refreshMainIntervalChips()
                BaseWifiWidget.renderAllWidgets(this)
            }
        }

        // 自定义芯片点击
        findViewById<LinearLayout>(R.id.chip_theme_custom).setOnClickListener {
            SPUtil.setColorThemeIndex(this, -1)
            refreshThemeChips(-1)
            showCustomPanel(true)
            BackgroundUtil.applyWindowBackground(this)
            refreshAppThemeSegments()
            refreshMainIntervalChips()
            BaseWifiWidget.renderAllWidgets(this)
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
                    val darkColor = adjustBrightness(color, 0.85f)
                    SPUtil.setCustomAccentLight(this, color)
                    SPUtil.setCustomAccentDark(this, darkColor)
                    SPUtil.setColorThemeIndex(this, -1)
                    refreshThemeChips(-1)
                    BackgroundUtil.applyWindowBackground(this)
                    ThemeUtil.applyToAppSettingsActivity(this)
                    refreshAppThemeSegments()
                    refreshMainIntervalChips()
                    BaseWifiWidget.renderAllWidgets(this)
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

    // ==================== 主界面刷新频率（芯片式选择器） ====================
    private lateinit var mainIntervalChips: List<TextView>
    private lateinit var chipMainCustom: TextView
    private lateinit var rowCustomMainInterval: View
    private lateinit var etCustomMainInterval: EditText

    private fun initMainRefreshInterval() {
        mainIntervalSeconds = SPUtil.getMainRefreshSeconds(this)
        mainIntervalChips = presetChipIds.map { findViewById<TextView>(it) }
        chipMainCustom = findViewById(R.id.chip_main_custom)
        rowCustomMainInterval = findViewById(R.id.row_custom_main_interval)
        etCustomMainInterval = findViewById(R.id.et_custom_main_interval)

        presetChipIds.forEachIndexed { index, id ->
            val chip = findViewById<TextView>(id)
            chip.setOnClickListener {
                mainIntervalSeconds = presetIntervals[index]
                SPUtil.setMainRefreshSeconds(this, mainIntervalSeconds)
                refreshMainIntervalChips()
                rowCustomMainInterval.visibility = View.GONE
                Toast.makeText(this, "刷新间隔已设为 ${if (presetIntervals[index] > 0) "${presetIntervals[index]}秒" else "关闭"}", Toast.LENGTH_SHORT).show()
            }
        }

        chipMainCustom.setOnClickListener {
            // 切换自定义输入行显示/隐藏
            val isVisible = rowCustomMainInterval.visibility == View.VISIBLE
            rowCustomMainInterval.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                val isPreset = presetIntervals.contains(mainIntervalSeconds)
                etCustomMainInterval.setText(if (!isPreset && mainIntervalSeconds > 0) mainIntervalSeconds.toString() else "")
                etCustomMainInterval.selectAll()
                etCustomMainInterval.requestFocus()
                applyCustomRowTheme()
            }
        }

        // 确定按钮 → 保存自定义间隔
        findViewById<View>(R.id.btn_custom_main_confirm).setOnClickListener {
            val secs = etCustomMainInterval.text.toString().toIntOrNull()
            if (secs != null && secs in 1..3600) {
                mainIntervalSeconds = secs
                SPUtil.setMainRefreshSeconds(this, secs)
                refreshMainIntervalChips()
                rowCustomMainInterval.visibility = View.GONE
                Toast.makeText(this, "自定义间隔已设为 ${secs}秒", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请输入 1-3600 之间的秒数", Toast.LENGTH_SHORT).show()
            }
        }

        // 取消按钮
        findViewById<View>(R.id.btn_custom_main_cancel).setOnClickListener {
            rowCustomMainInterval.visibility = View.GONE
        }

        // 键盘回车确认
        etCustomMainInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                findViewById<View>(R.id.btn_custom_main_confirm).performClick()
                true
            } else false
        }

        refreshMainIntervalChips()
    }

    private fun applyCustomRowTheme() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)

        etCustomMainInterval.apply {
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setBackgroundColor(cardBg)
        }

        findViewById<View>(R.id.btn_custom_main_confirm).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accent)
                cornerRadius = 20f * resources.displayMetrics.density
            }
            (this as? TextView)?.setTextColor(0xFFFFFFFF.toInt())
        }
        findViewById<View>(R.id.btn_custom_main_cancel).apply {
            (this as? TextView)?.setTextColor(textSecondary)
        }
    }

    private fun refreshMainIntervalChips() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val chipRadius = 18f * resources.displayMetrics.density

        val chipSelected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = chipRadius
        }
        val chipUnselected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = chipRadius
        }

        val isPreset = presetIntervals.contains(mainIntervalSeconds)

        presetChipIds.forEachIndexed { index, id ->
            val chip = findViewById<TextView>(id)
            val selected = presetIntervals[index] == mainIntervalSeconds && isPreset
            chip.background = if (selected) chipSelected else chipUnselected
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else textPrimary)
        }

        chipMainCustom.background = if (!isPreset) chipSelected else chipUnselected
        chipMainCustom.setTextColor(if (!isPreset) 0xFFFFFFFF.toInt() else textPrimary)
        chipMainCustom.text = if (!isPreset && mainIntervalSeconds > 0) "${mainIntervalSeconds}秒" else "自定义..."
    }

    // ==================== 主题 Chip 刷新 ====================
    private fun refreshThemeChips(selectedIndex: Int) {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val chipRadius = 20f * resources.displayMetrics.density

        val chipSelectedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = chipRadius
        }
        val chipNormalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = chipRadius
        }

        themeChips.forEach { chip ->
            val container = findViewById<LinearLayout>(chip.chipId)
            val dot = findViewById<View>(chip.dotId)
            val label = findViewById<TextView>(chip.labelId)
            val palette = ThemeColors.getById(chip.index)
            val selected = chip.index == selectedIndex

            container.background = if (selected) chipSelectedBg else chipNormalBg
            dot.background = makeDot(palette.accentLight, if (selected) 0xFFFFFFFF.toInt() else palette.accentLight)
            label.setTextColor(if (selected) 0xFFFFFFFF.toInt() else textPrimary)
        }

        // 自定义 Chip
        val customContainer = findViewById<LinearLayout>(R.id.chip_theme_custom)
        val customDot = findViewById<View>(R.id.dot_custom)
        val customLabel = findViewById<TextView>(R.id.label_custom)
        val customSelected = selectedIndex == -1
        val customAccent = SPUtil.getCustomAccentLight(this)

        customContainer.background = if (customSelected) chipSelectedBg else chipNormalBg
        // 自定义色点：渐变圆
        val dotColor = if (customSelected) 0xFFFFFFFF.toInt() else customAccent
        customDot.background = makeDot(customAccent, dotColor)
        customLabel.setTextColor(if (customSelected) 0xFFFFFFFF.toInt() else textPrimary)
    }

    /** 创建纯色小圆点 */
    private fun makeDot(color: Int, displayColor: Int = color): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(displayColor)
        }
    }
}
