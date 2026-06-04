package com.ufi_toolswidget

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConfigModifyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_config_modify)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToFormPage(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val etDeviceAddress = findViewById<EditText>(R.id.et_device_address)
        val etToken = findViewById<EditText>(R.id.et_token)

        // 恢复已有配置
        etDeviceAddress.setText(SPUtil.getDeviceAddress(this))
        val savedToken = SPUtil.getRawToken(this)
        etToken.setText(if (savedToken == "admin") "" else savedToken)

        findViewById<View>(R.id.btn_save).apply {
            setOnClickListener {
                val address = etDeviceAddress.text.toString().trim().ifEmpty { SPUtil.DEFAULT_DEVICE_ADDRESS }
                val token = etToken.text.toString().trim().ifEmpty { "admin" }

                SPUtil.setDeviceAddress(this@ConfigModifyActivity, address)
                SPUtil.saveRawToken(this@ConfigModifyActivity, token)
                SPUtil.saveAuthToken(this@ConfigModifyActivity, NetUtil.sha256(token))

                // 配置已变更 → 重置 worker 失败状态，允许立即恢复刷新
                WifiWorker.resetFailureState(this@ConfigModifyActivity)

                // 后台自动探测协议（域名填的 http 还是 https）
                triggerProtocolProbe()

                BaseWifiWidget.renderAllWidgets(this@ConfigModifyActivity)
                Toast.makeText(this@ConfigModifyActivity, "连接配置已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
            setOnTouchListener(ScaleTouchListener())
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToFormPage(this)
    }

    /** 后台自动探测协议（HTTPS 优先 → HTTP 回退），结果存入 SP */
    private fun triggerProtocolProbe() {
        if (!SPUtil.needsProtocolProbe(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = WifiCrawl.probeProtocol(this@ConfigModifyActivity)
            if (result != null) {
                SPUtil.setDeviceProtocol(this@ConfigModifyActivity, result)
                android.util.Log.d("ConfigModify", "Protocol auto-detected: $result")
            }
        }
    }
}
