package com.ufi_toolswidget

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.launch
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
    private lateinit var tvPower: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvClientIp: TextView

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToMainActivity(this)
        if (::tvModel.isInitialized) {
            refreshData()
        }
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

        try {
            setContentView(R.layout.activity_main)
            BackgroundUtil.applyWindowBackground(this)
            ThemeUtil.applyToMainActivity(this)
            initViews()
            displayAppVersion()
            refreshData()
            startWorker(SPUtil.getRefreshInterval(this))
            setupAnimations()
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
        val cards = listOf(
            findViewById<View>(R.id.main_header),
            findViewById<View>(R.id.card_network),
            findViewById<View>(R.id.card_device)
        )
        
        cards.forEachIndexed { index, view ->
            view?.let {
                it.alpha = 0f
                it.translationY = 50f
                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setStartDelay(index * 100L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
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
        tvPower = findViewById(R.id.main_tv_power)
        tvStorage = findViewById(R.id.main_tv_storage)
        tvClientIp = findViewById(R.id.main_tv_client_ip)

        findViewById<View>(R.id.btn_settings).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            setOnTouchListener(ScaleTouchListener())
        }

        findViewById<View>(R.id.btn_check_update).apply {
            setOnClickListener {
                refreshData()
                Toast.makeText(this@MainActivity, "正在同步最新状态...", Toast.LENGTH_SHORT).show()
            }
            setOnTouchListener(ScaleTouchListener())
        }
    }

    private inner class ScaleTouchListener : View.OnTouchListener {
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                    v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            return true
        }
    }

    private fun refreshData() {
        lifecycleScope.launch {
            val data = WifiCrawl.getWifiData(this@MainActivity)
            if (data != null) {
                // 执行一个简单的淡出淡入效果
                val fadeOut = android.view.animation.AlphaAnimation(1.0f, 0.5f).apply { duration = 200 }
                val fadeIn = android.view.animation.AlphaAnimation(0.5f, 1.0f).apply { duration = 300 }
                
                listOf(tvNetSignal, tvTemp, tvCpu, tvMem, tvDaily, tvFlow).forEach { it.startAnimation(fadeOut) }

                val deviceName = data.deviceModel.ifEmpty { data.model }
                tvModel.text = deviceName

                tvNetSignal.text = "${data.netType.ifEmpty { "--" }} | ${data.signal.ifEmpty { "--" }}"
                tvTemp.text = data.temp.ifEmpty { "--" }
                tvCpu.text = data.cpu.ifEmpty { "--" }
                tvMem.text = data.mem.ifEmpty { "--" }
                tvDaily.text = data.dailyFlow.ifEmpty { "--" }
                tvFlow.text = data.flow.ifEmpty { "--" }
                
                tvFirmware.text = data.firmwareVer.ifEmpty { "--" }
                tvPower.text = "${data.batteryCurrent.ifEmpty { "--" }}  /  ${data.batteryVoltage.ifEmpty { "--" }}"
                tvStorage.text = data.internalStorage.ifEmpty { "--" }
                tvClientIp.text = data.clientIp.ifEmpty { "--" }

                listOf(tvNetSignal, tvTemp, tvCpu, tvMem, tvDaily, tvFlow).forEach { it.startAnimation(fadeIn) }

                SPUtil.saveData(this@MainActivity, data)
                BaseWifiWidget.renderAllWidgets(this@MainActivity)
            } else {
                val error = WifiCrawl.lastError
                // 只有在真正获取失败且可能是密码问题时才提示
                if (error.contains("401") || error.contains("Unauthorized")) {
                    Toast.makeText(this@MainActivity, "访问受限，请在设置中检查管理口令", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "同步失败: ${error.ifEmpty { "网络超时" }}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startWorker(minutes: Int) {
        val workManager = WorkManager.getInstance(this)
        if (minutes <= 0) {
            workManager.cancelUniqueWork("wifi_crawl")
            return
        }
        val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            "wifi_crawl",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}
