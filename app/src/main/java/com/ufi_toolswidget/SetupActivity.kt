package com.ufi_toolswidget

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_setup)
            BackgroundUtil.applyWindowBackground(this)

            val etBaseUrl = findViewById<EditText>(R.id.et_base_url)
            val etToken = findViewById<EditText>(R.id.et_token)

            // 恢复已有配置
            val savedUrl = SPUtil.getBaseUrl(this)
            val savedToken = SPUtil.getRawToken(this)
            if (savedToken != "admin") {
                etToken.setText(savedToken)
            }
            if (savedUrl != "http://192.168.0.1:2333/") {
                etBaseUrl.setText(savedUrl)
            }

            findViewById<View>(R.id.btn_setup_confirm).setOnClickListener {
                val url = etBaseUrl.text.toString().trim().ifEmpty { "http://192.168.0.1:2333/" }
                val token = etToken.text.toString().trim().ifEmpty { "admin" }

                SPUtil.setBaseUrl(this, url)
                SPUtil.saveRawToken(this, token)
                SPUtil.saveAuthToken(this, NetUtil.sha256(token))
                SPUtil.setFirstRun(this, false)

                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }

            findViewById<View>(R.id.tv_skip).setOnClickListener {
                SPUtil.setFirstRun(this, false)
                Toast.makeText(this, "已使用默认配置", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "SetupActivity onCreate crashed: ${e.message}", e)
            // 兜底：跳过配置直接用默认值
            SPUtil.setFirstRun(this, false)
            Toast.makeText(this, "配置界面异常，已使用默认配置", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
    }
}
