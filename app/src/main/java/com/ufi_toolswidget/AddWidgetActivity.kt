package com.ufi_toolswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.widget.WifiWidget4x2

class AddWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val widgetSize = intent.getStringExtra("widget_size") ?: "4x2"
        Log.d("AddWidget", "Requesting size: $widgetSize")

        val widgetClass = when (widgetSize) {
            "4x2" -> WifiWidget4x2::class.java
            else -> WifiWidget4x2::class.java
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val appWidgetManager = getSystemService(AppWidgetManager::class.java)
                if (appWidgetManager?.isRequestPinAppWidgetSupported == true) {
                    val intent = Intent(this, WidgetAddedReceiver::class.java).apply {
                        putExtra("widget_size", widgetSize)
                    }
                    val callback = PendingIntent.getBroadcast(
                        this, widgetSize.hashCode(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    appWidgetManager.requestPinAppWidget(ComponentName(this, widgetClass), null, callback)
                    val displaySize = widgetSize
                    Toast.makeText(this, "正在请求添加 $displaySize 小组件", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("AddWidget", "Error", e)
            }
        }
        
        window.decorView.postDelayed({ finish() }, 800)
    }
}
