package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

object WifiCrawl {
    
    private const val TAG = "WifiCrawl"
    
    @Volatile var lastRawResponse: String = ""
    @Volatile var lastError: String = ""

    // ── 预编译 Regex：避免每次调用重复编译 ──
    private val PORT_RE = Regex(":(\\d+)$")

    suspend fun getWifiData(context: Context, quickStart: Boolean = false): WifiEntity? = withContext(Dispatchers.IO) {
        try {
            lastError = ""
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)
            val baseUrl = SPUtil.buildBaseUrl(context)
            
            DebugLogger.logApi(TAG, "Starting data refresh from $baseUrl" + if (quickStart) " (quick start)" else "")

            // 分批获取：先单独获取 baseDeviceInfo（含 CPU），避免并发请求拉高设备 CPU 导致读数虚高
            var baseDeviceInfo: org.json.JSONObject? = null
            var signalInfo: org.json.JSONObject? = null
            var goformDeviceInfo: org.json.JSONObject? = null
            var versionInfo: org.json.JSONObject? = null
            var tokenInfo: org.json.JSONObject? = null
            // 在并发请求前预先解析的 CPU 值
            var preCpuUsage = -1.0
            var preCpuUsageMap = emptyMap<String, String>()

            coroutineScope {
                // 冷却期：快速启动时跳过，正常刷新时等待 1-2s，让设备 CPU 从上轮请求中恢复
                if (!quickStart) {
                    delay(1000L + (Math.random() * 1000).toLong())
                }

                // Step 0: 确保 device_token 就绪（X-Device-Token 头必需，缺失将导致 401）
                ensureDeviceToken(t, context)

                // Step 1: 单独获取 baseDeviceInfo，避免其他请求干扰 CPU 读数
                val baseResp = fetchApi(SPUtil.getDeviceInfoPath(context), t, auth, context)
                if (baseResp == null) {
                    DebugLogger.logApiErr(TAG, "Failed to fetch baseDeviceInfo: $lastError")
                    return@coroutineScope
                }
                baseDeviceInfo = baseResp
                // 在并发请求前立即解析 CPU 占用
                preCpuUsage = baseResp.optDouble("cpu_usage", -1.0)
                val map = mutableMapOf<String, String>()
                baseResp.optJSONObject("cpuUsageInfo")?.let { usageObj ->
                    usageObj.keys().forEach { key ->
                        map[key] = usageObj.optString(key, "--")
                    }
                }
                preCpuUsageMap = map

                // 冷却期：快速启动时跳过，正常刷新时等待 1-2s 再发起后续请求，确保 CPU 读数已被捕获
                if (!quickStart) {
                    delay(1000L + (Math.random() * 1000).toLong())
                }

                // Step 2: 其余请求并发执行
                val signalDeferred = async {
                    fetchApi("${SPUtil.getGoformCommandPath(context)}?is_all=true&cmd=lte_rsrp,Z5g_rsrp,network_type,rssi", t, auth, context)
                }
                val goformDeviceDeferred = async {
                    fetchApi("${SPUtil.getGoformCommandPath(context)}?is_all=true&cmd=wan_ipaddr,ipv6_wan_ipaddr,pdp_type,imei,imsi,iccid,hardware_version,web_version,mac_address,pin_status", t, auth, context)
                }
                val versionDeferred = async {
                    if (SPUtil.isVersionInfoCacheFresh(context)) {
                        val cached = SPUtil.getCachedVersionInfoJson(context)
                        if (cached.isNotEmpty()) {
                            DebugLogger.logApi(TAG, "version_info: using cache")
                            return@async JSONObject(cached)
                        }
                    }
                    fetchApiNoAuth(SPUtil.getVersionInfoPath(context), t, context)?.also {
                        SPUtil.setCachedVersionInfoJson(context, it.toString())
                        SPUtil.setVersionInfoCacheTime(context, System.currentTimeMillis())
                    }
                }
                val tokenDeferred = async {
                    if (SPUtil.isNeedTokenCacheFresh(context)) {
                        val cached = SPUtil.getCachedNeedTokenJson(context)
                        if (cached.isNotEmpty()) {
                            DebugLogger.logApi(TAG, "need_token: using cache")
                            return@async JSONObject(cached)
                        }
                    }
                    fetchApiNoAuth(SPUtil.getNeedTokenPath(context), t, context)?.also {
                        SPUtil.setCachedNeedTokenJson(context, it.toString())
                        SPUtil.setNeedTokenCacheTime(context, System.currentTimeMillis())
                        // 顺带持久化 device_token（X-Device-Token 头必需）
                        it.optString("device_token", "").takeIf { tok -> tok.isNotEmpty() }
                            ?.let { tok -> SPUtil.setDeviceToken(context, tok) }
                    }
                }
                signalInfo = signalDeferred.await()
                goformDeviceInfo = goformDeviceDeferred.await()
                versionInfo = versionDeferred.await()
                tokenInfo = tokenDeferred.await()
            }
            if (baseDeviceInfo == null) {
                DebugLogger.flushToFile()
                return@withContext null
            }

            DebugLogger.logApi(TAG, "Goform signal check: success=${signalInfo != null}")
            DebugLogger.logApi(TAG, "Goform device info: success=${goformDeviceInfo != null}")

            // --- 精准解析（CPU 已在并发前预解析） ---
            val model = baseDeviceInfo.optString("model", "F50")
            val batteryPercent = baseDeviceInfo.optInt("battery", -1)
            val cpuUsage = preCpuUsage
            val cpuUsageMap = preCpuUsageMap
            val memUsage = baseDeviceInfo.optDouble("mem_usage", 0.0)
            
            // === Goform 设备身份+网络地址 ===
            val wanIp = goformDeviceInfo?.optString("wan_ipaddr", "") ?: ""
            val wanIpv6 = goformDeviceInfo?.optString("ipv6_wan_ipaddr", "") ?: ""
            val pdpType = goformDeviceInfo?.optString("pdp_type", "") ?: ""
            val goformImei = goformDeviceInfo?.optString("imei", "") ?: ""
            val goformImsi = goformDeviceInfo?.optString("imsi", "") ?: ""
            val goformIccid = goformDeviceInfo?.optString("iccid", "") ?: ""
            val hwVersion = goformDeviceInfo?.optString("hardware_version", "") ?: ""
            val wbVersion = goformDeviceInfo?.optString("web_version", "") ?: ""
            val macAddr = goformDeviceInfo?.optString("mac_address", "") ?: ""
            val pinStatusCode = goformDeviceInfo?.optInt("pin_status", -1) ?: -1

            // ── 运营商名称：优先 Goform network_provider，回退用 IMSI 识别 ──
            val goformProvider = signalInfo?.optString("network_provider_fullname")?.ifBlank { null }
                ?: signalInfo?.optString("network_provider")?.ifBlank { null }
            val carrier = goformProvider ?: mapPlmnToCarrier(goformImsi)

            // --- 流量提取 (仅从 baseDeviceInfo) ---
            val dTx = extractTrafficBytes(baseDeviceInfo, "daily_tx_bytes")
            val dRx = extractTrafficBytes(baseDeviceInfo, "daily_rx_bytes")
            val dTotal = extractTrafficBytes(baseDeviceInfo, "daily_data", "day_data")
            val dailyRaw = if (dTotal > 0) dTotal else (dTx + dRx)

            val mTx = extractTrafficBytes(baseDeviceInfo, "monthly_tx_bytes")
            val mRx = extractTrafficBytes(baseDeviceInfo, "monthly_rx_bytes")
            val mTotal = extractTrafficBytes(baseDeviceInfo, "monthly_data", "month_data", "total_data")
            var monthlyRaw = if (mTotal > 0) mTotal else (mTx + mRx)

            // 如果 API 没返回月流量，用缓存
            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            } else {
                SPUtil.setCachedMonthlyData(context, monthlyRaw)
            }
            Log.d(TAG, "dailyRaw=$dailyRaw, monthlyRaw=$monthlyRaw (tx=$mTx, rx=$mRx)")
            
