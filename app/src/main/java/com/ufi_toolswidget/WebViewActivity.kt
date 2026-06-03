package com.ufi_toolswidget

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.NetUtil
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class WebViewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        BackgroundUtil.applyWindowBackground(this)

        val webView = findViewById<WebView>(R.id.webview)
        val btnFinish = findViewById<Button>(R.id.btn_finish_webview)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl(NetUtil.BASE_URL)

        btnFinish.setOnClickListener {
            syncCookiesToOkHttp()
            Toast.makeText(this, "会话已同步", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
    }

    private fun syncCookiesToOkHttp() {
        val cookieManager = CookieManager.getInstance()
        val cookiesString = cookieManager.getCookie(NetUtil.BASE_URL)
        if (cookiesString != null) {
            val url = NetUtil.BASE_URL.toHttpUrl()
            val cookieList = mutableListOf<Cookie>()
            val cookiePairs = cookiesString.split(";")
            for (pair in cookiePairs) {
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .build()
                    cookieList.add(cookie)
                }
            }
            NetUtil.saveCookies(cookieList)
        }
    }
}
