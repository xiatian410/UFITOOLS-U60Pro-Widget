package com.ufi_toolswidget

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.*
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvModel: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvFlow: TextView
    private lateinit var tvDaily: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvSys: TextView
    private lateinit var tvDebug: TextView
    // 新增
    private lateinit var tvCurrent: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvClientIp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        refreshData()
    }

    private fun initViews() {
        tvModel = findViewById(R.id.main_tv_model)
        tvBattery = findViewById(R.id.main_tv_battery)
        tvFlow = findViewById(R.id.main_tv_flow)
        tvDaily = findViewById(R.id.main_tv_daily)
        tvSignal = findViewById(R.id.main_tv_signal)
        tvTemp = findViewById(R.id.main_tv_temp)
        tvSys = findViewById(R.id.main_tv_sys)
        tvDebug = findViewById(R.id.tv_debug_log)
        // 新增
        tvCurrent = findViewById(R.id.main_tv_current)
        tvVoltage = findViewById(R.id.main_tv_voltage)
        tvStorage = findViewById(R.id.main_tv_storage)
        tvClientIp = findViewById(R.id.main_tv_client_ip)

        val etToken = findViewById<EditText>(R.id.et_token)
        val cbFlow = findViewById<CheckBox>(R.id.cb_show_flow)
        val cbSignal = findViewById<CheckBox>(R.id.cb_show_signal)
        val cbTemp = findViewById<CheckBox>(R.id.cb_show_temp)

        val btnRefresh = findViewById<Button>(R.id.btn_refresh)
        val btnSave = findViewById<Button>(R.id.btn_save_settings)
        val btnCopy = findViewById<Button>(R.id.btn_copy_logs)

        etToken.setText(SPUtil.getRawToken(this))
        cbFlow.isChecked = SPUtil.getShowFlow(this)
        cbSignal.isChecked = SPUtil.getShowSignal(this)
        cbTemp.isChecked = SPUtil.getShowTemp(this)

        btnRefresh.setOnClickListener { refreshData() }

        btnSave.setOnClickListener {
            val rawToken = etToken.text.toString().trim()
            if (rawToken.isNotEmpty()) {
                val auth = NetUtil.sha256(rawToken)
                SPUtil.saveRawToken(this, rawToken)
                SPUtil.saveAuthToken(this, auth)
                SPUtil.setWidgetSettings(this, cbFlow.isChecked, cbSignal.isChecked, cbTemp.isChecked)
                
                updateAllWidgets()
                startWorker()
                refreshData()
                Toast.makeText(this, "配置已保存并同步所有小组件", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Logs", tvDebug.text))
            Toast.makeText(this, "已复制日志", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshData() {
        tvDebug.text = "正在同步数据..."
        lifecycleScope.launch {
            val data = WifiCrawl.getWifiData(this@MainActivity)
            if (data != null) {
                tvModel.text = "设备：${data.model}"
                tvBattery.text = "电量：${data.battery}"
                tvFlow.text = "月流量：${data.flow}"
                tvDaily.text = "日流量：${data.dailyFlow}"
                tvSignal.text = "信号：${data.signal} (${data.netType})"
                tvTemp.text = "温度：${data.temp}"
                tvSys.text = "CPU: ${data.cpu} | 内存: ${data.mem}"
                tvCurrent.text = "电流：${data.batteryCurrent}"
                tvVoltage.text = "电压：${data.batteryVoltage}"
                tvStorage.text = "存储：${data.internalStorage}"
                tvClientIp.text = "IP：${data.clientIp}"
                tvDebug.text = "采集成功\nv${data.appVer}\nRaw Data: ${WifiCrawl.lastRawResponse}"
                SPUtil.saveData(this@MainActivity, data)
                updateAllWidgets()
            } else {
                tvDebug.text = "采集失败\nError: ${WifiCrawl.lastError}\nResponse: ${WifiCrawl.lastRawResponse}"
            }
        }
    }

    private fun updateAllWidgets() {
        val widgetClasses = arrayOf(
            WifiWidget1x1::class.java,
            WifiWidget1x2::class.java,
            WifiWidget1x3::class.java,
            WifiWidget2x1::class.java,
            WifiWidget2x2::class.java,
            WifiWidget2x3::class.java,
            WifiWidget3x1::class.java,
            WifiWidget3x2::class.java,
            WifiWidget3x3::class.java
        )
        for (clazz in widgetClasses) {
            val intent = Intent(this, clazz).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(applicationContext)
                    .getAppWidgetIds(ComponentName(applicationContext, clazz))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            sendBroadcast(intent)
        }
    }

    private fun startWorker() {
        val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "wifi_crawl",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }
}
