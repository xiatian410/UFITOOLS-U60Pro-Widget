package com.ufi_toolswidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.SPUtil

/**
 * 从 SharedPreferences 读取数据的辅助方法
 */
private fun Context.getWifiSp() = getSharedPreferences("wifi_data", Context.MODE_PRIVATE)

abstract class BaseWifiWidget(private val layoutId: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val rv = RemoteViews(context.packageName, layoutId)
            updateViews(context, rv)
            appWidgetManager.updateAppWidget(id, rv)
        }
    }

    protected open fun updateViews(context: Context, rv: RemoteViews) {
        val sp = context.getWifiSp()
        val flow = SPUtil.getFlow(context)
        val daily = sp.getString("daily_flow", "--") ?: "--"
        val signal = SPUtil.getSignal(context)
        val temp = SPUtil.getTemp(context)
        val battery = sp.getString("battery", "--") ?: "--"
        val model = sp.getString("model", "F50") ?: "F50"
        val cpu = sp.getString("cpu", "--") ?: "--"
        val mem = sp.getString("mem", "--") ?: "--"
        val netType = sp.getString("net_type", "") ?: ""
        val appVer = sp.getString("app_ver", "") ?: ""
        val batteryCurrent = sp.getString("battery_current", "--") ?: "--"
        val batteryVoltage = sp.getString("battery_voltage", "--") ?: "--"
        val internalStorage = sp.getString("internal_storage", "--") ?: "--"
        val clientIp = sp.getString("client_ip", "") ?: ""

        // 通用字段映射
        safeSetText(rv, R.id.tv_model, model)
        safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())
        safeSetText(rv, R.id.tv_daily, daily.replace("GB", "").trim())
        safeSetText(rv, R.id.tv_signal, signal)
        safeSetText(rv, R.id.tv_temp, temp)
        safeSetText(rv, R.id.tv_battery, if (battery.contains("%")) battery else "$battery%")
        safeSetText(rv, R.id.tv_cpu_mem, cpu)
        safeSetText(rv, R.id.tv_net_type, netType)
        safeSetText(rv, R.id.tv_app_ver, "UFI v$appVer")
        safeSetText(rv, R.id.tv_current, batteryCurrent)
        safeSetText(rv, R.id.tv_voltage, batteryVoltage)
        safeSetText(rv, R.id.tv_storage, internalStorage)
        safeSetText(rv, R.id.tv_client_ip, clientIp)

        // 显示/隐藏控制
        val showFlow = SPUtil.getShowFlow(context)
        val showSignal = SPUtil.getShowSignal(context)
        val showTemp = SPUtil.getShowTemp(context)

        safeSetVisibility(rv, R.id.tv_flow, showFlow)
        safeSetVisibility(rv, R.id.tv_daily, showFlow)
        safeSetVisibility(rv, R.id.tv_signal, showSignal)
        safeSetVisibility(rv, R.id.tv_temp, showTemp)
    }

    /** 安全设置文本，避免 ID 不存在时崩溃 */
    private fun safeSetText(rv: RemoteViews, id: Int, text: CharSequence) {
        try {
            rv.setTextViewText(id, text)
        } catch (_: Exception) {}
    }

    /** 安全设置可见性 */
    private fun safeSetVisibility(rv: RemoteViews, id: Int, visible: Boolean) {
        try {
            rv.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
        } catch (_: Exception) {}
    }
}

// ======== 常用小组件尺寸 ========
class WifiWidget2x2 : BaseWifiWidget(R.layout.widget_2x2) {
    override fun updateViews(context: Context, rv: RemoteViews) {
        super.updateViews(context, rv)
    }
}

class WifiWidget3x2 : BaseWifiWidget(R.layout.widget_3x2) {
    override fun updateViews(context: Context, rv: RemoteViews) {
        super.updateViews(context, rv)
    }
}

class WifiWidget3x3 : BaseWifiWidget(R.layout.widget_3x3) {
    override fun updateViews(context: Context, rv: RemoteViews) {
        super.updateViews(context, rv)
    }
}
