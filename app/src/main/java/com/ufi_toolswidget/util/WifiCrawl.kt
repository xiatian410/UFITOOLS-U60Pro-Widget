package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

object WifiCrawl {
    
    private const val TAG = "WifiCrawl"
    
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
            
            // 日流量 (daily_data 来自 baseDeviceInfo，可能是多种字段名)
            val dailyRaw = extractTrafficBytes(baseInfo, "daily_data", "daily_tx_bytes", "daily_rx_bytes", "day_data")

            // 月流量：从 baseDeviceInfo 中尝试提取（优先级最高）
            var monthlyRaw = extractTrafficBytes(baseInfo, "monthly_data", "monthly_tx_bytes", "monthly_rx_bytes", "month_data", "total_data")
            
            // 如果 baseDeviceInfo 中没有月流量，尝试从 goform 代理获取
            if (monthlyRaw <= 0) {
                val monthlyInfo = fetchApi("/api/goform/goform_get_cmd_process?is_all=true&cmd=monthly_data", t, auth)
                val monthlyFromGoform = extractTrafficBytes(monthlyInfo, "monthly_data", "monthly_tx_bytes", "monthly_rx_bytes", "month_data")
                if (monthlyFromGoform > 0) {
                    monthlyRaw = monthlyFromGoform
                }
            }
            
            // 如果 API 都没返回月流量，用缓存
            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            } else {
                SPUtil.setCachedMonthlyData(context, monthlyRaw)
            }
            
            Log.d(TAG, "dailyRaw=$dailyRaw, monthlyRaw=$monthlyRaw")
            
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

    /**
     * 从 JSON 中提取流量字节数，自动适配不同单位和字段名
     * 
     * 有些固件返回的是 Bytes，有些是 KB，有些是 MB。
     * 通过数值大小判断单位：>10GB 量级 = Bytes，>10MB 量级 = KB，否则 = MB
     */
    private fun extractTrafficBytes(json: JSONObject?, vararg keys: String): Long {
        if (json == null) return 0L
        
        for (key in keys) {
            // 尝试数字类型字段
            val longVal = json.optLong(key, -1L)
            if (longVal > 0) {
                // 根据数值大小判断实际单位
                // 如果 > 10GB (10737418240 bytes)，很可能是 Bytes 单位
                if (longVal > 10_737_418_240L) {
                    Log.d(TAG, "  $key=$longVal -> 判断为 Bytes 单位")
                    return longVal
                }
                // 如果 > 10MB (10485760)，很可能是 KB 单位
                if (longVal > 10_485_760L) {
                    Log.d(TAG, "  $key=$longVal -> 判断为 KB 单位，转换为 Bytes")
                    return longVal * 1024L
                }
                // 否则可能是 MB 单位
                if (longVal > 10_240L) {
                    Log.d(TAG, "  $key=$longVal -> 判断为 MB 单位，转换为 Bytes")
                    return longVal * 1024L * 1024L
                }
                // 数值很小，可能是 GB 单位
                if (longVal > 0) {
                    Log.d(TAG, "  $key=$longVal -> 判断为 GB 单位，转换为 Bytes")
                    return longVal * 1024L * 1024L * 1024L
                }
            }
            
            // 尝试字符串类型字段（有些固件返回字符串数字）
            val strVal = json.optString(key, "")
            if (strVal.isNotEmpty() && strVal != "null") {
                val parsed = strVal.toDoubleOrNull()
                if (parsed != null && parsed > 0) {
                    val bytes = parsed.toLong()
                    if (bytes > 10_737_418_240L) {
                        Log.d(TAG, "  $key=$strVal(String) -> 判断为 Bytes 单位")
                        return bytes
                    }
                    if (bytes > 10_485_760L) {
                        Log.d(TAG, "  $key=$strVal(String) -> 判断为 KB 单位，转换为 Bytes")
                        return bytes * 1024L
                    }
                    if (bytes > 10_240L) {
                        Log.d(TAG, "  $key=$strVal(String) -> 判断为 MB 单位，转换为 Bytes")
                        return bytes * 1024L * 1024L
                    }
                    if (bytes > 0) {
                        Log.d(TAG, "  $key=$strVal(String) -> 判断为 GB 单位，转换为 Bytes")
                        return bytes * 1024L * 1024L * 1024L
                    }
                }
            }
        }
        return 0L
    }

    private fun formatFlow(bytes: Long): String {
        if (bytes <= 0) return "0.00 GB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.2f GB", gb)
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
