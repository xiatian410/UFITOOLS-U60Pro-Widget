package com.ufi_toolswidget.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

object WifiCrawl {
    
    var lastRawResponse: String = ""
    var lastError: String = ""

    suspend fun getWifiData(context: Context): WifiEntity? = withContext(Dispatchers.IO) {
        try {
            lastError = ""
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)

            // 1. 获取系统全量信息
            val baseInfo = fetchApi("/api/baseDeviceInfo", t, auth) ?: return@withContext null
            
            // 2. 获取信号详细信息 (修正后的参数)
            val signalPath = "/api/goform/goform_get_cmd_process?is_all=true&cmd=lte_rsrp,Z5g_rsrp,network_type,rssi"
            val signalInfo = fetchApi(signalPath, t, auth)

            // --- 精准解析 ---
            val model = baseInfo.optString("model", "F50")
            val batteryPercent = baseInfo.optInt("battery", -1)
            val cpuUsage = baseInfo.optDouble("cpu_usage", 0.0)
            val memUsage = baseInfo.optDouble("mem_usage", 0.0)
            
            // 流量 (daily_data 来自 baseDeviceInfo)
            val dailyRaw = baseInfo.optLong("daily_data", 0L)

            // 月流量：尝试从 goform 代理获取，失败则用缓存
            var monthlyRaw = SPUtil.getCachedMonthlyData(context)
            val monthlyInfo = fetchApi("/api/goform/goform_get_cmd_process?is_all=true&cmd=monthly_data", t, auth)
            val monthlyFromGoform = monthlyInfo?.optLong("monthly_data", -1L) ?: -1L
            if (monthlyFromGoform > 0) {
                monthlyRaw = monthlyFromGoform
                SPUtil.setCachedMonthlyData(context, monthlyFromGoform)
            }
            
            // 温度 (60620 -> 60.6)
            val tempRaw = baseInfo.optDouble("cpu_temp", 0.0)

            // === /api/baseDeviceInfo 新增字段 ===
            val appVer = baseInfo.optString("app_ver", "")
            val currentNow = baseInfo.optInt("current_now", -1)        // 微安 µA
            val voltageNow = baseInfo.optInt("voltage_now", -1)        // 微伏 µV
            val internalTotal = baseInfo.optLong("internal_total_storage", -1L)
            val internalUsed = baseInfo.optLong("internal_used_storage", -1L)
            val clientIp = baseInfo.optString("client_ip", "")

            // 信号解析逻辑
            val netType = signalInfo?.optString("network_type") ?: ""
            val signalVal = when {
                netType.contains("5G") -> signalInfo?.optString("Z5g_rsrp")
                else -> signalInfo?.optString("lte_rsrp")
            } ?: signalInfo?.optString("rssi") ?: "--"

            WifiEntity(
                model = model,
                flow = formatFlow(monthlyRaw),
                dailyFlow = formatFlow(dailyRaw),
                signal = if (signalVal == "" || signalVal == "null") "--" else "${signalVal}dBm",
                temp = formatTemp(tempRaw),
                battery = if (batteryPercent >= 0) "${batteryPercent}%" else "--",
                cpu = String.format(Locale.getDefault(), "%.1f%%", cpuUsage),
                mem = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                netType = netType,
                appVer = appVer,
                batteryCurrent = formatCurrent(currentNow),
                batteryVoltage = formatVoltage(voltageNow),
                internalStorage = formatStorage(internalTotal, internalUsed),
                clientIp = clientIp
            )
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "Parse Error: ${e.message}"
            null
        }
    }

    private suspend fun fetchApi(path: String, t: Long, auth: String): JSONObject? {
        val purePath = if (path.contains("?")) path.substringBefore("?") else path
        val sign = NetUtil.generateKanoSign("GET", purePath, t)
        
        val req = Request.Builder()
            .url("${NetUtil.BASE_URL}$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .addHeader("Authorization", auth)
            .build()
            
        return try {
            val resp = NetUtil.client.newCall(req).execute()
            val content = resp.body?.string() ?: ""
            lastRawResponse = content
            if (resp.isSuccessful && content.isNotEmpty()) {
                JSONObject(content)
            } else {
                lastError = "HTTP ${resp.code} on $purePath"
                null
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Network error"
            null
        }
    }

    private fun formatFlow(bytes: Long): String {
        if (bytes <= 0) return "0.00 GB"
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun formatTemp(raw: Double): String {
        if (raw <= 0) return "--"
        val celsius = if (raw > 1000) raw / 1000.0 else raw
        return String.format(Locale.getDefault(), "%.1f℃", celsius)
    }

    /** 电流格式化: µA → mA (微安转毫安) */
    private fun formatCurrent(currentUa: Int): String {
        if (currentUa < 0) return "--"
        val ma = currentUa / 1000.0
        return String.format(Locale.getDefault(), "%.0fmA", ma)
    }

    /** 电压格式化: µV → V (微伏转伏) */
    private fun formatVoltage(voltageUv: Int): String {
        if (voltageUv < 0) return "--"
        val v = voltageUv / 1_000_000.0
        return String.format(Locale.getDefault(), "%.2fV", v)
    }

    /** 存储格式化: Bytes → GB */
    private fun formatStorage(total: Long, used: Long): String {
        if (total <= 0) return "--"
        val usedGb = used / (1024.0 * 1024.0 * 1024.0)
        val totalGb = total / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.1f / %.1f GB", usedGb, totalGb)
    }
}

data class WifiEntity(
    val model: String,
    val flow: String,
    val dailyFlow: String,
    val signal: String,
    val temp: String,
    val battery: String,
    val cpu: String,
    val mem: String,
    val netType: String,
    // === /api/baseDeviceInfo 新增字段 ===
    val appVer: String,           // UFI-TOOLS 版本号
    val batteryCurrent: String,   // 电池电流 (mA)
    val batteryVoltage: String,   // 电池电压 (V)
    val internalStorage: String,  // 内部存储 已用/总容量
    val clientIp: String          // 设备 IP 地址
)
