package com.ufi_toolswidget.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val data = WifiCrawl.getWifiData(applicationContext)
            if (data != null) {
                SPUtil.saveData(applicationContext, data)
                // 直接更新小组件 UI，不发送导致死循环的广播
                BaseWifiWidget.renderAllWidgets(applicationContext)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
