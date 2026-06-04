package com.ufi_toolswidget.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object SPUtil {
    fun getSp(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
    }

    fun saveData(ctx: Context, data: WifiEntity) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        getSp(ctx).edit()
            .putString("flow", data.flow)
            .putString("daily_flow", data.dailyFlow)
            .putString("signal", data.signal)
            .putString("temp", data.temp)
            .putString("battery", data.battery)
            .putInt("battery_percent", data.batteryPercent)
            .putString("model", data.model)
            .putString("cpu", data.cpu)
            .putString("mem", data.mem)
            .putString("net_type", data.netType)
            .putString("app_ver", data.appVer)
            .putString("app_ver_code", data.appVerCode)
            .putString("battery_current", data.batteryCurrent)
            .putString("battery_voltage", data.batteryVoltage)
            .putString("internal_storage", data.internalStorage)
            .putLong("internal_total_storage", data.internalTotalStorage)
            .putLong("internal_used_storage", data.internalUsedStorage)
            .putLong("internal_available_storage", data.internalAvailableStorage)
            .putLong("external_total_storage", data.externalTotalStorage)
            .putLong("external_used_storage", data.externalUsedStorage)
            .putLong("external_available_storage", data.externalAvailableStorage)
            .putString("client_ip", data.clientIp)
            .putString("device_model", data.deviceModel)
            .putString("firmware_ver", data.firmwareVer)
            .putBoolean("need_token", data.needToken)
            .putString("update_time", time)
            .apply()
    }

    // 认证与配置
    fun saveRawToken(ctx: Context, token: String) = getSp(ctx).edit().putString("raw_token", token).apply()
    fun getRawToken(ctx: Context) = getSp(ctx).getString("raw_token", "admin") ?: "admin"
    fun saveAuthToken(ctx: Context, token: String) = getSp(ctx).edit().putString("auth_token", token).apply()
    fun getAuthToken(ctx: Context) = getSp(ctx).getString("auth_token", "") ?: ""

    // 刷新频率 (单位: 分钟) — 后台 Worker 间隔
    fun setRefreshInterval(ctx: Context, minutes: Int) = getSp(ctx).edit().putInt("refresh_interval", minutes).apply()
    fun getRefreshInterval(ctx: Context) = getSp(ctx).getInt("refresh_interval", 15)

    // 主界面自动刷新间隔 (单位: 秒) — 前台轮询间隔，0 表示关闭
    fun setMainRefreshSeconds(ctx: Context, seconds: Int) = getSp(ctx).edit().putInt("main_refresh_seconds", seconds).apply()
    fun getMainRefreshSeconds(ctx: Context) = getSp(ctx).getInt("main_refresh_seconds", 5)

    // 显隐设置
    fun setWidgetSettings(ctx: Context, flow: Boolean, signal: Boolean, temp: Boolean, cpu: Boolean, model: Boolean, time: Boolean) {
        getSp(ctx).edit()
            .putBoolean("show_flow", flow)
            .putBoolean("show_signal", signal)
            .putBoolean("show_temp", temp)
            .putBoolean("show_cpu", cpu)
            .putBoolean("show_model", model)
            .putBoolean("show_time", time)
            .apply()
    }

    fun getShowFlow(ctx: Context) = getSp(ctx).getBoolean("show_flow", true)
    fun getShowSignal(ctx: Context) = getSp(ctx).getBoolean("show_signal", true)
    fun getShowTemp(ctx: Context) = getSp(ctx).getBoolean("show_temp", true)
    fun getShowCpu(ctx: Context) = getSp(ctx).getBoolean("show_cpu", true)
    fun getShowModel(ctx: Context) = getSp(ctx).getBoolean("show_model", true)
    fun getShowTime(ctx: Context) = getSp(ctx).getBoolean("show_time", true)
    fun getShowBattery(ctx: Context) = getSp(ctx).getBoolean("show_battery", true)
    fun getShowMem(ctx: Context) = getSp(ctx).getBoolean("show_mem", true)

    fun setShowFlow(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_flow", show).apply()
    fun setShowSignal(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal", show).apply()
    fun setShowTemp(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_temp", show).apply()
    fun setShowCpu(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_cpu", show).apply()
    fun setShowModel(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_model", show).apply()
    fun setShowTime(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_time", show).apply()
    fun setShowBattery(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery", show).apply()
    fun setShowMem(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_mem", show).apply()

    /** 统计已启用的显示项数量 */
    fun getEnabledCount(ctx: Context): Int {
        var count = 0
        if (getShowFlow(ctx)) count++
        if (getShowSignal(ctx)) count++
        if (getShowTemp(ctx)) count++
        if (getShowCpu(ctx)) count++
        if (getShowModel(ctx)) count++
        if (getShowTime(ctx)) count++
        if (getShowBattery(ctx)) count++
        if (getShowMem(ctx)) count++
        return count
    }
    
    fun getCachedMonthlyData(ctx: Context): Long = getSp(ctx).getLong("cached_monthly_data", 0L)
    fun setCachedMonthlyData(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("cached_monthly_data", bytes).apply()

    fun isFirstRun(ctx: Context) = getSp(ctx).getBoolean("is_first_run", true)
    fun setFirstRun(ctx: Context, value: Boolean) = getSp(ctx).edit().putBoolean("is_first_run", value).apply()

    // ==================== Worker 失败状态 ====================
    /** 失败原因类型：空字符串=未失败, "network"=网络不通, "api"=端口/Token错误 */
    fun getWorkerStopReason(ctx: Context) = getSp(ctx).getString("worker_stop_reason", "") ?: ""
    fun setWorkerStopReason(ctx: Context, reason: String) = getSp(ctx).edit().putString("worker_stop_reason", reason).apply()

    // ==================== 设备连接配置 ====================
    const val DEFAULT_DEVICE_ADDRESS = "192.168.0.1:2333"

    /** 获取设备地址（单一字段，支持 IP:端口 或 域名） */
    fun getDeviceAddress(ctx: Context): String {
        return getSp(ctx).getString("device_address", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEVICE_ADDRESS
    }

    /** 保存设备地址（同时重置协议探测结果，下次自动重探） */
    fun setDeviceAddress(ctx: Context, address: String) {
        val v = address.trim()
        getSp(ctx).edit()
            .putString("device_address", v.ifEmpty { DEFAULT_DEVICE_ADDRESS })
            .putString("device_protocol", "auto")  // 地址变了，旧探测结果作废
            .apply()
    }

    /** 从地址中提取主机部分（IP 或域名） */
    fun getDeviceHost(ctx: Context): String {
        return parseAddress(getDeviceAddress(ctx)).host
    }

    /** 从地址中提取端口号 */
    fun getDevicePortInt(ctx: Context): Int {
        return parseAddress(getDeviceAddress(ctx)).port
    }

    // ── 协议自动探测缓存 ──

    /** 获取自动探测到的协议（\"auto\" = 未探测 / \"http\" / \"https\"） */
    fun getDeviceProtocol(ctx: Context): String =
        getSp(ctx).getString("device_protocol", "auto") ?: "auto"

    /** 保存自动探测到的协议 */
    fun setDeviceProtocol(ctx: Context, protocol: String) =
        getSp(ctx).edit().putString("device_protocol", protocol).apply()

    /** 当前地址是否需要协议探测（域名或公网IP，且未显式写协议前缀） */
    fun needsProtocolProbe(ctx: Context): Boolean {
        val raw = getDeviceAddress(ctx).trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return false
        return !isPrivateOrLocalIp(parseAddress(raw).host)
    }

    /**
     * 构建完整 Base URL：
     * - 显式协议（http:// 或 https://）→ 直接使用
     * - 私有 IP → http://
     * - 域名/公网IP → 查缓存协议；若已探测到则用对应协议，否则默认 http://
     */
    fun buildBaseUrl(ctx: Context): String {
        val raw = getDeviceAddress(ctx)
        val (parsedProtocol, host, parsedPort, protocolExplicit, portExplicit) = parseAddress(raw)

        val protocol = if (protocolExplicit) {
            parsedProtocol
        } else {
            val stored = getDeviceProtocol(ctx)
            if (stored == "auto") parsedProtocol else stored
        }

        val port = if (portExplicit) {
            parsedPort
        } else {
            when {
                protocol == "https" -> 443
                isPrivateOrLocalIp(host) -> 2333
                else -> 80
            }
        }

        return "$protocol://$host:$port/"
    }

    // ── 解析工具 ──

    /** 解析地址字符串 → (协议, 主机, 端口, 协议是否显式指定, 端口是否显式指定) */
    private fun parseAddress(raw: String): AddressParts {
        val trimmed = raw.trim()

        // 1) 带协议前缀：http://host:port 或 https://host:port
        val protocolRegex = Regex("^(https?)://([^:/]+)(?::(\\d+))?/?$")
        protocolRegex.find(trimmed)?.let { m ->
            val protocol = m.groupValues[1]
            val host = m.groupValues[2]
            val portStr = m.groupValues[3]
            val portExplicit = portStr.isNotEmpty()
            val port = if (portExplicit) portStr.toInt() else (if (protocol == "https") 443 else 80)
            return AddressParts(protocol, host, port, protocolExplicit = true, portExplicit = portExplicit)
        }

        // 2) 无协议：host:port 或 host
        val hostPortRegex = Regex("^([^:/]+)(?::(\\d+))?$")
        hostPortRegex.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val explicitPort = m.groupValues[2].toIntOrNull()
            val portExplicit = explicitPort != null
            val (protocol, defaultPort) = resolveProtocol(host, explicitPort)
            return AddressParts(protocol, host, explicitPort ?: defaultPort, protocolExplicit = false, portExplicit = portExplicit)
        }

        // 解析失败 → 默认地址
        return parseAddress(DEFAULT_DEVICE_ADDRESS)
    }

    /** 根据主机类型决定协议与默认端口（仅用于未探测时的默认值） */
    private fun resolveProtocol(host: String, explicitPort: Int?): Pair<String, Int> {
        if (isPrivateOrLocalIp(host)) {
            return "http" to (explicitPort ?: 2333)
        }
        return "http" to (explicitPort ?: 80)
    }

    /** 判断是否为私有/本地 IP */
    internal fun isPrivateOrLocalIp(host: String): Boolean {
        val ipPattern = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        val m = ipPattern.find(host) ?: return false
        val o1 = m.groupValues[1].toIntOrNull() ?: return false
        val o2 = m.groupValues[2].toIntOrNull() ?: return false
        return o1 == 10 ||
                o1 == 127 ||
                (o1 == 172 && o2 in 16..31) ||
                (o1 == 192 && o2 == 168)
    }

    private data class AddressParts(
        val protocol: String,
        val host: String,
        val port: Int,
        val protocolExplicit: Boolean = false,
        val portExplicit: Boolean = false
    )

    // ==================== 主题模式 ====================
    // app_theme: "system" (默认/跟随设备), "light", "dark"
    // widget_theme: "follow_app" (默认/跟随应用), "light", "dark"

    fun getAppTheme(ctx: Context) = getSp(ctx).getString("app_theme", "system") ?: "system"
    fun setAppTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("app_theme", mode).apply()

    fun getWidgetTheme(ctx: Context) = getSp(ctx).getString("widget_theme", "follow_app") ?: "follow_app"
    fun setWidgetTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("widget_theme", mode).apply()

    /** 判断小组件当前是否应使用暗色模式 */
    fun isWidgetDark(ctx: Context): Boolean {
        return when (getWidgetTheme(ctx)) {
            "light" -> false
            "dark" -> true
            else -> { // follow_app
                when (getAppTheme(ctx)) {
                    "light" -> false
                    "dark" -> true
                    else -> { // system
                        val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        nightMode == Configuration.UI_MODE_NIGHT_YES
                    }
                }
            }
        }
    }

    /** 判断应用当前是否应使用暗色模式（用于 Activity 启动时设置） */
    fun getNightMode(ctx: Context): Int {
        return when (getAppTheme(ctx)) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    // ==================== 自定义颜色主题 ====================
    // color_theme = -1 表示使用自定义颜色
    fun getCustomAccentLight(ctx: Context) = getSp(ctx).getInt("custom_accent_light", 0xFF222222.toInt())
    fun setCustomAccentLight(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_light", color).apply()
    fun getCustomAccentDark(ctx: Context) = getSp(ctx).getInt("custom_accent_dark", 0xFFCCCCCC.toInt())
    fun setCustomAccentDark(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_dark", color).apply()

    /** 获取当前颜色主题索引（-1 为自定义） */
    fun getColorThemeIndex(ctx: Context) = getSp(ctx).getInt("color_theme", 0)
    fun setColorThemeIndex(ctx: Context, index: Int) = getSp(ctx).edit().putInt("color_theme", index).apply()

    // ==================== 调试模式 ====================
    fun getDebugEnabled(ctx: Context) = getSp(ctx).getBoolean("debug_enabled", false)
    fun setDebugEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("debug_enabled", enabled).apply()

    // ==================== 更新镜像源 ====================
    // 0 = GitHub 官方，1 = 国内镜像 (gh-proxy)
    fun getUpdateMirror(ctx: Context) = getSp(ctx).getInt("update_mirror", 0)
    fun setUpdateMirror(ctx: Context, mirror: Int) = getSp(ctx).edit().putInt("update_mirror", mirror).commit()
}
