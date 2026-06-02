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
        // 新增字段
        val appVer = sp.getString("app_ver", "") ?: ""
        val batteryCurrent = sp.getString("battery_current", "--") ?: "--"
        val batteryVoltage = sp.getString("battery_voltage", "--") ?: "--"
        val internalStorage = sp.getString("internal_storage", "--") ?: "--"
        val clientIp = sp.getString("client_ip", "") ?: ""

        // 通用字段映射 (子类布局中存在的 ID 才会生效)
        rv.setTextViewText(R.id.tv_model, model)
        rv.setTextViewText(R.id.tv_flow, flow.replace("GB", "").trim())
        rv.setTextViewText(R.id.tv_daily, daily.replace("GB", "").trim())
        rv.setTextViewText(R.id.tv_signal, signal)
        rv.setTextViewText(R.id.tv_temp, temp)
        rv.setTextViewText(R.id.tv_battery, if (battery.contains("%")) battery else "$battery%")
        // 新增字段映射
        rv.setTextViewText(R.id.tv_cpu_mem, cpu)
        rv.setTextViewText(R.id.tv_net_type, netType)
        rv.setTextViewText(R.id.tv_app_ver, "UFI v$appVer")
        rv.setTextViewText(R.id.tv_current, batteryCurrent)
        rv.setTextViewText(R.id.tv_voltage, batteryVoltage)
        rv.setTextViewText(R.id.tv_storage, internalStorage)
        rv.setTextViewText(R.id.tv_client_ip, clientIp)

        // 根据设置控制可见性 (如果布局里有这些 ID)
        val showFlow = SPUtil.getShowFlow(context)
        val showSignal = SPUtil.getShowSignal(context)
        val showTemp = SPUtil.getShowTemp(context)

        rv.setViewVisibility(R.id.tv_flow, if (showFlow) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.tv_daily, if (showFlow) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.tv_signal, if (showSignal) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.tv_temp, if (showTemp) View.VISIBLE else View.GONE)
    }
}

// ======== 9 种小组件尺寸 (1x1 ~ 3x3) ========
class WifiWidget1x1 : BaseWifiWidget(R.layout.widget_1x1)
class WifiWidget1x2 : BaseWifiWidget(R.layout.widget_1x2)
class WifiWidget1x3 : BaseWifiWidget(R.layout.widget_1x3)
class WifiWidget2x1 : BaseWifiWidget(R.layout.widget_2x1)
class WifiWidget2x2 : BaseWifiWidget(R.layout.widget_2x2)
class WifiWidget2x3 : BaseWifiWidget(R.layout.widget_2x3)
class WifiWidget3x1 : BaseWifiWidget(R.layout.widget_3x1)
class WifiWidget3x2 : BaseWifiWidget(R.layout.widget_3x2)
class WifiWidget3x3 : BaseWifiWidget(R.layout.widget_3x3)