            // 温度 (60620 -> 60.6)
            val tempRaw = baseDeviceInfo.optDouble("cpu_temp", 0.0)

            // === /api/baseDeviceInfo 新增字段 ===
            val appVer = baseDeviceInfo.optString("app_ver", "")
            val appVerCode = baseDeviceInfo.optString("app_ver_code", "")
            val currentNow = baseDeviceInfo.optInt("current_now", -1)        // 微安 µA
            val voltageNow = baseDeviceInfo.optInt("voltage_now", -1)        // 微伏 µV
            val internalTotal = baseDeviceInfo.optLong("internal_total_storage", -1L)
            val internalUsed = baseDeviceInfo.optLong("internal_used_storage", -1L)
            val internalAvailable = baseDeviceInfo.optLong("internal_available_storage", -1L)
            val externalTotal = baseDeviceInfo.optLong("external_total_storage", -1L)
            val externalUsed = baseDeviceInfo.optLong("external_used_storage", -1L)
            val externalAvailable = baseDeviceInfo.optLong("external_available_storage", -1L)
            val clientIp = baseDeviceInfo.optString("client_ip", "")

            // === cpu_temp_list 各模块温度 ===
            val cpuTempList = mutableListOf<CpuTempItem>()
            baseDeviceInfo.optJSONArray("cpu_temp_list")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    cpuTempList.add(CpuTempItem(
                        type = obj.optString("type", "unknown"),
                        temp = obj.optDouble("temp", 0.0)
                    ))
                }
            }

            // === cpuFreqInfo 各核心频率 ===
            val cpuFreqMap = mutableMapOf<String, CpuFreqItem>()
            baseDeviceInfo.optJSONObject("cpuFreqInfo")?.let { freqObj ->
                freqObj.keys().forEach { key ->
                    val core = freqObj.optJSONObject(key) ?: return@forEach
                    cpuFreqMap[key] = CpuFreqItem(
                        cur = core.optInt("cur", 0),
                        max = core.optInt("max", 0)
                    )
                }
            }

            // CPU 总体占用（preCpuUsage 已在并发请求前采集，避免虚高）
            val finalCpuUsage = if (preCpuUsage in 0.0..100.0) preCpuUsage
                else preCpuUsageMap["cpu"]?.toDoubleOrNull() ?: 0.0

            // === memInfo 详细内存信息 ===
            var memTotalKb = 0L
            var memAvailableKb = 0L
            var memUsedKb = 0L
            var swapTotalKb = 0L
            var swapUsedKb = 0L
            var swapFreeKb = 0L
            baseDeviceInfo.optJSONObject("memInfo")?.let { memObj ->
                memTotalKb = memObj.optLong("mem_total_kb", 0L)
                memAvailableKb = memObj.optLong("mem_available_kb", 0L)
                memUsedKb = memObj.optLong("mem_used_kb", 0L)
                swapTotalKb = memObj.optLong("swap_total_kb", 0L)
                swapUsedKb = memObj.optLong("swap_used_kb", 0L)
                swapFreeKb = memObj.optLong("swap_free_kb", 0L)
            }

            // === /api/version_info 字段 ===
            val deviceModel = versionInfo?.optString("model", "") ?: ""
            val firmwareVer = versionInfo?.optString("app_ver", "") 
                ?: versionInfo?.optString("version", "") ?: ""

            // === /api/need_token 字段 ===
            val needToken = tokenInfo?.optBoolean("need_token", false) ?: false

            // 信号解析逻辑（仅使用 Goform API，设备不支持 AT 指令）
            val netType = signalInfo?.optString("network_type")?.ifBlank { null } ?: ""
            val goformSignalRaw = when {
                netType.contains("5G") -> signalInfo?.optString("Z5g_rsrp")
                else -> signalInfo?.optString("lte_rsrp")
            } ?: signalInfo?.optString("rssi") ?: "--"

            val signalStr = run {
                // goform 部分设备返回正值（绝对值），需修正为负 dBm
                val goformInt = goformSignalRaw.toIntOrNull()
                val s = when {
                    goformSignalRaw.isEmpty() || goformSignalRaw == "null" || goformSignalRaw == "--" -> "--"
                    goformInt != null && goformInt > 0 -> "${-goformInt}dBm"
                    else -> "${goformSignalRaw}dBm"
                }
                DebugLogger.logApi(TAG, "Signal: goform=$s")
                s
            }

            DebugLogger.flushToFile() // 本轮数据刷新完毕，批量落盘

            WifiEntity(
                model = model,
                flow = formatFlow(monthlyRaw),
                dailyFlow = formatFlow(dailyRaw),
                signal = signalStr,
                temp = formatTemp(tempRaw),
                battery = if (batteryPercent >= 0) "${batteryPercent}%" else "--",
                batteryPercent = if (batteryPercent >= 0) batteryPercent else -1,
                cpu = String.format(Locale.getDefault(), "%.1f%%", finalCpuUsage),
                mem = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                netType = netType,
                appVer = appVer,
                appVerCode = appVerCode,
                batteryCurrent = formatCurrent(currentNow),
                batteryVoltage = formatVoltage(voltageNow),
                internalStorage = formatStorage(internalTotal, internalUsed),
                internalAvailableStorage = internalAvailable,
                internalTotalStorage = internalTotal,
                internalUsedStorage = internalUsed,
                externalTotalStorage = externalTotal,
                externalUsedStorage = externalUsed,
                externalAvailableStorage = externalAvailable,
                clientIp = clientIp,
                deviceModel = deviceModel.ifEmpty { model },
                firmwareVer = firmwareVer,
                needToken = needToken,
                carrier = carrier,
                cpuTempList = cpuTempList,
                cpuFreqInfo = cpuFreqMap,
                cpuUsageInfo = cpuUsageMap,
                memTotalKb = memTotalKb,
                memAvailableKb = memAvailableKb,
                memUsedKb = memUsedKb,
                swapTotalKb = swapTotalKb,
                swapUsedKb = swapUsedKb,
                swapFreeKb = swapFreeKb,
                // === Goform 新增字段 ===
                wanIp = wanIp,
                wanIpv6 = wanIpv6,
                pdpTypeGoform = pdpType,
                goformImei = goformImei,
                goformImsi = goformImsi,
                goformIccid = goformIccid,
                hardwareVersion = hwVersion,
                webVersion = wbVersion,
                macAddress = macAddr,
                pinStatusCode = pinStatusCode,
                monthlyUploadBytes = mTx,
                monthlyDownloadBytes = mRx,
                dailyRawBytes = dailyRaw,
                monthlyRawBytes = monthlyRaw
            )
        } catch (e: CancellationException) {
            // 协程被取消 → 不视为错误，直接重新抛出让协程栈正确处理
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "Parse Error: ${e.message}"
            DebugLogger.flushToFile() // 异常落盘
            null
        }
    }

    /** 封装 HTTP 响应（body 已读取，连接已由 executeRequest 内部关闭） */
    private data class HttpResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String
    )

    /**
     * 非阻塞网络请求：使用 OkHttp 异步 enqueue + suspendCancellableCoroutine，
     * 不阻塞 IO 线程，且支持协程取消。
     * 内部自动关闭 Response，调用方无需手动 use{}，从根源消除连接泄漏风险。
     * 每次 resume 前检查 isActive，防止协程取消后 OkHttp 回调仍触发导致 IllegalStateException。
     */
    private suspend fun executeRequest(req: Request): HttpResponse? =
        suspendCancellableCoroutine { continuation ->
            val call = NetUtil.client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = it.body?.string() ?: ""
                            if (continuation.isActive) {
                                continuation.resume(HttpResponse(it.code, it.isSuccessful, body))
                            }
                        }
                    } catch (e: IOException) {
                        // body.string() 中途断网抛异常 → OkHttp 已回调 onResponse 不会调 onFailure
                        // 必须手动 resume，否则协程永久挂起
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
        }

    private suspend fun fetchApi(path: String, t: Long, auth: String, context: Context): JSONObject? {
        val purePath = if (path.contains("?")) path.substringBefore("?") else path
        val sign = NetUtil.generateKanoSign("GET", purePath, t, context)

        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val reqBuilder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .addHeader("Authorization", auth)
        // §2 鉴权要求 4 个头：缺 X-Device-Token 时 login_token_enabled=true 的设备会返回 401
        val deviceToken = SPUtil.getDeviceToken(context)
        if (deviceToken.isNotEmpty()) {
            reqBuilder.addHeader("X-Device-Token", deviceToken)
        }
        val req = reqBuilder.build()
            
        val resp = executeRequest(req) ?: run {
            lastError = "Network error (timeout or unreachable)"
            DebugLogger.logApiErr(TAG, "Request failed: $baseUrl$path - $lastError")
            return null
        }
        lastRawResponse = resp.body

        return if (resp.isSuccessful && resp.body.isNotEmpty()) {
            DebugLogger.logApi(TAG, "API Success [$path], code=${resp.code}, resp=${resp.body}")
            try {
                JSONObject(resp.body)
            } catch (e: org.json.JSONException) {
                // 单个端点 JSON 解析失败不应影响其他并发请求
                lastError = "JSON parse error on $purePath: ${e.message}"
                DebugLogger.logApiErr(TAG, "fetchApi JSON parse failed: ${e.message}")
                null
            }
        } else {
            lastError = "HTTP ${resp.code} on $purePath"
            DebugLogger.logApiErr(TAG, "API Error [$path], code=${resp.code}, resp=${resp.body}")
            null
        }
    }

    /** 无需认证的 API 请求 (version_info / need_token) */
    private suspend fun fetchApiNoAuth(path: String, t: Long, context: Context): JSONObject? {
        val sign = NetUtil.generateKanoSign("GET", path, t, context)
        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .build()

        val resp = executeRequest(req) ?: return null
        return if (resp.isSuccessful && resp.body.isNotEmpty()) {
            JSONObject(resp.body)
        } else {
            null
        }
    }

    /**
     * 确保设备级令牌 device_token 就绪（供 X-Device-Token 头使用）。
     *
     * device_token 由免鉴权的 /api/need_token 公开返回，随设备持久化不变。
     * 优先级：SP 已存 → need_token JSON 缓存 → 免鉴权拉取一次。
     * 必须在任何鉴权请求（fetchApi）之前调用，否则首个请求会因缺头返回 401。
     */
    private suspend fun ensureDeviceToken(t: Long, context: Context): String {
        SPUtil.getDeviceToken(context).takeIf { it.isNotEmpty() }?.let { return it }

        // 尝试从已有 need_token 缓存中提取
        val cached = SPUtil.getCachedNeedTokenJson(context)
        if (cached.isNotEmpty()) {
            try {
                JSONObject(cached).optString("device_token", "").takeIf { it.isNotEmpty() }?.let {
                    SPUtil.setDeviceToken(context, it)
                    return it
                }
            } catch (_: org.json.JSONException) { /* 缓存损坏，继续走网络拉取 */ }
        }

        // 免鉴权拉取一次并落地缓存
        val resp = fetchApiNoAuth(SPUtil.getNeedTokenPath(context), t, context) ?: return ""
        SPUtil.setCachedNeedTokenJson(context, resp.toString())
        SPUtil.setNeedTokenCacheTime(context, System.currentTimeMillis())
        val token = resp.optString("device_token", "")
        if (token.isNotEmpty()) {
            SPUtil.setDeviceToken(context, token)
            DebugLogger.logApi(TAG, "device_token acquired")
        }
        return token
    }

    /**
     * 从 JSON 中提取流量字节数，自动适配不同单位和字段名。
     * 
     * 策略：
     * 1. 优先信任字段名：含 "bytes" 或以 "_data" 结尾的字段，始终视为 Bytes。
     * 2. 只有在字段名不明确（如 "flow"）且数值极小时，才进行单位推断。
     * 3. 增加单位推断的安全性，防止误乘导致流量显示异常。
     */
    private fun extractTrafficBytes(json: JSONObject?, vararg keys: String): Long {
        if (json == null) return 0L
        
        for (key in keys) {
            val keyLower = key.lowercase()
            // 明确定义为字节的字段名
            val isExplicitBytes = keyLower.contains("bytes") || 
                                 keyLower.endsWith("_data") || 
                                 keyLower.contains("traffic")
            
            val longVal = json.optLong(key, -1L)
            if (longVal >= 0) {
                if (isExplicitBytes || longVal > 500_000_000L) { // >500MB 极大概率是 Bytes
                    Log.d(TAG, "  $key=$longVal -> 识别为 Bytes")
                    return longVal
                }
                
                // 模糊字段名且数值较小：保守推断
                return when {
                    longVal == 0L -> 0L
                    longVal < 100L -> { // 如 1.5 或 80，推断为 GB
                        Log.d(TAG, "  $key=$longVal -> 推断为 GB")
                        longVal * 1024L * 1024L * 1024L
                    }
                    longVal < 100_000L -> { // 如 1500，推断为 MB
                        Log.d(TAG, "  $key=$longVal -> 推断为 MB")
                        longVal * 1024L * 1024L
                    }
                    else -> { // 其他情况默认 Bytes，不再乘以 1024 防止溢出
                        Log.d(TAG, "  $key=$longVal -> 默认识别为 Bytes")
                        longVal
                    }
                }
            }
            
            // 字符串处理
            val strVal = json.optString(key, "")
            if (strVal.isNotEmpty() && strVal != "null") {
                val parsed = strVal.toDoubleOrNull()
                if (parsed != null && parsed >= 0) {
                    if (isExplicitBytes || parsed > 500_000_000.0) return parsed.toLong()
                    
                    return when {
                        parsed < 100.0 -> (parsed * 1024.0 * 1024.0 * 1024.0).toLong()
                        parsed < 100_000.0 -> (parsed * 1024.0 * 1024.0).toLong()
                        else -> parsed.toLong()
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

    // ══════════════════════════════════════════════════════════════
    // 轻量级通知监控数据获取
    // ══════════════════════════════════════════════════════════════

    /**
     * 从设备信息 API 中提取的轻量级通知监控数据。
     * 仅包含通知阈值检查所需的字段，不包含完整设备状态。
     */
    data class NotificationBaseInfo(
        val dailyFlowStr: String,
        val monthlyFlowStr: String,
        val tempStr: String,
        val cpuStr: String,
        val memStr: String,
        val batteryPercent: Int
    )

    /**
     * 轻量级数据获取，仅用于通知监控。
     *
     * 与 [getWifiData] 不同，此方法：
     * - 只请求 /api/deviceInfo 一个端点，没有冷却延迟
     * - 不发起并发请求（无 signal / goform / version / token / AT 请求）
     * - 仅解析通知阈值检查所需的字段
     *
     * 性能开销约为完整 [getWifiData] 的 1/6 ~ 1/10，
     * 适合高频调用（如 60 秒间隔）。
     */
    suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo? = withContext(Dispatchers.IO) {
        try {
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)
            // 确保 device_token 就绪（X-Device-Token 头必需，缺失将导致 401）
            ensureDeviceToken(t, context)
            val baseResp = fetchApi(SPUtil.getDeviceInfoPath(context), t, auth, context) ?: return@withContext null

            // 提取通知所需字段
            val batteryPercent = baseResp.optInt("battery", -1)
            val cpuUsage = baseResp.optDouble("cpu_usage", -1.0)
            val memUsage = baseResp.optDouble("mem_usage", 0.0)
            val tempRaw = baseResp.optDouble("cpu_temp", 0.0)

            val dTx = extractTrafficBytes(baseResp, "daily_tx_bytes")
            val dRx = extractTrafficBytes(baseResp, "daily_rx_bytes")
            val dTotal = extractTrafficBytes(baseResp, "daily_data", "day_data")
            val dailyRaw = if (dTotal > 0) dTotal else (dTx + dRx)

            val mTx = extractTrafficBytes(baseResp, "monthly_tx_bytes")
            val mRx = extractTrafficBytes(baseResp, "monthly_rx_bytes")
            val mTotal = extractTrafficBytes(baseResp, "monthly_data", "month_data", "total_data")
            var monthlyRaw = if (mTotal > 0) mTotal else (mTx + mRx)

            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            }

            val finalCpuUsage = if (cpuUsage in 0.0..100.0) cpuUsage else 0.0

            NotificationBaseInfo(
                dailyFlowStr = formatFlow(dailyRaw),
                monthlyFlowStr = formatFlow(monthlyRaw),
                tempStr = formatTemp(tempRaw),
                cpuStr = String.format(Locale.getDefault(), "%.1f%%", finalCpuUsage),
                memStr = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                batteryPercent = if (batteryPercent >= 0) batteryPercent else -1
            )
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播，不能被吞掉
        } catch (e: Exception) {
            lastError = "Notification fetch: ${e.message}"
            DebugLogger.logApiErr(TAG, "fetchNotificationBaseInfo: ${e.message}")
            null
        }
    }

    // ===== 协议自动探测 =====

    /**
     * 自动探测目标主机支持的协议（HTTPS 优先，失败回退 HTTP）。
     * 通过请求 /api/need_token（无需认证）验证连接是否可用。
     *
     * @return "https" / "http" / null（两者均不可达）
     */
    suspend fun probeProtocol(context: Context): String? = withContext(Dispatchers.IO) {
        val host = SPUtil.getDeviceHost(context)
        // 私有 IP 无需探测，始终 HTTP
        if (SPUtil.isPrivateOrLocalIp(host)) return@withContext null

        // 获取用户填写的端口（如果有）
        val raw = SPUtil.getDeviceAddress(context).trim()
        val userPort = PORT_RE.find(raw)?.groupValues?.get(1)?.toIntOrNull()


        val testPath = SPUtil.getNeedTokenPath(context)
        val t = System.currentTimeMillis()

        // 1) 先试 HTTPS
        val httpsPort = userPort ?: 443
        val httpsUrl = "https://$host:$httpsPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTPS $httpsUrl")
        if (tryProbeRequest(httpsUrl, t, context)) {
            Log.d(TAG, "probeProtocol: HTTPS OK")
            return@withContext "https"
        }

        // 2) 回退 HTTP
        val httpPort = userPort ?: 80
        val httpUrl = "http://$host:$httpPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTP $httpUrl")
        if (tryProbeRequest(httpUrl, t, context)) {
            Log.d(TAG, "probeProtocol: HTTP OK")
            return@withContext "http"
        }

        Log.d(TAG, "probeProtocol: both failed")
        null
    }

    /** 单次探测请求：成功返回 true（响应是合法 JSON） */
    private suspend fun tryProbeRequest(url: String, t: Long, context: Context): Boolean {
        return try {
            val sign = NetUtil.generateKanoSign("GET", SPUtil.getNeedTokenPath(context), t, context)
            val req = Request.Builder()
                .url(url)
                .addHeader("kano-t", t.toString())
                .addHeader("kano-sign", sign)
                .build()
            val resp = executeRequest(req) ?: return false
            if (!resp.isSuccessful) return false
            resp.body.trimStart().startsWith("{")  // 合法 JSON 对象
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播
        } catch (e: Exception) {
            false
        }
    }

    /** 运营商识别 (MCC 460)：基于 PLMN 或 IMSI 前缀 */
    private fun mapPlmnToCarrier(input: String): String {
        if (input.length < 5) return ""
        
        // 统一取前 5 位 (MCC 3 + MNC 2) 处理中国主流运营商
        // 460 00/02/07/08 -> 移动
        // 460 01/06/09    -> 联通
        // 460 03/05/11    -> 电信
        // 460 15          -> 广电
        
        if (input.startsWith("460")) {
            return when (input.substring(3, 5)) {
                "00", "02", "07", "08" -> "中国移动"
                "01", "06", "09" -> "中国联通"
                "03", "05", "11" -> "中国电信"
                "15" -> "中国广电"
                else -> ""
            }
        }
        return ""
    }
}

