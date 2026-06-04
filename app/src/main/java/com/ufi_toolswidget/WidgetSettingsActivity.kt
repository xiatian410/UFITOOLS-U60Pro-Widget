package com.ufi_toolswidget

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import java.util.concurrent.TimeUnit

class WidgetSettingsActivity : AppCompatActivity() {

    /** 后台刷新间隔预设选项（分钟），-1 表示自定义 */
    private val presetWidgetIntervals = listOf(15, 30, 60, 120, 0)
    private val presetWidgetChipIds = listOf(
        R.id.chip_widget_15, R.id.chip_widget_30, R.id.chip_widget_60, R.id.chip_widget_120, R.id.chip_widget_off
    )

    private lateinit var segFollow: TextView
    private lateinit var segLight: TextView
    private lateinit var segDark: TextView
    private var widgetTheme: String = "follow_app"

    private var widgetIntervalMinutes: Int = 15
    private lateinit var widgetIntervalChips: List<TextView>
    private lateinit var chipWidgetCustom: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_widget_settings)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToWidgetSettingsPage(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // ===== 显示项 CheckBox（实时生效）=====
        val cbTemp = findViewById<CheckBox>(R.id.cb_show_temp)
        val cbModel = findViewById<CheckBox>(R.id.cb_show_model)
        val cbSignal = findViewById<CheckBox>(R.id.cb_show_signal)
        val cbBattery = findViewById<CheckBox>(R.id.cb_show_battery)
        val cbCpu = findViewById<CheckBox>(R.id.cb_show_cpu)
        val cbMem = findViewById<CheckBox>(R.id.cb_show_mem)
        val cbTime = findViewById<CheckBox>(R.id.cb_show_time)

        // 恢复显示项设置
        cbTemp.isChecked = SPUtil.getShowTemp(this)
        cbModel.isChecked = SPUtil.getShowModel(this)
        cbSignal.isChecked = SPUtil.getShowSignal(this)
        cbBattery.isChecked = SPUtil.getShowBattery(this)
        cbCpu.isChecked = SPUtil.getShowCpu(this)
        cbMem.isChecked = SPUtil.getShowMem(this)
        cbTime.isChecked = SPUtil.getShowTime(this)

        // CheckBox 变化时立即保存并刷新小组件
        val onCheckedChanged = { _: CompoundButton, _: Boolean ->
            SPUtil.setShowTemp(this, cbTemp.isChecked)
            SPUtil.setShowModel(this, cbModel.isChecked)
            SPUtil.setShowSignal(this, cbSignal.isChecked)
            SPUtil.setShowBattery(this, cbBattery.isChecked)
            SPUtil.setShowCpu(this, cbCpu.isChecked)
            SPUtil.setShowMem(this, cbMem.isChecked)
            SPUtil.setShowTime(this, cbTime.isChecked)
            BaseWifiWidget.renderAllWidgets(this)
        }
        cbTemp.setOnCheckedChangeListener(onCheckedChanged)
        cbModel.setOnCheckedChangeListener(onCheckedChanged)
        cbSignal.setOnCheckedChangeListener(onCheckedChanged)
        cbBattery.setOnCheckedChangeListener(onCheckedChanged)
        cbCpu.setOnCheckedChangeListener(onCheckedChanged)
        cbMem.setOnCheckedChangeListener(onCheckedChanged)
        cbTime.setOnCheckedChangeListener(onCheckedChanged)

        // ===== 后台刷新频率（芯片式选择器） =====
        widgetIntervalMinutes = SPUtil.getRefreshInterval(this)
        widgetIntervalChips = presetWidgetChipIds.map { findViewById<TextView>(it) }
        chipWidgetCustom = findViewById(R.id.chip_widget_custom)
        val rowCustomWidgetInterval = findViewById<View>(R.id.row_custom_widget_interval)
        val etCustomWidgetInterval = findViewById<EditText>(R.id.et_custom_widget_interval)

        presetWidgetChipIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                widgetIntervalMinutes = presetWidgetIntervals[index]
                SPUtil.setRefreshInterval(this, presetWidgetIntervals[index])
                refreshWidgetIntervalChips()
                rowCustomWidgetInterval.visibility = View.GONE
                updateWidgetWorker()
                val label = if (presetWidgetIntervals[index] > 0) "${presetWidgetIntervals[index]}分钟" else "关闭"
                Toast.makeText(this, "后台刷新间隔已设为 $label", Toast.LENGTH_SHORT).show()
            }
        }

        chipWidgetCustom.setOnClickListener {
            val isVisible = rowCustomWidgetInterval.visibility == View.VISIBLE
            rowCustomWidgetInterval.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)
                etCustomWidgetInterval.setText(if (!isPreset && widgetIntervalMinutes > 0) widgetIntervalMinutes.toString() else "")
                etCustomWidgetInterval.selectAll()
                etCustomWidgetInterval.requestFocus()
                applyWidgetCustomRowTheme()
            }
        }

        // 确定按钮
        findViewById<View>(R.id.btn_custom_widget_confirm).setOnClickListener {
            val mins = etCustomWidgetInterval.text.toString().toIntOrNull()
            if (mins != null && mins in 1..1440) {
                widgetIntervalMinutes = mins
                SPUtil.setRefreshInterval(this, mins)
                refreshWidgetIntervalChips()
                rowCustomWidgetInterval.visibility = View.GONE
                updateWidgetWorker()
                Toast.makeText(this, "自定义间隔已设为 ${mins}分钟", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请输入 1-1440 之间的分钟数", Toast.LENGTH_SHORT).show()
            }
        }

        // 取消按钮
        findViewById<View>(R.id.btn_custom_widget_cancel).setOnClickListener {
            rowCustomWidgetInterval.visibility = View.GONE
        }

        // 键盘回车
        etCustomWidgetInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                findViewById<View>(R.id.btn_custom_widget_confirm).performClick()
                true
            } else false
        }

        refreshWidgetIntervalChips()

        // ===== 小组件主题分段控件（实时生效）=====
        widgetTheme = SPUtil.getWidgetTheme(this)
        segFollow = findViewById(R.id.seg_widget_follow)
        segLight = findViewById(R.id.seg_widget_light)
        segDark = findViewById(R.id.seg_widget_dark)

        fun applyWidgetTheme() {
            SPUtil.setWidgetTheme(this, widgetTheme)
            refreshWidgetSegments()
            BaseWifiWidget.renderAllWidgets(this)
        }
        segFollow.setOnClickListener { widgetTheme = "follow_app"; applyWidgetTheme() }
        segLight.setOnClickListener { widgetTheme = "light"; applyWidgetTheme() }
        segDark.setOnClickListener { widgetTheme = "dark"; applyWidgetTheme() }
        refreshWidgetSegments()

    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToWidgetSettingsPage(this)
        if (::segFollow.isInitialized) {
            refreshWidgetSegments()
            refreshWidgetIntervalChips()
        }
    }

    // ===== 更新 Worker =====
    private fun updateWidgetWorker() {
        if (widgetIntervalMinutes <= 0) {
            WorkManager.getInstance(this).cancelUniqueWork("wifi_crawl")
        } else {
            val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(
                widgetIntervalMinutes.toLong(), TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "wifi_crawl",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    // ===== 自定义行主题 =====
    private fun applyWidgetCustomRowTheme() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)

        findViewById<EditText>(R.id.et_custom_widget_interval).apply {
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setBackgroundColor(cardBg)
        }

        findViewById<View>(R.id.btn_custom_widget_confirm).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accent)
                cornerRadius = 20f * resources.displayMetrics.density
            }
            (this as? TextView)?.setTextColor(0xFFFFFFFF.toInt())
        }
        findViewById<View>(R.id.btn_custom_widget_cancel).apply {
            (this as? TextView)?.setTextColor(textSecondary)
        }
    }

    // ===== 小组件主题分段控件刷新 =====
    private fun refreshWidgetSegments() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)

        val segBgSelected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = 20f * resources.displayMetrics.density
        }
        val segBgUnselected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = 20f * resources.displayMetrics.density
        }

        segFollow.apply {
            background = if (widgetTheme == "follow_app") segBgSelected else segBgUnselected
            setTextColor(if (widgetTheme == "follow_app") 0xFFFFFFFF.toInt() else textPrimary)
        }
        segLight.apply {
            background = if (widgetTheme == "light") segBgSelected else segBgUnselected
            setTextColor(if (widgetTheme == "light") 0xFFFFFFFF.toInt() else textPrimary)
        }
        segDark.apply {
            background = if (widgetTheme == "dark") segBgSelected else segBgUnselected
            setTextColor(if (widgetTheme == "dark") 0xFFFFFFFF.toInt() else textPrimary)
        }
    }

    // ===== 刷新间隔芯片刷新 =====
    private fun refreshWidgetIntervalChips() {
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

        val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)

        presetWidgetChipIds.forEachIndexed { index, id ->
            val chip = findViewById<TextView>(id)
            val selected = presetWidgetIntervals[index] == widgetIntervalMinutes && isPreset
            chip.background = if (selected) chipSelected else chipUnselected
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else textPrimary)
        }

        chipWidgetCustom.background = if (!isPreset) chipSelected else chipUnselected
        chipWidgetCustom.setTextColor(if (!isPreset) 0xFFFFFFFF.toInt() else textPrimary)
        chipWidgetCustom.text = if (!isPreset && widgetIntervalMinutes > 0) "${widgetIntervalMinutes}分" else "自定义..."
    }

}
