package com.ufi_toolswidget

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.util.BackgroundUtil

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        BackgroundUtil.applyWindowBackground(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 显示版本号
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tv_app_version).text = "版本 ${info.versionName}"
        } catch (_: PackageManager.NameNotFoundException) {}

        // GitHub 链接点击
        findViewById<View>(R.id.card_github).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asunano/UFITOOLS-Widget"))
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
    }
}
