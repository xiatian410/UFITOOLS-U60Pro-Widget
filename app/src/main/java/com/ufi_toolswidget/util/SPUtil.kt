package com.ufi_toolswidget.util

import android.content.Context
import android.content.SharedPreferences

object SPUtil {
    fun getSp(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
    }

    fun saveData(ctx: Context, data: WifiEntity) {
        getSp(ctx).edit()
            .putString("flow", data.flow)
            .putString("daily_flow", data.dailyFlow)
            .putString("signal", data.signal)
            .putString("temp", data.temp)
            .putString("battery", data.battery)
            .putString("model", data.model)
            .putString("cpu", data.cpu)
            .putString("mem", data.mem)
            .putString("net_type", data.netType)
            // 新增字段
            .putString("app_ver", data.appVer)
            .putString("battery_current", data.batteryCurrent)
            .putString("battery_voltage", data.batteryVoltage)
            .putString("internal_storage", data.internalStorage)
            .putString("client_ip", data.clientIp)
            .apply()
    }

    fun getFlow(ctx: Context) = getSp(ctx).getString("flow", "--") ?: "--"
    fun getSignal(ctx: Context) = getSp(ctx).getString("signal", "--") ?: "--"
    fun getTemp(ctx: Context) = getSp(ctx).getString("temp", "--") ?: "--"

    /** 缓存月流量数据（用于 goform API 获取失败时的回退） */
    fun getCachedMonthlyData(ctx: Context): Long = getSp(ctx).getLong("cached_monthly_data", 0L)
    fun setCachedMonthlyData(ctx: Context, bytes: Long) {
        getSp(ctx).edit().putLong("cached_monthly_data", bytes).apply()
    }

    fun saveAuthToken(ctx: Context, token: String) {
        getSp(ctx).edit().putString("auth_token", token).apply()
    }
    fun getAuthToken(ctx: Context) = getSp(ctx).getString("auth_token", "") ?: ""

    fun saveRawToken(ctx: Context, token: String) {
        getSp(ctx).edit().putString("raw_token", token).apply()
    }
    fun getRawToken(ctx: Context) = getSp(ctx).getString("raw_token", "admin") ?: "admin"

    fun isFirstRun(ctx: Context) = getSp(ctx).getBoolean("is_first_run", true)
    fun setFirstRun(ctx: Context, value: Boolean) = getSp(ctx).edit().putBoolean("is_first_run", value).apply()

    fun setWidgetSettings(ctx: Context, flow: Boolean, signal: Boolean, temp: Boolean) {
        getSp(ctx).edit()
            .putBoolean("show_flow", flow)
            .putBoolean("show_signal", signal)
            .putBoolean("show_temp", temp)
            .apply()
    }

    fun getShowFlow(ctx: Context) = getSp(ctx).getBoolean("show_flow", true)
    fun getShowSignal(ctx: Context) = getSp(ctx).getBoolean("show_signal", true)
    fun getShowTemp(ctx: Context) = getSp(ctx).getBoolean("show_temp", true)
}
