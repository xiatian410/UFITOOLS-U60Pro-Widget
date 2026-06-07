package com.ufi_toolswidget

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import java.util.Locale
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CrashHandler
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.UpdateChecker
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.util.WifiEntity
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var tvModel: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvNetSignal: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDaily: TextView
    private lateinit var tvFlow: TextView
    private lateinit var tvFirmware: TextView
    private lateinit var tvFirmwareSub: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatterySub: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvClientIp: TextView

    // 保存最后一次获取到的完整数据（用于弹窗详情） — 已迁移至 MainViewModel
    // 线程: 仅限主线程，使用 viewModel.getWifiEntity() 访问

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    // 当前打开的详情弹窗（支持实时刷新）
    // activeDialogType 已迁移至 MainViewModel，通过 viewModel.setActiveDialogType() / viewModel.activeDialogType.value 访问
    private var activeDialog: Dialog? = null

    // 弹窗防拥堵：debounce 时间戳
    private var lastDialogClickTime = 0L

    // 加载 / 主内容状态
    private lateinit var loadingView: View
    private lateinit var mainContentView: View

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var autoRefreshJob: Job? = null

    /** ViewModel：横跨配置变更存活，解决旋转屏幕数据丢失问题 */
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** 首次加载动画是否已执行（避免旋转后重复触发） */
    private var hasShownFirstLoadAnimation = false

    override fun onResume() {
        super.onResume()
        DebugLogger.logLife(TAG, "onResume() called")
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.MAIN)
        DebugLogger.logUi(TAG, "Theme applied in onResume")
        if (::tvModel.isInitialized) {
            // 先检测 Worker 是否因连续失败被停止
            checkWorkerFailureState()
            // 只有没有错误弹窗显示时才刷新数据
            if (viewModel.activeDialogType.value != "error") {
                DebugLogger.logLife(TAG, "onResume: no error dialog, calling refreshData")
                // 从 ViewModel 恢复缓存数据到 UI（旋转恢复场景）
                viewModel.getWifiEntity()?.let { cachedData ->
                    DebugLogger.logUi(TAG, "onResume: applying cached entity from ViewModel")
                    hideLoadingView(false)
                    hasShownFirstLoadAnimation = true  // 避免 refreshData 重复触发动画
                    applyWifiEntityToUi(cachedData)
                }
                refreshData()
                startAutoRefreshTimer()
            } else {
                DebugLogger.logLife(TAG, "onResume: error dialog IS visible, skipping refreshData")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.logLife(TAG, "onPause() called, stopping auto refresh")
        // 离开界面时停止自动刷新
        stopAutoRefreshTimer()
        dismissActiveDialog()
        DebugLogger.flushToFile() // Activity 离开前台，落盘日志
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ═══ 初始化全局崩溃捕获 ═══
        CrashHandler.init(applicationContext)

        // 全局应用主题
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)

        if (SPUtil.isFirstRun(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        DebugLogger.init(this)

        try {
            // 1. 设置视图
            setContentView(R.layout.activity_main)
            // 2. 应用主题
            ThemeUtil.applyTheme(this, ThemeUtil.PageType.MAIN)
            // 3. 注册主题变更监听（后台也能实时更新背景）
            themeChangeReceiver = ThemeChangeNotifier.register(this) {
                ThemeUtil.applyTheme(this@MainActivity, ThemeUtil.PageType.MAIN)
            }
            // 3. 入场动画（安全包装）
            try {
                if (AnimationUtil.pendingTransitionBitmap != null) {
                    AnimationUtil.applyCrossfadeEnterFromRecreate(this)
                }
            } catch (e: Exception) {
                DebugLogger.logExc(TAG, "入场动画执行失败: ${e.message}")
            }

            initViews()
            displayAppVersion()

            // 自动检测更新逻辑
            checkUpdateSilently()

            // 启动数据同步
            startWorker(SPUtil.getRefreshInterval(this))
            
            // ════ 检测上次崩溃 ════
            checkCrashLogOnStartup()
        } catch (e: Exception) {
            DebugLogger.logExc(TAG, "MainActivity 启动流程崩溃: ${e.message}")
            e.printStackTrace()
            // 兜底：避免白屏
            val errorView = TextView(this).apply {
                text = "加载界面失败\n\n${e.message}\n\n建议前往「诊断中心」查看原因"
                textSize = 14f
                setPadding(40, 80, 40, 40)
            }
            val scroll = ScrollView(this).apply { addView(errorView) }
            setContentView(scroll)
        }
    }

    /**
     * 静默检查更新（启动时触发，受开关和频率限制）
     */
    private fun checkUpdateSilently() {
        if (!SPUtil.getAutoCheckUpdate(this)) return

        val now = System.currentTimeMillis()
        val lastCheck = SPUtil.getLastUpdateCheckTime(this)
        
        // 限制自动检测频率：每 24 小时检查一次
        if (now - lastCheck < 24 * 60 * 60 * 1000L) {
            DebugLogger.logSys(TAG, "checkUpdateSilently: skip, last check was within 24h")
            return
        }

        DebugLogger.logSys(TAG, "checkUpdateSilently: starting background check")
        UpdateChecker.checkUpdate(this) { info, error ->
            if (info != null && !isFinishing && !isDestroyed) {
                SPUtil.setLastUpdateCheckTime(this, now)
                showUpdateDialog(info)
            } else if (error == null) {
                // 如果没有新版本且没有错误，也记录本次检查时间
                SPUtil.setLastUpdateCheckTime(this, now)
            }
        }
    }

    private fun displayAppVersion() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        tvSubtitle.text = "软件版本: v${version ?: "--"}"
    }

    private fun setupAnimations() {
        // 交错入场序列：Header → 网络卡片 → 更新按钮 → 硬件卡片
        // 每个子元素内部也有微小的 cascade 延迟
        val entries = listOf(
            Triple(findViewById<View>(R.id.main_header), 0L, 60L),
            Triple(findViewById<View>(R.id.card_network), 70L, 30L),
            Triple(findViewById<View>(R.id.btn_check_update), 120L, 0L),
            Triple(findViewById<View>(R.id.card_device), 160L, 50L)
        )
        entries.forEachIndexed { index, (view, stagger, childOffset) ->
            view?.let {
                it.alpha = 0f
                it.translationY = 60f
                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(550)
                    .setStartDelay(index * stagger)
                    .setInterpolator(DecelerateInterpolator(2.5f))
                    .start()
            }
        }

        // 卡片内数据行依次淡入
        val dataViews = listOf(
            findViewById<View>(R.id.main_tv_model),
            findViewById<View>(R.id.main_tv_subtitle),
            findViewById<View>(R.id.main_iv_antenna), findViewById<View>(R.id.main_tv_net_signal),
            findViewById<View>(R.id.main_iv_temp), findViewById<View>(R.id.main_tv_temp),
            findViewById<View>(R.id.main_iv_cpu), findViewById<View>(R.id.main_tv_cpu),
            findViewById<View>(R.id.main_iv_chip), findViewById<View>(R.id.main_tv_mem),
            findViewById<View>(R.id.main_tv_daily), findViewById<View>(R.id.main_tv_flow),
        )
        dataViews.forEachIndexed { i, v ->
            v?.let {
                it.alpha = 0f
                it.animate()
                    .alpha(1f)
                    .setDuration(350)
                    .setStartDelay(300 + i * 40L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { it.alpha = 1f }
                    .start()
            }
        }
    }

    private fun initViews() {
        tvModel = findViewById(R.id.main_tv_model)
        tvSubtitle = findViewById(R.id.main_tv_subtitle)
        tvNetSignal = findViewById(R.id.main_tv_net_signal)
        tvTemp = findViewById(R.id.main_tv_temp)
        tvCpu = findViewById(R.id.main_tv_cpu)
        tvMem = findViewById(R.id.main_tv_mem)
        tvDaily = findViewById(R.id.main_tv_daily)
        tvFlow = findViewById(R.id.main_tv_flow)
        tvFirmware = findViewById(R.id.main_tv_firmware)
        tvFirmwareSub = findViewById(R.id.main_tv_firmware_sub)
        tvBattery = findViewById(R.id.main_tv_battery)
        tvBatterySub = findViewById(R.id.main_tv_battery_sub)
        tvStorage = findViewById(R.id.main_tv_storage)
        tvClientIp = findViewById(R.id.main_tv_client_ip)

        // 网络 点击 → 网络详情 (RSRP/SINR/RSRQ)
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_network)) {
            viewModel.getWifiEntity()?.let { showNetworkDetailDialog(it) }
        }
        // 温度 点击 → 温度详情
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_temp)) {
            viewModel.getWifiEntity()?.let { showTemperatureDialog(it) }
        }
        // CPU 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_cpu)) {
            viewModel.getWifiEntity()?.let { showCpuDetailDialog(it) }
        }
        // 内存 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_mem)) {
            viewModel.getWifiEntity()?.let { showMemDetailDialog(it) }
        }
        // 存储 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_storage)) {
            viewModel.getWifiEntity()?.let { showStorageDetailDialog(it) }
        }
        // 固件 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_firmware)) {
            viewModel.getWifiEntity()?.let { showFirmwareDetailDialog(it) }
        }
        // IP 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_ip)) {
            viewModel.getWifiEntity()?.let { showIpDetailDialog(it) }
        }

        // 状态视图
        mainContentView = findViewById(R.id.main_content)
        loadingView = findViewById(R.id.loading_view)

        // 初始：显示加载界面，隐藏主内容
        loadingView.visibility = View.VISIBLE
        mainContentView.visibility = View.GONE

        findViewById<View>(R.id.btn_settings).apply {
            AnimationUtil.applyScaleClickAnimation(this) {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        findViewById<View>(R.id.btn_check_update).apply {
            findViewById<TextView>(R.id.common_btn_text).text = "检查更新"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "正在检查更新...", Toast.LENGTH_SHORT).show()
                UpdateChecker.checkUpdate(this@MainActivity) { info, error ->
                    when {
                        error != null -> {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        }
                        info != null -> {
                            showUpdateDialog(info)
                        }
                        else -> {
                            Toast.makeText(this@MainActivity, "当前已是最新版本 ✓", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            setOnTouchListener(ScaleTouchListener())
        }
    }

    private fun refreshData() {
        DebugLogger.logApi(TAG, "refreshData() started")
        viewModel.refreshData(
            ctx = this,
            onSuccess = { data ->
                val wasLoading = loadingView.visibility == View.VISIBLE
                hideLoadingView(wasLoading)
                if (wasLoading && !hasShownFirstLoadAnimation) {
                    hasShownFirstLoadAnimation = true
                    setupAnimations()
                    DebugLogger.logUi(TAG, "First load complete, setupAnimations called")
                }
                applyWifiEntityToUi(data)
                refreshActiveDialog(data)
            },
            onError = { reason ->
                showErrorDialog()
            },
            onToast = { msg, isLong ->
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@MainActivity, msg,
                        if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * 将 [WifiEntity] 各字段应用到主界面 UI。
     * 由 refreshData() 成功分支和旋转恢复时调用。
     */
    private fun applyWifiEntityToUi(data: WifiEntity) {
        DebugLogger.logApi(TAG, "applyWifiEntityToUi: model=${data.model}")

        // 设备名称 & 软件版本
        val deviceName = data.deviceModel.ifEmpty { data.model }
        if (tvModel.text != deviceName) tvModel.text = deviceName
        try {
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
            val subText = "软件 v$appVersion"
            if (tvSubtitle.text != subText) tvSubtitle.text = subText
        } catch (_: Exception) {
            // getPackageInfo 在极少数情况下失败，非关键 UI 错误
        }

        // 网络制式
        val info = data.atNetworkInfo
        val newNetText = if (info != null) {
            val carrierText = info.carrier.ifEmpty { info.operator }
            val typeText = info.networkType.replace(" NSA", "").replace(" SA", "")
                .ifEmpty { data.netType }
            if (carrierText.isNotEmpty() && typeText.isNotEmpty() && !typeText.contains(carrierText)) {
                "$carrierText $typeText"
            } else {
                typeText.ifEmpty { carrierText.ifEmpty { "--" } }
            }
        } else {
            data.netType.ifEmpty { "--" }
        }
        DebugLogger.logUi(TAG, "Updating UI: net=$newNetText")
        AnimationUtil.smoothUpdateText(tvNetSignal, newNetText)
        AnimationUtil.smoothUpdateText(tvTemp, getHighestTemp(data))
        AnimationUtil.smoothUpdateText(tvCpu, data.cpu.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvMem, data.mem.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvDaily, data.dailyFlow.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvFlow, data.flow.ifEmpty { "--" })

        // 电池
        val (batteryPct, batterySub) = buildBatteryParts(data)
        AnimationUtil.smoothUpdateText(tvBattery, batteryPct)
        if (batterySub != null) {
            if (tvBatterySub.visibility != View.VISIBLE) {
                tvBatterySub.text = batterySub
                tvBatterySub.alpha = 0f
                tvBatterySub.visibility = View.VISIBLE
                tvBatterySub.animate().alpha(1f).setDuration(300).start()
            } else {
                AnimationUtil.smoothUpdateText(tvBatterySub, batterySub)
            }
        } else {
            tvBatterySub.visibility = View.GONE
        }

        // 固件与存储
        val fwMain = data.appVer.ifEmpty { data.firmwareVer.ifEmpty { "--" } }
        AnimationUtil.smoothUpdateText(tvFirmware, fwMain)
        if (data.appVerCode.isNotEmpty()) {
            val fwSub = "build ${data.appVerCode}"
            if (tvFirmwareSub.visibility != View.VISIBLE) {
                tvFirmwareSub.text = fwSub
                tvFirmwareSub.alpha = 0f
                tvFirmwareSub.visibility = View.VISIBLE
                tvFirmwareSub.animate().alpha(1f).setDuration(300).start()
            } else {
                AnimationUtil.smoothUpdateText(tvFirmwareSub, fwSub)
            }
        } else {
            tvFirmwareSub.visibility = View.GONE
        }
        AnimationUtil.smoothUpdateText(tvStorage, data.internalStorage.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvClientIp, data.clientIp.ifEmpty { "--" })
    }

    /** 隐藏加载界面，根据参数决定是否执行淡出动画 */
    private fun hideLoadingView(animate: Boolean) {
        if (loadingView.visibility != View.VISIBLE) return
        if (animate) {
            loadingView.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    loadingView.visibility = View.GONE
                    loadingView.alpha = 1f
                }
                .start()
            mainContentView.alpha = 0f
            mainContentView.visibility = View.VISIBLE
            mainContentView.animate()
                .alpha(1f)
                .setDuration(350)
                .setStartDelay(80)
                .withEndAction { mainContentView.alpha = 1f }
                .start()
        } else {
            loadingView.visibility = View.GONE
            mainContentView.visibility = View.VISIBLE
        }
    }

    /**
     * 启动时检测上次崩溃标志，若存在则弹出对话框引导用户查看日志。
     * 该弹窗优先级低于 error 弹窗，仅在主流程成功后调用。
     */
    private fun checkCrashLogOnStartup() {
        val crashTime = SPUtil.getLastCrashTime(this)
        if (crashTime <= 0) return

        val summary = SPUtil.getLastCrashSummary(this)
        DebugLogger.logExc(TAG, "checkCrashLogOnStartup: last crash=$crashTime, summary=$summary")

        // 延迟 1 秒弹出，避免与 loading → content 过渡动画冲突
        lifecycleScope.launch {
            delay(1000)
            if (isFinishing || isDestroyed) return@launch

            try {
                // 使用通用弹窗展示崩溃提示
                val dialog = showCommonDialog(
                    type = "crash",
                    title = "检测到异常退出",
                    iconRes = android.R.drawable.ic_dialog_alert,
                    onFill = { content ->
                        content.addView(sectionTitleView("崩溃信息"))
                        val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(crashTime))
                        content.addView(keyValueView("发生时间", timeStr))
                        if (summary.isNotEmpty()) {
                            content.addView(keyValueView("异常摘要", summary.take(150)))
                        }
                        content.addView(keyValueView("提示", "日志已自动保存（已脱敏），建议分享给开发者以帮助修复问题"))
                    },
                    primaryBtnText = "查看并分享日志",
                    onPrimaryClick = { dlg ->
                        dlg.dismiss()
                        SPUtil.clearCrashInfo(this@MainActivity)
                        val intent = Intent(this@MainActivity, DebugLogActivity::class.java).apply {
                            putExtra(DebugLogActivity.EXTRA_CRASH_MODE, true)
                        }
                        startActivity(intent)
                    },
                    secondaryBtnText = "忽略",
                    onSecondaryClick = { dlg ->
                        dlg.dismiss()
                        SPUtil.clearCrashInfo(this@MainActivity)
                        CrashHandler.clearCrashLog(this@MainActivity)
                        Toast.makeText(this@MainActivity, "崩溃记录已清除", Toast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                DebugLogger.logExc(TAG, "Failed to show crash dialog: ${e.message}")
                // 静默清除崩溃标志，避免反复弹窗
                SPUtil.clearCrashInfo(this@MainActivity)
            }
        }
    }

    /**
     * 显示错误状态弹窗
     */
    private fun showErrorDialog() {
        if (viewModel.activeDialogType.value == "error") return
        dismissActiveDialog()

        val reason = SPUtil.getWorkerStopReason(this)
        val address = SPUtil.getDeviceAddress(this)

        DebugLogger.logSys(TAG, "showErrorDialog: reason=$reason, target=$address")

        val title: String
        val message: String
        val hint: String
        val iconRes: Int

        when (reason) {
            WifiWorker.REASON_NETWORK -> {
                title = "无法与设备通信"
                message = "无法连接到设备 ($address)，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备未开机或不在同一内网\n" +
                        "• 设备 IP 地址配置有误\n" +
                        "• 设备网络异常"
                hint = "设备恢复上线后将自动恢复刷新"
                iconRes = R.drawable.ic_router_off
            }
            WifiWorker.REASON_API -> {
                title = "连接配置异常"
                message = "设备网络可达，但 API 请求连续失败，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备端口配置错误\n" +
                        "• Token / 管理口令不正确或已更改\n" +
                        "• 设备 API 服务异常"
                hint = "请修改配置后点击「重新连接」重试"
                iconRes = R.drawable.ic_router_off
            }
            else -> {
                title = "连接失败"
                message = "连续多次无法连接到设备，后台刷新已自动暂停以节省电量。\n\n" +
                        "请检查设备状态和连接配置后重试。"
                hint = "设备恢复上线后将自动恢复刷新"
                iconRes = R.drawable.ic_router_off
            }
        }

        val dialog = showCommonDialog(
            type = "error",
            title = title,
            iconRes = iconRes,
            onFill = { content ->
                // 消息文本
                content.addView(TextView(this).apply {
                    text = message
                    textSize = 14f
                    setTextColor(ThemeColors.textPrimary(this@MainActivity))
                    setLineSpacing(0f, 1.3f)
                })
                
                // 目标地址行
                content.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12.dpToPx() }
                    alpha = 0.7f
                    
                    addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply {
                            rightMargin = 6.dpToPx()
                        }
                        setImageResource(R.drawable.ic_antenna)
                        setColorFilter(ThemeColors.textPrimary(this@MainActivity))
                    })
                    
                    addView(TextView(this@MainActivity).apply {
                        text = address
                        textSize = 13f
                        setTextColor(ThemeColors.textPrimary(this@MainActivity))
                        typeface = android.graphics.Typeface.MONOSPACE
                    })
                })
                
                // 提示文本
                content.addView(TextView(this).apply {
                    text = hint
                    textSize = 12f
                    setTextColor(ThemeColors.textSecondary(this@MainActivity))
                    alpha = 0.6f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 14.dpToPx() }
                })
            },
            primaryBtnText = "重新连接",
            onPrimaryClick = { d ->
                WifiWorker.resetFailureState(this@MainActivity)
                BaseWifiWidget.renderAllWidgets(this@MainActivity)
                d.dismiss()
                loadingView.visibility = View.VISIBLE
                mainContentView.visibility = View.GONE
                stopAutoRefreshTimer()
                refreshData()
            },
            secondaryBtnText = "修改连接配置",
            onSecondaryClick = {
                startActivity(Intent(this@MainActivity, ConfigModifyActivity::class.java))
            }
        )
        
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)
        stopAutoRefreshTimer()
    }
    
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun startWorker(minutes: Int) {
        DebugLogger.logSys(TAG, "startWorker: minutes=$minutes")
        val workManager = WorkManager.getInstance(this)
        if (minutes <= 0) {
            DebugLogger.logSys(TAG, "startWorker: minutes<=0, cancelling worker")
            workManager.cancelUniqueWork("wifi_crawl")
            return
        }
        // 只在 Worker 当前未 stopped 时才重置 — 如果已 stopped，保持 stopped 状态让用户看到错误界面
        // Worker 内部自己会处理恢复逻辑（ping 通过后自动解除 stopped）
        val wasStopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.logSys(TAG, "startWorker: wasStopped=$wasStopped")
        if (!wasStopped) {
            WifiWorker.resetFailureState(this)
        } else {
            DebugLogger.logSys(TAG, "startWorker: worker is stopped, NOT resetting failure state")
        }
        val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            "wifi_crawl",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * 检查 Worker 是否因失败被停止。
     * 失败时显示错误弹窗。
     *
     * 如果 Worker 已恢复，关闭弹窗。
     */
    private fun checkWorkerFailureState() {
        val stopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.logSys(TAG, "checkWorkerFailureState: stopped=$stopped")

        if (!stopped) {
            // Worker 正常 → 如果错误弹窗已显示则关闭
            if (viewModel.activeDialogType.value == "error") {
                DebugLogger.logSys(TAG, "checkWorkerFailureState: stopped=false, dismissing error dialog")
                dismissActiveDialog()
            }
            return
        }

        // Worker 已停止 → 显示错误弹窗
        showErrorDialog()
    }

    /**
     * 启动前台自动刷新定时器（基于主界面刷新间隔设置）
     */
    private fun startAutoRefreshTimer() {
        stopAutoRefreshTimer()
        val intervalSeconds = SPUtil.getMainRefreshSeconds(this)
        if (intervalSeconds <= 0) return // 用户关闭了自动刷新

        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (!isFinishing && !isDestroyed) {
                    refreshData()
                }
            }
        }
    }

    /**
     * 停止前台自动刷新定时器
     */
    private fun stopAutoRefreshTimer() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    // ===== 详情弹窗（主题统一 + 实时刷新） =====

    /** 创建主题统一的 Dialog，带防拥堵 + 弹窗动效 + 动态模糊背景（含退出渐变） */
    private fun createThemedDialog(layoutRes: Int, widthRatio: Float = 0.92f, dialogType: String): Dialog {
        // 同类型弹窗已显示 → 直接复用，避免叠加/闪烁
        if (activeDialog?.isShowing == true && viewModel.activeDialogType.value == dialogType) {
            return activeDialog!!
        }
        // 不同类型 → 关闭旧的，创建新的
        dismissActiveDialog()

        val dialog = object : Dialog(this, R.style.Theme_UFITOOLSWidget_Transparent) {
            fun realDismiss() {
                super.dismiss()
                activeDialog = null
                viewModel.setActiveDialogType(null)
            }

            override fun dismiss() {
                if (window == null) {
                    realDismiss()
                    return
                }
                viewModel.setActiveDialogType(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AnimationUtil.applyDialogBlurOut(this) { realDismiss() }
                } else {
                    realDismiss()
                }
            }
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutRes)

        // ── 动态应用主题色到弹窗根布局 ──
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.01f)
            setWindowAnimations(R.style.DialogAnimationTheme)
            val width = (resources.displayMetrics.widthPixels * widthRatio).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // ── 动态模糊背景：API 31+ 原生模糊，API 26-30 bitmap 缩放模糊 ──
        CommonDialogHelper.applyDialogBlur(this, dialog)

        dialog.setOnDismissListener {
            activeDialog = null
            viewModel.setActiveDialogType(null)
        }
        activeDialog = dialog
        viewModel.setActiveDialogType(dialogType)
        return dialog
    }

    /** 弹窗防拥堵检查：返回 true 允许创建/显示 */
    private fun checkDialogDebounce(): Boolean {
        // 如果当前已有正在显示的弹窗且不是 error 弹窗，禁止开启新的
        // 增加 null 判定，确保关闭过程中也能及时重置状态
        val isShowing = activeDialog?.isShowing == true
        if (isShowing && viewModel.activeDialogType.value != "error") {
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastDialogClickTime < 300L) return false // 缩短 debounce 时间
        lastDialogClickTime = now
        return true
    }

    /** 弹窗内容容器（ScrollView 内的 LinearLayout） */
    private fun LinearLayout.clearAndFill(block: LinearLayout.() -> Unit) {
        removeAllViews()
        block()
    }

    private fun dismissActiveDialog() {
        try { activeDialog?.dismiss() } catch (_: Exception) {
            // Dialog.dismiss 在 Window 已销毁时可能抛异常，非关键错误
        }
        activeDialog = null
        viewModel.setActiveDialogType(null)
    }

    /** 数据刷新后自动更新已打开的弹窗内容 */
    private fun refreshActiveDialog(data: WifiEntity) {
        if (activeDialog == null || !activeDialog!!.isShowing) {
            activeDialog = null
            viewModel.setActiveDialogType(null)
            return
        }
        
        // 尝试获取通用内容容器
        val content = try { 
            activeDialog!!.findViewById<LinearLayout>(R.id.common_dialog_content) 
        } catch (_: Exception) {
            // 弹窗尚未完全初始化时 findViewById 可能失败
            null
        } ?: return

        when (viewModel.activeDialogType.value) {
            "temperature" -> {
                // 精细化缓存：使用 hashCode 校验数据对象是否有任何变化
                val currentHash = data.cpuTempList.hashCode().toLong()
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillTemperature(data)
                    content.tag = currentHash
                }
            }
            "cpu" -> {
                // 精细化缓存：校验频率和使用率的完整 hashCode
                val currentHash = data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillCpuDetail(data)
                    content.tag = currentHash
                }
            }
            "mem" -> {
                // 精细化缓存：校验内存所有关键数值
                val currentHash = data.memUsedKb + data.memAvailableKb + data.swapUsedKb
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillMemDetail(data)
                    content.tag = currentHash
                }
            }
            "storage" -> {
                // 精细化缓存：校验内外存储已用量
                val currentHash = data.internalUsedStorage + data.externalUsedStorage
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillStorageDetail(data)
                    content.tag = currentHash
                }
            }
            "firmware" -> {
                // 精细化缓存：校验版本号变更
                val currentHash = data.appVer.hashCode().toLong() + data.webVersion.hashCode().toLong()
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillFirmwareDetail(data)
                    content.tag = currentHash
                }
            }
            "ip" -> {
                // 精细化缓存：校验 IP 地址变更
                val currentHash = data.clientIp.hashCode().toLong() + data.wanIp.hashCode().toLong() +
                                 (data.atNetworkInfo?.wanIpAt?.hashCode()?.toLong() ?: 0L)
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillIpDetail(data)
                    content.tag = currentHash
                }
            }
            "network" -> {
                // 精细化缓存：校验 AT 网络详情对象的完整 hashCode
                val currentHash = data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
                if (content.tag != currentHash) {
                    content.removeAllViews()
                    content.fillNetworkDetail(data)
                    content.tag = currentHash
                }
            }
        }
        
        // 动态数据刷新后，重新评估弹窗高度
        com.ufi_toolswidget.util.PopupViewUtil.autoAdjustDialogHeight(this, activeDialog!!, 0.92f)
    }

    /**
     * 显示通用主题弹窗的公共逻辑
     */
    private fun showCommonDialog(
        type: String,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout) -> Unit,
        primaryBtnText: String = "关闭",
        onPrimaryClick: ((Dialog) -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: ((Dialog) -> Unit)? = null
    ): Dialog? {
        if (!checkDialogDebounce()) return null
        
        val dialog = createThemedDialog(R.layout.layout_common_dialog, dialogType = type)
        
        // 如果是错误弹窗，禁止点击外部关闭，强制用户处理
        if (type == "error") {
            dialog.setCanceledOnTouchOutside(false)
            dialog.setCancelable(false)
        } else {
            dialog.setCanceledOnTouchOutside(true)
            dialog.setCancelable(true)
        }
        
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)
        
        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.clearAndFill { onFill(this) }
        
        val btnPrimary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = primaryBtnText
        AnimationUtil.applyScaleClickAnimation(btnPrimary) {
            if (onPrimaryClick != null) onPrimaryClick(dialog) else dialog.dismiss()
        }
        
        val btnSecondary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary)
        if (secondaryBtnText != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryBtnText
            AnimationUtil.applyScaleClickAnimation(btnSecondary) {
                onSecondaryClick?.invoke(dialog) ?: dialog.dismiss()
            }
            // 两个按钮时，主按钮保持间距
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 12.dpToPx()
        } else {
            btnSecondary.visibility = View.GONE
            // 只有一个按钮时，主按钮消除间距以占满宽度
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
        }
        
        // ── 动态高度适配：在填充内容后、展示前进行测量 ──
        com.ufi_toolswidget.util.PopupViewUtil.autoAdjustDialogHeight(this, dialog, 0.92f)
        
        dialog.show()
        return dialog
    }

    // ---------- 各弹窗 ----------

    private fun showNetworkDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "network",
            title = "网络详情",
            iconRes = R.drawable.ic_antenna,
            onFill = { it.fillNetworkDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
    }

    private fun showFirmwareDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "firmware",
            title = "固件与版本",
            iconRes = R.drawable.ic_check,
            onFill = { it.fillFirmwareDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.appVer.hashCode().toLong() + data.webVersion.hashCode().toLong()
    }

    private fun showIpDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "ip",
            title = "网络地址详情",
            iconRes = R.drawable.ic_router,
            onFill = { it.fillIpDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.clientIp.hashCode().toLong() + data.wanIp.hashCode().toLong() + 
            (data.atNetworkInfo?.wanIpAt?.hashCode()?.toLong() ?: 0L)
    }

    private fun LinearLayout.fillIpDetail(data: WifiEntity) {
        // ── 局域网地址 ──
        addView(sectionTitleView("局域网 (LAN)"))
        if (data.clientIp.isNotEmpty()) {
            addView(keyValueView("设备 IP", data.clientIp))
        }
        val gateway = SPUtil.getDeviceHost(context)
        addView(keyValueView("网关地址", gateway))

        // ── 广域网地址 ──
        val info = data.atNetworkInfo
        val wanIpv4 = if (data.wanIp.isNotEmpty()) data.wanIp else info?.wanIpAt ?: ""
        if (wanIpv4.isNotEmpty() || data.wanIpv6.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("广域网 (WAN)"))
            if (wanIpv4.isNotEmpty()) {
                addView(keyValueView("IPv4 地址", wanIpv4))
            }
            if (data.wanIpv6.isNotEmpty()) {
                addView(keyValueView("IPv6 地址", data.wanIpv6))
            }
            if (data.pdpTypeGoform.isNotEmpty()) {
                addView(keyValueView("承载类型", data.pdpTypeGoform))
            }
            if (info?.dnsServers?.isNotEmpty() == true) {
                addView(keyValueView("DNS 服务", info.dnsServers))
            }
        }
    }

    private fun LinearLayout.fillFirmwareDetail(data: WifiEntity) {
        // ── 软件信息 ──
        addView(sectionTitleView("软件版本"))
        try {
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
            addView(keyValueView("应用版本", "v$appVersion"))
        } catch (_: Exception) {
            // getPackageInfo 失败，不显示应用版本行（非关键）
        }
        if (data.appVer.isNotEmpty()) addView(keyValueView("接口版本", data.appVer))
        if (data.appVerCode.isNotEmpty()) addView(keyValueView("构建代码", data.appVerCode))

        // ── 模块信息 (AT) ──
        val info = data.atNetworkInfo
        if (info != null && (info.moduleModel.isNotEmpty() || info.firmwareDetail.isNotEmpty())) {
            addView(dividerView())
            addView(sectionTitleView("通信模块"))
            if (info.moduleModel.isNotEmpty()) addView(keyValueView("型号", info.moduleModel))
            if (info.firmwareDetail.isNotEmpty()) addView(keyValueView("固件", info.firmwareDetail))
        }

        // ── 设备信息 (Goform) ──
        if (data.hardwareVersion.isNotEmpty() || data.webVersion.isNotEmpty() || data.macAddress.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("设备信息"))
            if (data.hardwareVersion.isNotEmpty()) addView(keyValueView("硬件版本", data.hardwareVersion))
            if (data.webVersion.isNotEmpty()) addView(keyValueView("固件版本", data.webVersion))
            if (data.macAddress.isNotEmpty()) addView(keyValueView("MAC 地址", data.macAddress))
        }
    }

    private fun showTemperatureDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "temperature",
            title = "设备温度",
            iconRes = R.drawable.ic_temp,
            onFill = { it.fillTemperature(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.cpuTempList.hashCode().toLong()
    }

    private fun LinearLayout.fillTemperature(data: WifiEntity) {
        if (data.cpuTempList.isEmpty()) {
            addView(emptyHintView("暂无温度数据"))
        } else {
            addView(sectionTitleView("各分区温度"))
            data.cpuTempList.sortedByDescending { it.temp }.forEach { item ->
                val celsius = if (item.temp > 1000) item.temp / 1000.0 else item.temp
                val name = item.type.removeSuffix("-thmzone").replace("_", " ")
                addView(keyValueView(name, "%.1f℃".format(celsius), celsius >= 60))
            }
        }
    }

    private fun showCpuDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "cpu",
            title = "CPU 详情",
            iconRes = R.drawable.ic_cpu,
            onFill = { it.fillCpuDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
    }

    private fun LinearLayout.fillCpuDetail(data: WifiEntity) {
        var hasData = false
        if (data.cpuFreqInfo.isNotEmpty()) {
            addView(sectionTitleView("核心频率 (MHz)"))
            data.cpuFreqInfo.toSortedMap(compareBy { it.removePrefix("cpu").toIntOrNull() ?: 99 })
                .forEach { (key, freq) ->
                    addView(keyValueView(key, "${freq.cur} / ${freq.max} MHz"))
                }
            hasData = true
        }
        if (data.cpuUsageInfo.isNotEmpty()) {
            if (hasData) addView(dividerView())
            addView(sectionTitleView("核心使用率"))
            val sorted = data.cpuUsageInfo.toList().sortedBy { (k, _) ->
                if (k == "cpu") -1 else k.removePrefix("cpu").toIntOrNull() ?: 99
            }
            sorted.forEach { (key, usage) ->
                val label = if (key == "cpu") "总体" else key
                addView(keyValueView(label, "$usage%"))
            }
            hasData = true
        }
        if (!hasData) addView(emptyHintView("暂无详细 CPU 数据"))
    }

    private fun showMemDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "mem",
            title = "内存详情",
            iconRes = R.drawable.ic_chip,
            onFill = { it.fillMemDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.memUsedKb + data.memAvailableKb + data.swapUsedKb
    }

    private fun LinearLayout.fillMemDetail(data: WifiEntity) {
        var hasData = false
        if (data.memTotalKb > 0) {
            addView(sectionTitleView("内存 (RAM)"))
            addView(keyValueView("总量", formatKb(data.memTotalKb)))
            addView(keyValueView("已用", formatKb(data.memUsedKb)))
            addView(keyValueView("可用", formatKb(data.memAvailableKb)))
            addView(keyValueView("使用率", data.mem))
            hasData = true
        }
        if (data.swapTotalKb > 0) {
            if (hasData) addView(dividerView())
            addView(sectionTitleView("交换分区 (SWAP)"))
            addView(keyValueView("总量", formatKb(data.swapTotalKb)))
            addView(keyValueView("已用", formatKb(data.swapUsedKb)))
            addView(keyValueView("空闲", formatKb(data.swapFreeKb)))
            val swapPct = if (data.swapTotalKb > 0)
                "%.1f%%".format(data.swapUsedKb * 100.0 / data.swapTotalKb) else "--"
            addView(keyValueView("使用率", swapPct))
            hasData = true
        }
        if (!hasData) addView(emptyHintView("暂无详细内存数据"))
    }

    private fun showStorageDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "storage",
            title = "存储详情",
            iconRes = R.drawable.ic_router,
            onFill = { it.fillStorageDetail(data) }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.internalUsedStorage + data.externalUsedStorage
    }

    private fun LinearLayout.fillStorageDetail(data: WifiEntity) {
        var hasData = false
        if (data.internalTotalStorage > 0) {
            addView(sectionTitleView("内部存储"))
            addView(keyValueView("总量", formatStorageGb(data.internalTotalStorage)))
            addView(keyValueView("已用", formatStorageGb(data.internalUsedStorage)))
            val avail = if (data.internalAvailableStorage > 0) data.internalAvailableStorage
                else data.internalTotalStorage - data.internalUsedStorage
            addView(keyValueView("可用", formatStorageGb(avail)))
            val pct = if (data.internalTotalStorage > 0)
                "%.1f%%".format(data.internalUsedStorage * 100.0 / data.internalTotalStorage) else "--"
            addView(keyValueView("使用率", pct))
            hasData = true
        }
        if (data.externalTotalStorage > 0) {
            if (hasData) addView(dividerView())
            addView(sectionTitleView("外部存储"))
            addView(keyValueView("总量", formatStorageGb(data.externalTotalStorage)))
            addView(keyValueView("已用", formatStorageGb(data.externalUsedStorage)))
            val extAvail = if (data.externalAvailableStorage > 0) data.externalAvailableStorage
                else data.externalTotalStorage - data.externalUsedStorage
            addView(keyValueView("可用", formatStorageGb(extAvail)))
            val extPct = if (data.externalTotalStorage > 0)
                "%.1f%%".format(data.externalUsedStorage * 100.0 / data.externalTotalStorage) else "--"
            addView(keyValueView("使用率", extPct))
            hasData = true
        }
        if (!hasData) addView(emptyHintView("暂无详细存储数据"))
    }

    /** 填充网络详情（AT 指令：RSRP/SINR/RSRQ/频段/运营商） */
    private fun LinearLayout.fillNetworkDetail(data: WifiEntity) {
        val info = data.atNetworkInfo
        if (info == null) {
            addView(emptyHintView("暂无 AT 网络数据\n（设备可能不支持 AT 指令接口）"))
            return
        }

        // 运营商 + 网络类型
        addView(sectionTitleView("当前网络"))
        // 优先显示映射后的真实运营商名称，其次原始值
        val carrierText = info.carrier.ifEmpty { info.operator }
        if (carrierText.isNotEmpty()) {
            addView(keyValueView("运营商", carrierText))
        }
        if (info.networkType.isNotEmpty()) {
            addView(keyValueView("网络制式", info.networkType))
        }

        // RSRP (dBm)：-90 以上优秀，-105 以下差
        if (info.rsrp > Int.MIN_VALUE) {
            addView(dividerView())
            addView(sectionTitleView("信号参数"))
            val rsrpLabel = when {
                info.rsrp >= -85 -> "excellent"
                info.rsrp >= -100 -> "good"
                info.rsrp >= -110 -> "fair"
                else -> "poor"
            }
            addView(keyValueView("RSRP", "${info.rsrp} dBm  ($rsrpLabel)", info.rsrp < -110))

            // SINR (dB)：15+ 优秀，5 以下差
            if (info.sinr > Int.MIN_VALUE) {
                val sinrLabel = when {
                    info.sinr >= 20 -> "excellent"
                    info.sinr >= 10 -> "good"
                    info.sinr >= 0 -> "fair"
                    else -> "poor"
                }
                addView(keyValueView("SINR", "${info.sinr} dB  ($sinrLabel)", info.sinr < 0))
            }

            // RSRQ (dB)：-10 以上优秀，-20 以下差
            if (info.rsrq > Int.MIN_VALUE) {
                val rsrqLabel = when {
                    info.rsrq >= -10 -> "excellent"
                    info.rsrq >= -15 -> "good"
                    info.rsrq >= -20 -> "fair"
                    else -> "poor"
                }
                addView(keyValueView("RSRQ", "${info.rsrq} dB  ($rsrqLabel)", info.rsrq < -20))
            }
        }

        // 频段 / PCI / EARFCN / TAC / CI
        var hasCell = false
        if (info.band.isNotEmpty() || info.pci >= 0 || info.earfcn >= 0 || info.tac.isNotEmpty() || info.cellId.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("小区信息"))
            if (info.band.isNotEmpty()) { addView(keyValueView("频段", info.band)); hasCell = true }
            if (info.pci >= 0) { addView(keyValueView("PCI", info.pci.toString())); hasCell = true }
            if (info.earfcn >= 0) {
                val label = if (info.band.startsWith("n", ignoreCase = true)) "NR-ARFCN" else "EARFCN"
                addView(keyValueView(label, info.earfcn.toString())); hasCell = true
            }
            if (info.tac.isNotEmpty()) {
                val label = if (info.band.startsWith("n", ignoreCase = true)) "NR-TAC" else "TAC"
                addView(keyValueView(label, info.tac)); hasCell = true
            }
            if (info.cellId.isNotEmpty()) {
                val label = if (info.band.startsWith("n", ignoreCase = true)) "NR-CI" else "Cell ID"
                addView(keyValueView(label, info.cellId)); hasCell = true
            }
        }

        // ── 设备标识 + SIM（AT + Goform）──
        var hasIdentity = false
        if (info.imei.isNotEmpty() || data.goformImei.isNotEmpty() || data.goformImsi.isNotEmpty() || data.goformIccid.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("设备标识"))
            if (info.imei.isNotEmpty()) {
                addView(keyValueView("IMEI", info.imei)); hasIdentity = true
            } else if (data.goformImei.isNotEmpty()) {
                addView(keyValueView("IMEI", data.goformImei)); hasIdentity = true
            }
            if (data.goformImsi.isNotEmpty()) {
                addView(keyValueView("IMSI", data.goformImsi)); hasIdentity = true
            }
            if (data.goformIccid.isNotEmpty()) {
                addView(keyValueView("ICCID", data.goformIccid)); hasIdentity = true
            }
        }

        // ── SIM PIN 状态 ──
        val pinDisplay = when {
            info.pinStatusAt.isNotEmpty() -> info.pinStatusAt
            data.pinStatusCode in 0..2 -> when (data.pinStatusCode) {
                0 -> "READY (已解锁)"
                1 -> "SIM PIN (需输入)"
                2 -> "SIM PUK (已锁定)"
                else -> ""
            }
            else -> ""
        }
        if (pinDisplay.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("SIM 状态"))
            addView(keyValueView("PIN 状态", pinDisplay, pinDisplay.contains("PUK") || pinDisplay.contains("SIM PIN")))
        }

        // 签约速率（AT+CGEQOSRDP=1）
        if (info.subscriptionRate.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("签约速率 (QoS)"))
            info.subscriptionRate.lines().forEach { line ->
                if (line.isNotBlank()) {
                    addView(keyValueView("", line.trim()))
                }
            }
        }

        // ── 网络注册状态（AT+CREG?）──
        if (info.lteRegistration.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("网络注册"))
            addView(keyValueView("状态", info.lteRegistration))
        }

        // ── 模块状态 ──
        var hasModule = false
        if (info.rfFunc.isNotEmpty()) {
            if (!hasModule) { addView(dividerView()); addView(sectionTitleView("模块状态")) }
            addView(keyValueView("射频", info.rfFunc)); hasModule = true
        }
        if (info.moduleState.isNotEmpty()) {
            if (!hasModule) { addView(dividerView()); addView(sectionTitleView("模块状态")) }
            addView(keyValueView("状态", info.moduleState)); hasModule = true
        }
        if (info.psAttached.isNotEmpty()) {
            if (!hasModule) { addView(dividerView()); addView(sectionTitleView("模块状态")) }
            addView(keyValueView("PS 域", info.psAttached)); hasModule = true
        }

        // ── 月流量明细（Goform 独立字段）──
        if (data.monthlyUploadBytes > 0 || data.monthlyDownloadBytes > 0) {
            addView(dividerView())
            addView(sectionTitleView("月流量明细"))
            if (data.monthlyDownloadBytes > 0) {
                val dlGb = data.monthlyDownloadBytes / (1024.0 * 1024.0 * 1024.0)
                addView(keyValueView("下行", String.format(Locale.getDefault(), "%.2f GB", dlGb)))
            }
            if (data.monthlyUploadBytes > 0) {
                val ulGb = data.monthlyUploadBytes / (1024.0 * 1024.0 * 1024.0)
                addView(keyValueView("上行", String.format(Locale.getDefault(), "%.2f GB", ulGb)))
            }
        }

        // 检查是否真的没有任何数据
        val hasNewAtData = info.moduleModel.isNotEmpty() || info.firmwareDetail.isNotEmpty()
            || info.lteRegistration.isNotEmpty() || info.wanIpAt.isNotEmpty() || info.dnsServers.isNotEmpty()
            || info.pinStatusAt.isNotEmpty() || info.rfFunc.isNotEmpty() || info.moduleState.isNotEmpty() || info.psAttached.isNotEmpty()
        val hasGoformIdData = data.goformImei.isNotEmpty() || data.goformImsi.isNotEmpty() || data.goformIccid.isNotEmpty()
            || data.hardwareVersion.isNotEmpty() || data.webVersion.isNotEmpty() || data.macAddress.isNotEmpty()
            || data.wanIp.isNotEmpty() || data.wanIpv6.isNotEmpty()
            || data.monthlyUploadBytes > 0 || data.monthlyDownloadBytes > 0 || data.pinStatusCode in 0..2

        if (info.rsrp <= Int.MIN_VALUE && info.sinr <= Int.MIN_VALUE && info.rsrq <= Int.MIN_VALUE
            && info.networkType.isEmpty() && info.operator.isEmpty() && !hasCell
            && info.imei.isEmpty() && info.subscriptionRate.isEmpty()
            && !hasNewAtData && !hasGoformIdData) {
            removeAllViews()
            addView(emptyHintView("AT 指令返回了空数据\n（设备可能暂未注册到网络）"))
        }
    }

    // ===== 弹窗 UI 辅助方法 =====

    private fun sectionTitleView(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(ThemeColors.textPrimary(this@MainActivity))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 6)
        }
    }

    private fun keyValueView(key: String, value: String, highlightRed: Boolean = false): TextView {
        return TextView(this).apply {
            text = "  $key :  $value"
            textSize = 13f
            setTextColor(if (highlightRed) 0xFFE53935.toInt()
                else ThemeColors.textPrimary(this@MainActivity))
            setPadding(0, 3, 0, 3)
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2
            )
            setBackgroundColor(ThemeColors.divider(this@MainActivity))
            alpha = 0.3f
        }
    }

    private fun emptyHintView(hint: String): TextView {
        return TextView(this).apply {
            text = hint
            textSize = 14f
            setTextColor(ThemeColors.textSecondary(this@MainActivity))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
    }

    /** 从 cpu_temp_list 取最高温度显示 */
    private fun getHighestTemp(data: WifiEntity): String {
        if (data.cpuTempList.isNotEmpty()) {
            val highest = data.cpuTempList.maxByOrNull { it.temp }?.temp ?: 0.0
            if (highest > 0) {
                val celsius = if (highest > 1000) highest / 1000.0 else highest
                return "%.1f℃".format(celsius)
            }
        }
        return data.temp.ifEmpty { "--" }
    }

    /** 电池：分离为主百分比和副信息（电流/电压/充电状态，仅当前>100mA时显示） */
    private fun buildBatteryParts(data: WifiEntity): Pair<String, String?> {
        val pct = data.battery.ifEmpty { null }
        val curStr = data.batteryCurrent.ifEmpty { null }
        val vol = data.batteryVoltage.ifEmpty { null }

        val main = pct ?: "--"

        // 解析电流数值，只有 >50mA 才显示充电详情
        val curMa = curStr?.let { parseCurrentMa(it) } ?: -1
        if (curMa > 50 && curStr != null && vol != null && vol != "--") {
            val sub = "⚡充电 $curStr · $vol"
            return Pair(main, sub)
        }

        return Pair(main, null)
    }

    /** 解析电流字符串 (如 "500mA") 返回数值 mA，解析失败返回 -1 */
    private fun parseCurrentMa(curStr: String): Int {
        return try {
            curStr.replace("mA", "").replace("A", "").trim().toFloatOrNull()?.toInt() ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun formatKb(kb: Long): String {
        return when {
            kb >= 1024 * 1024 -> String.format("%.2f GB", kb / (1024.0 * 1024.0))
            kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
            else -> "${kb} KB"
        }
    }

    private fun formatStorageGb(bytes: Long): String {
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ===== 更新检查与下载 =====

    /**
     * 弹窗显示更新详情（合并显示最新更新日志）
     */
    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = buildString {
            append("发现新版本: ${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) {
                append("更新日志:\n")
                append(UpdateChecker.formatChangelog(info.changelog))
                append("\n\n")
            }
            if (info.apkSize > 0) {
                append("大小: ${UpdateChecker.formatFileSize(info.apkSize)}")
            }
        }

        showCommonDialog(
            type = "update",
            title = "更新可用",
            iconRes = R.drawable.ic_check,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = message
                    textSize = 13f
                    setTextColor(ThemeColors.textPrimary(this@MainActivity))
                    setLineSpacing(0f, 1.4f)
                })
            },
            primaryBtnText = "下载更新",
            onPrimaryClick = { d ->
                d.dismiss()
                downloadAndInstall(info.apkUrl, info.tagName, info.apkSha256)
            },
            secondaryBtnText = "稍后"
        )
    }

    private fun downloadAndInstall(url: String, tag: String, sha256: String) {
        val finalUrl = UpdateChecker.applyMirrorToUrl(this, url)
        if (finalUrl.isBlank()) {
            Toast.makeText(this, "没有可下载的 APK", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
                return
            }
        }

        startDownload(url, tag, sha256)
    }

    private fun startDownload(url: String, tag: String, sha256: String) {
        val fileName = "UFITOOLS-Widget-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("UFITOOLS-Widget")
            .setDescription("正在下载 $tag 版本...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show()

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName, sha256)
                    unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(fileName: String, sha256: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (!file.exists()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // SHA256 校验
        if (sha256.isNotBlank()) {
            DebugLogger.logSys(TAG, "正在校验 APK SHA256...")
            if (!UpdateChecker.verifySha256(file, sha256)) {
                file.delete()
                Toast.makeText(this, "文件校验失败，已删除损坏文件\n请重新下载", Toast.LENGTH_LONG).show()
                DebugLogger.logExc(TAG, "APK SHA256 校验失败！")
                return
            }
            DebugLogger.logSys(TAG, "APK SHA256 校验通过")
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请重新点击下载", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        downloadReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
