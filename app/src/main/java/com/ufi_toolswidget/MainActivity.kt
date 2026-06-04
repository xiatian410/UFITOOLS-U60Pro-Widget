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
import android.graphics.RenderEffect
import android.graphics.Shader
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
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.UpdateChecker
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.util.WifiEntity
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import com.google.android.material.button.MaterialButton
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

    // 保存最后一次获取到的完整数据（用于弹窗详情）
    private var lastWifiEntity: WifiEntity? = null

    // 当前打开的详情弹窗（支持实时刷新）
    private var activeDialog: Dialog? = null
    private var activeDialogType: String? = null

    // 弹窗防拥堵：debounce 时间戳
    private var lastDialogClickTime = 0L

    // 加载 / 主内容状态
    private lateinit var loadingView: View
    private lateinit var mainContentView: View

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var autoRefreshJob: Job? = null

    override fun onResume() {
        super.onResume()
        DebugLogger.d(TAG, "onResume() called")
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToMainActivity(this)
        if (::tvModel.isInitialized) {
            // 先检测 Worker 是否因连续失败被停止
            checkWorkerFailureState()
            // 只有没有错误弹窗显示时才刷新数据
            if (activeDialogType != "error") {
                DebugLogger.d(TAG, "onResume: error dialog not visible, calling refreshData + startAutoRefreshTimer")
                refreshData()
                startAutoRefreshTimer()
            } else {
                DebugLogger.d(TAG, "onResume: error dialog IS visible, skipping refreshData")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.d(TAG, "onPause() called, stopping auto refresh")
        // 离开界面时停止自动刷新
        stopAutoRefreshTimer()
        dismissActiveDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            // 边缘到边缘显示，内容绘制在系统栏后方
            WindowCompat.setDecorFitsSystemWindows(window, false)
            setContentView(R.layout.activity_main)
            BackgroundUtil.applyWindowBackground(this)
            ThemeUtil.applyToMainActivity(this)
            initViews()
            displayAppVersion()
            // 不在此处调用 refreshData()，由 onResume 统一处理；
            // onResume 会先检查 stopped 状态再决定是否刷新
            startWorker(SPUtil.getRefreshInterval(this))
            // setupAnimations 推迟到数据加载成功后调用（main_content 初始为 GONE）
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "MainActivity onCreate crashed: ${e.message}", e)
            // 兜底：用最简布局避免白屏
            val errorView = TextView(this).apply {
                text = "加载界面失败\n\n${e.message}\n\n请清除应用数据后重试"
                textSize = 14f
                setPadding(40, 80, 40, 40)
                setTextColor(0xFF333333.toInt())
            }
            val scroll = ScrollView(this).apply { addView(errorView) }
            setContentView(scroll)
            Toast.makeText(this, "界面加载失败: ${e.message}", Toast.LENGTH_LONG).show()
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
            lastWifiEntity?.let { showNetworkDetailDialog(it) }
        }
        // 温度 点击 → 温度详情
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_temp)) {
            lastWifiEntity?.let { showTemperatureDialog(it) }
        }
        // CPU 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_cpu)) {
            lastWifiEntity?.let { showCpuDetailDialog(it) }
        }
        // 内存 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_mem)) {
            lastWifiEntity?.let { showMemDetailDialog(it) }
        }
        // 存储 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_storage)) {
            lastWifiEntity?.let { showStorageDetailDialog(it) }
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
        DebugLogger.d(TAG, "refreshData() started")
        lifecycleScope.launch {
            val data = WifiCrawl.getWifiData(this@MainActivity)
            if (data != null) {
                DebugLogger.d(TAG, "refreshData: API success, model=${data.model}")
                lastWifiEntity = data  // 保存完整数据供弹窗使用
                // 成功 → 清除所有失败计数（无论之前是否 stopped）
                WifiWorker.resetFailureState(this@MainActivity)
                // 隐藏加载界面 → 平滑交叉淡入淡出到主内容
                val wasLoading = loadingView.visibility == View.VISIBLE
                if (activeDialogType == "error") {
                    dismissActiveDialog()
                }
                if (wasLoading) {
                    // 加载界面淡出 + 主内容淡入（交叉渐变）
                    loadingView.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .withEndAction {
                            loadingView.visibility = View.GONE
                            loadingView.alpha = 1f // reset for next use
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
                    // 首次加载成功 → 交错入场动画
                    setupAnimations()
                } else {
                    if (loadingView.visibility == View.VISIBLE) loadingView.visibility = View.GONE
                    mainContentView.visibility = View.VISIBLE
                    mainContentView.alpha = 1f
                }

                // 更新主界面数据（实时变动部分采用柔和模糊/淡入切换）
                val deviceName = data.deviceModel.ifEmpty { data.model }
                if (tvModel.text != deviceName) tvModel.text = deviceName
                try {
                    val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
                    val subText = "软件 v$appVersion"
                    if (tvSubtitle.text != subText) tvSubtitle.text = subText
                } catch (_: Exception) {}

                // 实时状态数据：使用 smoothUpdateText 提供模糊或淡入特效
                val newNetText = data.atNetworkInfo?.networkType?.ifEmpty { data.netType.ifEmpty { "--" } }
                    ?: data.netType.ifEmpty { "--" }
                AnimationUtil.smoothUpdateText(tvNetSignal, newNetText)
                AnimationUtil.smoothUpdateText(tvTemp, getHighestTemp(data))
                AnimationUtil.smoothUpdateText(tvCpu, data.cpu.ifEmpty { "--" })
                AnimationUtil.smoothUpdateText(tvMem, data.mem.ifEmpty { "--" })
                AnimationUtil.smoothUpdateText(tvDaily, data.dailyFlow.ifEmpty { "--" })
                AnimationUtil.smoothUpdateText(tvFlow, data.flow.ifEmpty { "--" })

                // 电池：百分比为主，电流/电压为副
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

                SPUtil.saveData(this@MainActivity, data)
                BaseWifiWidget.renderAllWidgets(this@MainActivity)
                // 刷新已打开的详情弹窗
                refreshActiveDialog(data)
            } else {
                // ====== 前台刷新失败 → 累加失败计数器，与 Worker 共用 ======
                val error = WifiCrawl.lastError
                val sp = this@MainActivity.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)

                // 判断是网络不通还是 API 错误
                val isNetworkError = error.contains("Network error", ignoreCase = true) ||
                        error.contains("timeout", ignoreCase = true) ||
                        error.contains("connect", ignoreCase = true) ||
                        error.contains("refused", ignoreCase = true)

                if (isNetworkError) {
                    val netFails = sp.getInt(WifiWorker.KEY_NETWORK_FAILURE_COUNT, 0) + 1
                    sp.edit().putInt(WifiWorker.KEY_NETWORK_FAILURE_COUNT, netFails).apply()
                    DebugLogger.w(TAG, "refreshData: network error, netFails=$netFails/${WifiWorker.NETWORK_MAX_FAILURES}")

                    if (netFails >= WifiWorker.NETWORK_MAX_FAILURES) {
                        sp.edit()
                            .putBoolean(WifiWorker.KEY_WORKER_STOPPED, true)
                            .putInt(WifiWorker.KEY_API_FAILURE_COUNT, 0)
                            .apply()
                        SPUtil.setWorkerStopReason(this@MainActivity, WifiWorker.REASON_NETWORK)
                        DebugLogger.e(TAG, "refreshData: network threshold reached, showing error dialog")
                        BaseWifiWidget.renderAllWidgets(this@MainActivity)
                        showErrorDialog()
                        return@launch
                    }
                } else {
                    val apiFails = sp.getInt(WifiWorker.KEY_API_FAILURE_COUNT, 0) + 1
                    sp.edit().putInt(WifiWorker.KEY_API_FAILURE_COUNT, apiFails).apply()
                    DebugLogger.w(TAG, "refreshData: API error, apiFails=$apiFails/${WifiWorker.API_MAX_FAILURES}, error=$error")

                    if (apiFails >= WifiWorker.API_MAX_FAILURES) {
                        sp.edit().putBoolean(WifiWorker.KEY_WORKER_STOPPED, true).apply()
                        SPUtil.setWorkerStopReason(this@MainActivity, WifiWorker.REASON_API)
                        DebugLogger.e(TAG, "refreshData: API threshold reached, showing error dialog")
                        BaseWifiWidget.renderAllWidgets(this@MainActivity)
                        showErrorDialog()
                        return@launch
                    }
                }

                // 还没达到阈值 → 只显示 Toast 提示
                if (error.contains("401") || error.contains("Unauthorized")) {
                    Toast.makeText(this@MainActivity, "访问受限，请在设置中检查管理口令", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "同步失败: ${error.ifEmpty { "网络超时" }}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 显示错误状态弹窗
     */
    private fun showErrorDialog() {
        if (activeDialogType == "error") return
        dismissActiveDialog()

        val reason = SPUtil.getWorkerStopReason(this)
        val address = SPUtil.getDeviceAddress(this)

        DebugLogger.w(TAG, "showErrorDialog: reason=$reason, target=$address")

        val dialog = createThemedDialog(R.layout.dialog_error_state, dialogType = "error")
        dialog.setCancelable(false)

        val titleTv = dialog.findViewById<TextView>(R.id.dialog_error_title)
        val msgTv = dialog.findViewById<TextView>(R.id.dialog_error_message)
        val targetTv = dialog.findViewById<TextView>(R.id.dialog_error_target)
        val hintTv = dialog.findViewById<TextView>(R.id.dialog_error_hint)
        val iconIv = dialog.findViewById<ImageView>(R.id.dialog_error_icon)

        targetTv.text = address

        when (reason) {
            WifiWorker.REASON_NETWORK -> {
                titleTv.text = "无法与设备通信"
                msgTv.text = "无法连接到设备 ($address)，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备未开机或不在同一内网\n" +
                        "• 设备 IP 地址配置有误\n" +
                        "• 设备网络异常"
                hintTv.text = "设备恢复上线后将自动恢复刷新"
                iconIv.setImageResource(R.drawable.ic_router_off)
            }
            WifiWorker.REASON_API -> {
                titleTv.text = "连接配置异常"
                msgTv.text = "设备网络可达，但 API 请求连续失败，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备端口配置错误\n" +
                        "• Token / 管理口令不正确或已更改\n" +
                        "• 设备 API 服务异常"
                hintTv.text = "请修改配置后点击「重新连接」重试"
                iconIv.setImageResource(R.drawable.ic_router_off)
            }
            else -> {
                titleTv.text = "连接失败"
                msgTv.text = "连续多次无法连接到设备，后台刷新已自动暂停以节省电量。\n\n" +
                        "请检查设备状态和连接配置后重试。"
                hintTv.text = "设备恢复上线后将自动恢复刷新"
                iconIv.setImageResource(R.drawable.ic_router_off)
            }
        }

        dialog.findViewById<View>(R.id.dialog_error_btn_retry).setOnClickListener {
            WifiWorker.resetFailureState(this@MainActivity)
            BaseWifiWidget.renderAllWidgets(this@MainActivity)
            dialog.dismiss()
            loadingView.visibility = View.VISIBLE
            mainContentView.visibility = View.GONE
            stopAutoRefreshTimer()
            refreshData()
        }

        dialog.findViewById<View>(R.id.dialog_error_btn_config).setOnClickListener {
            startActivity(Intent(this@MainActivity, ConfigModifyActivity::class.java))
        }

        dialog.show()
        stopAutoRefreshTimer()
    }

    private fun startWorker(minutes: Int) {
        DebugLogger.d(TAG, "startWorker: minutes=$minutes")
        val workManager = WorkManager.getInstance(this)
        if (minutes <= 0) {
            DebugLogger.d(TAG, "startWorker: minutes<=0, cancelling worker")
            workManager.cancelUniqueWork("wifi_crawl")
            return
        }
        // 只在 Worker 当前未 stopped 时才重置 — 如果已 stopped，保持 stopped 状态让用户看到错误界面
        // Worker 内部自己会处理恢复逻辑（ping 通过后自动解除 stopped）
        val wasStopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.d(TAG, "startWorker: wasStopped=$wasStopped")
        if (!wasStopped) {
            WifiWorker.resetFailureState(this)
        } else {
            DebugLogger.d(TAG, "startWorker: worker is stopped, NOT resetting failure state")
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
        DebugLogger.d(TAG, "checkWorkerFailureState: stopped=$stopped")

        if (!stopped) {
            // Worker 正常 → 如果错误弹窗已显示则关闭
            if (activeDialogType == "error") {
                DebugLogger.i(TAG, "checkWorkerFailureState: stopped=false, dismissing error dialog")
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
        if (activeDialog?.isShowing == true && activeDialogType == dialogType) {
            return activeDialog!!
        }
        // 不同类型 → 关闭旧的，创建新的
        dismissActiveDialog()

        val dialog = object : Dialog(this) {
            private var isDismissing = false

            fun realDismiss() {
                super.dismiss()
                activeDialog = null
                activeDialogType = null
            }

            override fun dismiss() {
                if (isDismissing || window == null) {
                    realDismiss()
                    return
                }
                isDismissing = true
                // 立即清除类型标记，允许下一个弹窗点击立即响应
                activeDialogType = null 

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
        applyThemeToDialogRoot(dialog)

        dialog.window?.apply {
            // 透明背景 + 无黑色遮罩
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)

            // 应用弹性缩放进出场动画
            setWindowAnimations(R.style.DialogAnimationTheme)

            setLayout(
                (resources.displayMetrics.widthPixels * widthRatio).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── 动态模糊背景：API 31+ 原生模糊，API 26-30 bitmap 缩放模糊 ──
        applyDialogBlur(dialog)

        dialog.setOnDismissListener {
            activeDialog = null
            activeDialogType = null
        }
        activeDialog = dialog
        activeDialogType = dialogType
        return dialog
    }

    /** 应用动态模糊背景到弹窗窗口，支持步进渐变 */
    private fun applyDialogBlur(dialog: Dialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurIn(dialog)
        } else {
            // Android 8.0-11: 截取 Activity 并缩放实现模糊
            applyLegacyBlur(dialog)
        }
    }

    /** API 26-30：通过截屏 + 多级缩放模拟背景模糊（类似毛玻璃效果） */
    private fun applyLegacyBlur(dialog: Dialog) {
        try {
            val rootView = window.decorView.rootView
            val vw = rootView.width
            val vh = rootView.height
            if (vw <= 0 || vh <= 0) return

            // 1. 截取当前 Activity 渲染
            val capture = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(capture))

            // 2. 大幅缩小（6%）→ 丢失细节 = 模糊
            val smallW = (vw * 0.06f).toInt().coerceAtLeast(4)
            val smallH = (vh * 0.06f).toInt().coerceAtLeast(4)
            val small = Bitmap.createScaledBitmap(capture, smallW, smallH, true)
            capture.recycle()

            // 3. 放大回原始尺寸（双线性插值 → 平滑渐变）
            val blurred = Bitmap.createScaledBitmap(small, vw, vh, true)
            small.recycle()

            dialog.window?.setBackgroundDrawable(BitmapDrawable(resources, blurred))
        } catch (e: Exception) {
            Log.w(TAG, "Legacy blur failed: ${e.message}")
        }
    }

    /** 对弹窗根布局动态应用当前主题色 + 淡淡阴影边框 */
    private fun applyThemeToDialogRoot(dialog: Dialog) {
        val root = dialog.findViewById<ViewGroup>(android.R.id.content)
            ?.let { if (it.childCount > 0) it.getChildAt(0) as? ViewGroup else it } ?: return
        val ctx = this@MainActivity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val accentSecondary = ThemeColors.accentSecondary(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // 根布局背景 → 主题卡片色 + 2dp 明显描边（增强立体感）
        val borderColor = if (SPUtil.getNightMode(ctx) == AppCompatDelegate.MODE_NIGHT_YES)
            0x4DFFFFFF.toInt() else 0x35000000
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = 16f
            setStroke(2, borderColor)
        }
        // 弹窗 elevation 阴影加重
        root.elevation = 24f

        // 遍历子控件着色
        applyThemeToViewTree(root, textPrimary, textSecondary, accent, accentSecondary, cardBg)
    }

    /** 递归遍历 Dialog 视图树，应用主题色 */
    private fun applyThemeToViewTree(
        parent: ViewGroup,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        accentSecondary: Int,
        cardBg: Int
    ) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is MaterialButton -> {
                    if (child.strokeWidth > 0) {
                        // 描边按钮（如 "稍后"、"修改连接配置"）
                        child.setTextColor(textPrimary)
                        child.strokeColor = ColorStateList.valueOf(textSecondary)
                        child.iconTint = ColorStateList.valueOf(accent)
                    } else {
                        // 实色按钮（如 "关闭"、"下载更新"、"重新连接"）
                        child.backgroundTintList = ColorStateList.valueOf(accent)
                        child.setTextColor(0xFFFFFFFF.toInt())
                        child.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    }
                }
                is Button -> {
                    // 普通按钮（如 SetupActivity 里的确认按钮）
                    child.backgroundTintList = ColorStateList.valueOf(accent)
                    child.setTextColor(0xFFFFFFFF.toInt())
                }
                is TextView -> {
                    // 小字(≤13sp) → 辅色，大字 → 主色
                    child.setTextColor(if (child.textSize <= 13f) textSecondary else textPrimary)
                }
                is ImageView -> {
                    // 标题图标 → 强调色
                    child.setColorFilter(accent)
                }
                is ViewGroup -> {
                    // 递归处理子 ViewGroup，但跳过动态内容容器（由代码填充）
                    val id = child.id
                    if (id != R.id.dialog_temp_content &&
                        id != R.id.dialog_cpu_content &&
                        id != R.id.dialog_mem_content &&
                        id != R.id.dialog_storage_content) {
                        applyThemeToViewTree(child, textPrimary, textSecondary, accent, accentSecondary, cardBg)
                    }
                }
            }
        }
    }

    /** 弹窗防拥堵检查：返回 true 允许创建/显示 */
    private fun checkDialogDebounce(): Boolean {
        // 如果当前已有正在显示的弹窗且不是 error 弹窗，禁止开启新的
        // 增加 null 判定，确保关闭过程中也能及时重置状态
        val isShowing = activeDialog?.isShowing == true
        if (isShowing && activeDialogType != "error") {
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
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        activeDialogType = null
    }

    /** 数据刷新后自动更新已打开的弹窗内容 */
    private fun refreshActiveDialog(data: WifiEntity) {
        if (activeDialog == null || !activeDialog!!.isShowing) {
            activeDialog = null
            activeDialogType = null
            return
        }
        when (activeDialogType) {
            "temperature" -> {
                val content = activeDialog!!.findViewById<LinearLayout>(R.id.dialog_temp_content)
                // 精细化缓存：使用 hashCode 校验数据对象是否有任何变化
                val currentHash = data.cpuTempList.hashCode().toLong()
                if (content.tag != currentHash) {
                    content.clearAndFill { fillTemperature(data) }
                    content.tag = currentHash
                }
            }
            "cpu" -> {
                val content = activeDialog!!.findViewById<LinearLayout>(R.id.dialog_cpu_content)
                // 精细化缓存：校验频率和使用率的完整 hashCode
                val currentHash = data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
                if (content.tag != currentHash) {
                    content.clearAndFill { fillCpuDetail(data) }
                    content.tag = currentHash
                }
            }
            "mem" -> {
                val content = activeDialog!!.findViewById<LinearLayout>(R.id.dialog_mem_content)
                // 精细化缓存：校验内存所有关键数值
                val currentHash = data.memUsedKb + data.memAvailableKb + data.swapUsedKb
                if (content.tag != currentHash) {
                    content.clearAndFill { fillMemDetail(data) }
                    content.tag = currentHash
                }
            }
            "storage" -> {
                val content = activeDialog!!.findViewById<LinearLayout>(R.id.dialog_storage_content)
                // 精细化缓存：校验内外存储已用量
                val currentHash = data.internalUsedStorage + data.externalUsedStorage
                if (content.tag != currentHash) {
                    content.clearAndFill { fillStorageDetail(data) }
                    content.tag = currentHash
                }
            }
            "network" -> {
                val content = activeDialog!!.findViewById<LinearLayout>(R.id.dialog_network_content)
                // 精细化缓存：校验 AT 网络详情对象的完整 hashCode
                val currentHash = data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
                if (content.tag != currentHash) {
                    content.clearAndFill { fillNetworkDetail(data) }
                    content.tag = currentHash
                }
            }
        }
    }

    // ---------- 各弹窗 ----------

    private fun showNetworkDetailDialog(data: WifiEntity) {
        if (!checkDialogDebounce()) return
        val dialog = createThemedDialog(R.layout.dialog_network_detail, dialogType = "network")
        val content = dialog.findViewById<LinearLayout>(R.id.dialog_network_content)
        dialog.findViewById<View>(R.id.dialog_network_btn_close).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        // 初始填充并设置完整数据 Hash 缓存
        content.clearAndFill { fillNetworkDetail(data) }
        content.tag = data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
        dialog.show()
    }

    private fun showTemperatureDialog(data: WifiEntity) {
        if (!checkDialogDebounce()) return
        val dialog = createThemedDialog(R.layout.dialog_temperature, dialogType = "temperature")
        val content = dialog.findViewById<LinearLayout>(R.id.dialog_temp_content)
        dialog.findViewById<View>(R.id.dialog_temp_btn_close).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        // 初始填充并设置完整数据 Hash 缓存
        content.clearAndFill { fillTemperature(data) }
        content.tag = data.cpuTempList.hashCode().toLong()
        dialog.show()
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
        if (!checkDialogDebounce()) return
        val dialog = createThemedDialog(R.layout.dialog_cpu_detail, dialogType = "cpu")
        val content = dialog.findViewById<LinearLayout>(R.id.dialog_cpu_content)
        dialog.findViewById<View>(R.id.dialog_cpu_btn_close).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        // 初始填充并设置完整数据 Hash 缓存
        content.clearAndFill { fillCpuDetail(data) }
        content.tag = data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
        dialog.show()
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
        if (!checkDialogDebounce()) return
        val dialog = createThemedDialog(R.layout.dialog_mem_detail, dialogType = "mem")
        val content = dialog.findViewById<LinearLayout>(R.id.dialog_mem_content)
        dialog.findViewById<View>(R.id.dialog_mem_btn_close).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        // 初始填充并设置缓存标识
        content.clearAndFill { fillMemDetail(data) }
        content.tag = data.memUsedKb + data.memAvailableKb + data.swapUsedKb
        dialog.show()
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
        if (!checkDialogDebounce()) return
        val dialog = createThemedDialog(R.layout.dialog_storage_detail, dialogType = "storage")
        val content = dialog.findViewById<LinearLayout>(R.id.dialog_storage_content)
        dialog.findViewById<View>(R.id.dialog_storage_btn_close).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        // 初始填充并设置缓存标识
        content.clearAndFill { fillStorageDetail(data) }
        content.tag = data.internalUsedStorage + data.externalUsedStorage
        dialog.show()
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
        if (info.operator.isNotEmpty()) {
            addView(keyValueView("运营商", info.operator))
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

        // 频段 / PCI / EARFCN
        var hasCell = false
        if (info.band.isNotEmpty() || info.pci >= 0 || info.earfcn >= 0) {
            addView(dividerView())
            addView(sectionTitleView("小区信息"))
            if (info.band.isNotEmpty()) { addView(keyValueView("频段", info.band)); hasCell = true }
            if (info.pci >= 0) { addView(keyValueView("PCI", info.pci.toString())); hasCell = true }
            if (info.earfcn >= 0) { addView(keyValueView("ARFCN", info.earfcn.toString())); hasCell = true }
        }

        // IMEI（AT+CGSN）
        if (info.imei.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("设备标识"))
            addView(keyValueView("IMEI", info.imei))
        }

        // 签约速率（AT+CGEQOSRDP=1）
        if (info.subscriptionRate.isNotEmpty()) {
            addView(dividerView())
            addView(sectionTitleView("签约速率 (QoS)"))
            // 可能包含多行（上/下行），逐行显示
            info.subscriptionRate.lines().forEach { line ->
                if (line.isNotBlank()) {
                    addView(keyValueView("", line.trim()))
                }
            }
        }

        if (info.rsrp <= Int.MIN_VALUE && info.sinr <= Int.MIN_VALUE && info.rsrq <= Int.MIN_VALUE
            && info.networkType.isEmpty() && info.operator.isEmpty() && !hasCell
            && info.imei.isEmpty() && info.subscriptionRate.isEmpty()) {
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

        val dialog = createThemedDialog(R.layout.dialog_update, dialogType = "update")
        dialog.findViewById<TextView>(R.id.dialog_update_message).text = message
        dialog.findViewById<View>(R.id.dialog_update_btn_download).apply {
            AnimationUtil.applyScaleClickAnimation(this) {
                dialog.dismiss()
                downloadAndInstall(info.apkUrl, info.tagName, info.apkSha256)
            }
        }
        dialog.findViewById<View>(R.id.dialog_update_btn_later).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dialog.dismiss() }
        }
        dialog.show()
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
            DebugLogger.d(TAG, "正在校验 APK SHA256...")
            if (!UpdateChecker.verifySha256(file, sha256)) {
                file.delete()
                Toast.makeText(this, "文件校验失败，已删除损坏文件\n请重新下载", Toast.LENGTH_LONG).show()
                DebugLogger.e(TAG, "APK SHA256 校验失败！")
                return
            }
            DebugLogger.d(TAG, "APK SHA256 校验通过")
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
        downloadReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
