package com.ufi_toolswidget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.*
import kotlinx.coroutines.runBlocking

class WifiWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        runBlocking {
            val data = WifiCrawl.getWifiData(applicationContext)
            if (data != null) {
                SPUtil.saveData(applicationContext, data)
                
                val widgetClasses = arrayOf(
                    WifiWidget2x2::class.java,
                    WifiWidget3x2::class.java,
                    WifiWidget3x3::class.java
                )
                
                for (clazz in widgetClasses) {
                    val intent = Intent(applicationContext, clazz).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        val ids = AppWidgetManager.getInstance(applicationContext)
                            .getAppWidgetIds(ComponentName(applicationContext, clazz))
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    applicationContext.sendBroadcast(intent)
                }
            }
        }
        return Result.success()
    }
}
