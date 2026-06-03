package com.ufi_toolswidget

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.widget.BaseWifiWidget

class ConfigModifyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_modify)
        BackgroundUtil.applyWindowBackground(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val etBaseUrl = findViewById<EditText>(R.id.et_base_url)
        val etToken = findViewById<EditText>(R.id.et_token)

        // 恢复已有配置
        etBaseUrl.setText(SPUtil.getBaseUrl(this))
        val savedToken = SPUtil.getRawToken(this)
        etToken.setText(if (savedToken == "admin") "" else savedToken)

        findViewById<View>(R.id.btn_save).setOnClickListener {
            val url = etBaseUrl.text.toString().trim().ifEmpty { "http://192.168.0.1:2333/" }
            val token = etToken.text.toString().trim().ifEmpty { "admin" }

            SPUtil.setBaseUrl(this, url)
            SPUtil.saveRawToken(this, token)
            SPUtil.saveAuthToken(this, NetUtil.sha256(token))

            BaseWifiWidget.renderAllWidgets(this)
            Toast.makeText(this, "连接配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
    }
}