data class WifiEntity(
    val model: String,
    val flow: String,
    val dailyFlow: String,
    val signal: String,
    val temp: String,
    val battery: String,
    val batteryPercent: Int,        // 原始电池百分比（-1 为无数据）
    val cpu: String,
    val mem: String,
    val netType: String,
    // === /api/baseDeviceInfo 新增字段 ===
    val appVer: String,           // UFI-TOOLS 版本号
    val appVerCode: String,       // UFI-TOOLS 版本代码 (如 20260601)
    val batteryCurrent: String,   // 电池电流 (mA)
    val batteryVoltage: String,   // 电池电压 (V)
    val internalStorage: String,  // 内部存储 已用/总容量 (格式化)
    val internalAvailableStorage: Long,  // 内部存储可用 (Bytes)
    val internalTotalStorage: Long,      // 内部存储总量 (Bytes)
    val internalUsedStorage: Long,       // 内部存储已用 (Bytes)
    val externalTotalStorage: Long,      // 外部存储总量 (Bytes)，0 表示无外部存储
    val externalUsedStorage: Long,       // 外部存储已用 (Bytes)
    val externalAvailableStorage: Long,  // 外部存储可用 (Bytes)
    val clientIp: String,         // 设备 IP 地址
    // === /api/version_info 字段 ===
    val deviceModel: String,      // 设备硬件型号 (如 U30 Air)
    val firmwareVer: String,      // 固件版本
    // === /api/need_token 字段 ===
    val needToken: Boolean,       // 是否需要登录验证
    // === 运营商（Goform network_provider 或 IMSI 推导）===
    val carrier: String,          // 运营商名称（如 "China Mobile" / "中国移动"）
    // === 详细硬件信息（用于主界面弹窗详情）===
    val cpuTempList: List<CpuTempItem>,     // cpu_temp_list 各模块温度
    val cpuFreqInfo: Map<String, CpuFreqItem>, // cpuFreqInfo 各核心频率
    val cpuUsageInfo: Map<String, String>,  // cpuUsageInfo 各核心使用率
    val memTotalKb: Long,         // 内存总量 KB
    val memAvailableKb: Long,     // 内存可用 KB
    val memUsedKb: Long,          // 内存已用 KB
    val swapTotalKb: Long,        // Swap 总量 KB
    val swapUsedKb: Long,         // Swap 已用 KB
    val swapFreeKb: Long,         // Swap 空闲 KB
    // === Goform 设备身份+网络（新增）===
    val wanIp: String,            // WAN IPv4 地址
    val wanIpv6: String,          // WAN IPv6 地址
    val pdpTypeGoform: String,    // PDP 承载类型（IPv4/IPv6/IPv4v6）
    val goformImei: String,       // IMEI（Goform 来源）
    val goformImsi: String,       // IMSI（Goform 来源）
    val goformIccid: String,      // ICCID（Goform 来源）
    val hardwareVersion: String,  // 硬件版本号（Goform 来源）
    val webVersion: String,       // Web/固件版本号（Goform 来源）
    val macAddress: String,       // 设备 MAC 地址
    val pinStatusCode: Int,       // SIM PIN 状态：0=已解锁，1=需PIN，2=PUK锁定，-1=无数据
    val monthlyUploadBytes: Long, // 当月上行流量 (Bytes)
    val monthlyDownloadBytes: Long, // 当月下行流量 (Bytes)
    val dailyRawBytes: Long,       // 日流量原始字节数
    val monthlyRawBytes: Long,     // 月流量原始字节数
)

data class CpuTempItem(
    val type: String,   // 模块名称 (如 pa-thmzone, gpu-thmzone)
    val temp: Double     // 原始温度值 (>1000 时需 /1000)
)

data class CpuFreqItem(
    val cur: Int,   // 当前频率 MHz
    val max: Int    // 最大频率 MHz
)
