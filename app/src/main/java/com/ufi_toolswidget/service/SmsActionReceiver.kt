package com.ufi_toolswidget.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 短信通知动作接收器。
 *
 * 处理短信通知上的「标记已读」按钮：调用设备 goform SET_MSG_READ 接口把指定短信标记已读，
 * 随后取消该通知、刷新未读数并触发小组件重绘。
 */
class SmsActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return
        val ids = intent.getStringExtra(EXTRA_IDS)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, -1)
        val appCtx = context.applicationContext

        // 先取消通知，给用户即时反馈
        if (notifyId != -1) {
            try { NotificationManagerCompat.from(appCtx).cancel(notifyId) } catch (_: Exception) {}
        }
        if (ids.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = WifiCrawl.markSmsRead(appCtx, ids)
                DebugLogger.logApi(TAG, "markSmsRead ids=$ids ok=$ok")
                // 重新拉取未读数，更新小组件显示
                val remaining = WifiCrawl.fetchUnreadSms(appCtx).size
                SPUtil.getSp(appCtx).edit().putInt("sms_unread", remaining).apply()
                try { BaseWifiWidget.renderAllWidgets(appCtx, force = true) } catch (_: Exception) {}
            } catch (e: Exception) {
                DebugLogger.logApiErr(TAG, "SmsActionReceiver failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsActionReceiver"
        const val ACTION_MARK_READ = "com.ufi_toolswidget.ACTION_SMS_MARK_READ"
        const val EXTRA_IDS = "sms_ids"
        const val EXTRA_NOTIFY_ID = "notify_id"
    }
}
