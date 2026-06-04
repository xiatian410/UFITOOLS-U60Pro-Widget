package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.coroutines.resumeWithException

object WifiCrawl {
    
    private const val TAG = "WifiCrawl"
    
    var lastRawResponse: String = ""
    var lastError: String = ""

    suspend fun getWifiData(context: Context): WifiEntity? = withContext(Dispatchers.IO) {
        try {
            lastError = ""
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)
            val baseUrl = SPUtil.buildBaseUrl(context)
            
            DebugLogger.i(TAG, "Starting data refresh from $baseUrl")

            // 1. 获取系统全量信息 (需认证)
            val baseInfo = fetchApi("/api/baseDeviceInfo", t, auth, context)
            if (baseInfo == null) {
                DebugLogger.e(TAG, "Failed to fetch baseDeviceInfo: $lastError")
                return@withContext null
            }
            
            // 2, 3, 4 并行获取
            val signalDeferred = async { 
                fetchApi("/api/goform/goform_get_cmd_process?is_all=true&cmd=lte_rsrp,Z5g_rsrp,network_type,rssi", t, auth, context)
            }
            val versionDeferred = async { fetchApiNoAuth("/api/version_info", t, context) }
            val tokenDeferred = async { fetchApiNoAuth("/api/need_token", t, context) }
            val atNetworkDeferred = async { fetchAtNetworkInfo(t, auth, context) }

            val signalInfo = signalDeferred.await()
            val versionInfo = versionDeferred.await()
            val tokenInfo = tokenDeferred.await()
            val atNetworkInfo = atNetworkDeferred.await()

            DebugLogger.d(TAG, "Parallel tasks finished. atNetworkInfo success=${atNetworkInfo != null}")

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
                val monthlyInfo = fetchApi("/api/goform/goform_get_cmd_process?is_all=true&cmd=monthly_data", t, auth, context)
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
            val appVerCode = baseInfo.optString("app_ver_code", "")
            val currentNow = baseInfo.optInt("current_now", -1)        // 微安 µA
            val voltageNow = baseInfo.optInt("voltage_now", -1)        // 微伏 µV
            val internalTotal = baseInfo.optLong("internal_total_storage", -1L)
            val internalUsed = baseInfo.optLong("internal_used_storage", -1L)
            val internalAvailable = baseInfo.optLong("internal_available_storage", -1L)
            val externalTotal = baseInfo.optLong("external_total_storage", -1L)
            val externalUsed = baseInfo.optLong("external_used_storage", -1L)
            val externalAvailable = baseInfo.optLong("external_available_storage", -1L)
            val clientIp = baseInfo.optString("client_ip", "")

            // === cpu_temp_list 各模块温度 ===
            val cpuTempList = mutableListOf<CpuTempItem>()
            baseInfo.optJSONArray("cpu_temp_list")?.let { arr ->
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
            baseInfo.optJSONObject("cpuFreqInfo")?.let { freqObj ->
                freqObj.keys().forEach { key ->
                    val core = freqObj.optJSONObject(key) ?: return@forEach
                    cpuFreqMap[key] = CpuFreqItem(
                        cur = core.optInt("cur", 0),
                        max = core.optInt("max", 0)
                    )
                }
            }

            // === cpuUsageInfo 各核心使用率 ===
            val cpuUsageMap = mutableMapOf<String, String>()
            baseInfo.optJSONObject("cpuUsageInfo")?.let { usageObj ->
                usageObj.keys().forEach { key ->
                    cpuUsageMap[key] = usageObj.optString(key, "--")
                }
            }

            // === memInfo 详细内存信息 ===
            var memTotalKb = 0L
            var memAvailableKb = 0L
            var memUsedKb = 0L
            var swapTotalKb = 0L
            var swapUsedKb = 0L
            var swapFreeKb = 0L
            baseInfo.optJSONObject("memInfo")?.let { memObj ->
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
                batteryPercent = if (batteryPercent >= 0) batteryPercent else -1,
                cpu = String.format(Locale.getDefault(), "%.1f%%", cpuUsage),
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
                atNetworkInfo = atNetworkInfo,
                cpuTempList = cpuTempList,
                cpuFreqInfo = cpuFreqMap,
                cpuUsageInfo = cpuUsageMap,
                memTotalKb = memTotalKb,
                memAvailableKb = memAvailableKb,
                memUsedKb = memUsedKb,
                swapTotalKb = swapTotalKb,
                swapUsedKb = swapUsedKb,
                swapFreeKb = swapFreeKb
            )
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "Parse Error: ${e.message}"
            null
        }
    }

    /**
     * 非阻塞网络请求：使用 OkHttp 异步 enqueue + suspendCancellableCoroutine，
     * 不阻塞 IO 线程，且支持协程取消。
     */
    private suspend fun executeRequest(req: Request): Response? =
        suspendCancellableCoroutine { continuation ->
            val call = NetUtil.client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }
            })
        }

    private suspend fun fetchApi(path: String, t: Long, auth: String, context: Context): JSONObject? {
        val purePath = if (path.contains("?")) path.substringBefore("?") else path
        val sign = NetUtil.generateKanoSign("GET", purePath, t)
        
        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .addHeader("Authorization", auth)
            .build()
            
        val resp = executeRequest(req) ?: run {
            lastError = "Network error (timeout or unreachable)"
            DebugLogger.e(TAG, "Request failed: $baseUrl$path - $lastError")
            return null
        }
        return resp.use { response ->
            val content = response.body?.string() ?: ""
            lastRawResponse = content
            
            if (response.isSuccessful && content.isNotEmpty()) {
                DebugLogger.d(TAG, "API Success [$path], code=${response.code}, resp=$content")
                JSONObject(content)
            } else {
                lastError = "HTTP ${response.code} on $purePath"
                DebugLogger.w(TAG, "API Error [$path], code=${response.code}, resp=$content")
                null
            }
        }
    }

    /** 无需认证的 API 请求 (version_info / need_token) */
    private suspend fun fetchApiNoAuth(path: String, t: Long, context: Context): JSONObject? {
        val sign = NetUtil.generateKanoSign("GET", path, t)
        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .build()

        val resp = executeRequest(req) ?: return null
        return resp.use { response ->
            val content = response.body?.string() ?: ""
            if (response.isSuccessful && content.isNotEmpty()) {
                JSONObject(content)
            } else {
                null
            }
        }
    }

    /**
     * 从 JSON 中提取流量字节数，自动适配不同单位和字段名。
     * 
     * 字段名含 "bytes" 的（如 daily_tx_bytes）直接当作 Bytes，不做单位推断。
     * 其他字段通过数值大小推断单位：
     *   >= 100 MB 量级 → Bytes（最常见）
     *   >= 100 KB 量级 → KB
     *   >= 100 B  量级 → MB
     *         更小      → GB
     * 
     * 阈值采用 100 倍阶跃，避免字节值落入相邻区间导致误判。
     */
    private fun extractTrafficBytes(json: JSONObject?, vararg keys: String): Long {
        if (json == null) return 0L
        
        for (key in keys) {
            // 字段名含 "bytes" 或以下划线 "_data" 结尾（如 daily_data/monthly_data）
            // 均直接视作 Bytes 单位，不做数值量级推断
            val keyLower = key.lowercase()
            val forceBytes = keyLower.contains("bytes") || keyLower.endsWith("_data")
            
            // 尝试数字类型字段
            val longVal = json.optLong(key, -1L)
            if (longVal > 0) {
                if (forceBytes) {
                    Log.d(TAG, "  $key=$longVal -> 字段名含bytes，直接作为 Bytes")
                    return longVal
                }
                return when {
                    longVal >= 100_000_000L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 Bytes")
                        longVal
                    }
                    longVal >= 100_000L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 KB，转为 Bytes")
                        longVal * 1024L
                    }
                    longVal >= 100L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 MB，转为 Bytes")
                        longVal * 1024L * 1024L
                    }
                    else -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 GB，转为 Bytes")
                        longVal * 1024L * 1024L * 1024L
                    }
                }
            }
            
            // 尝试字符串类型字段（有些固件返回字符串数字，如 "1.74"）
            val strVal = json.optString(key, "")
            if (strVal.isNotEmpty() && strVal != "null") {
                val parsed = strVal.toDoubleOrNull()
                if (parsed != null && parsed > 0) {
                    if (forceBytes) {
                        Log.d(TAG, "  $key=$strVal(String) -> 字段名含bytes，直接作为 Bytes")
                        return parsed.toLong()
                    }
                    return when {
                        parsed >= 100_000_000.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 Bytes")
                            parsed.toLong()
                        }
                        parsed >= 100_000.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 KB，转为 Bytes")
                            (parsed * 1024.0).toLong()
                        }
                        parsed >= 100.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 MB，转为 Bytes")
                            (parsed * 1024.0 * 1024.0).toLong()
                        }
                        else -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 GB，转为 Bytes")
                            (parsed * 1024.0 * 1024.0 * 1024.0).toLong()
                        }
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
        val userPort = Regex(":(\\d+)$").find(raw)?.groupValues?.get(1)?.toIntOrNull()

        val testPath = "/api/need_token"
        val t = System.currentTimeMillis()

        // 1) 先试 HTTPS
        val httpsPort = userPort ?: 443
        val httpsUrl = "https://$host:$httpsPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTPS $httpsUrl")
        if (tryProbeRequest(httpsUrl, t)) {
            Log.d(TAG, "probeProtocol: HTTPS OK")
            return@withContext "https"
        }

        // 2) 回退 HTTP
        val httpPort = userPort ?: 80
        val httpUrl = "http://$host:$httpPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTP $httpUrl")
        if (tryProbeRequest(httpUrl, t)) {
            Log.d(TAG, "probeProtocol: HTTP OK")
            return@withContext "http"
        }

        Log.d(TAG, "probeProtocol: both failed")
        null
    }

    /** 单次探测请求：成功返回 true（响应是合法 JSON） */
    private suspend fun tryProbeRequest(url: String, t: Long): Boolean {
        return try {
            val sign = NetUtil.generateKanoSign("GET", "/api/need_token", t)
            val req = Request.Builder()
                .url(url)
                .addHeader("kano-t", t.toString())
                .addHeader("kano-sign", sign)
                .build()
            val resp = executeRequest(req) ?: return false
            resp.use { response ->
                if (!response.isSuccessful) return false
                val body = response.body?.string() ?: ""
                body.trimStart().startsWith("{")  // 合法 JSON 对象
            }
        } catch (e: Exception) {
            false
        }
    }

    // ===== AT 指令网络详情 =====

    /**
     * 通过 /api/AT 接口获取网络信号详情（RSRP/SINR/RSRQ/频段/运营商等）。
     * 并行执行 AT+COPS?（运营商+网络类型）、AT+QENG（信号参数）和 AT+CESQ（备选信号参数）。
     * 优先使用 QENG（原生 dBm/dB 值），无 QENG 时 fallback 到 CESQ（3GPP 编码值需换算）。
     * 失败时返回 null，不影响主流程。
     */
    private suspend fun fetchAtNetworkInfo(t: Long, auth: String, context: Context): AtSignalInfo? {
        try {
            val copsCmd = java.net.URLEncoder.encode("AT+COPS?", "UTF-8")
            val qengCmd = java.net.URLEncoder.encode("AT+QENG=\"servingcell\"", "UTF-8")
            val cesqCmd = java.net.URLEncoder.encode("AT+CESQ", "UTF-8")
            val cgsnCmd = java.net.URLEncoder.encode("AT+CGSN", "UTF-8")
            val cgeqosCmd = java.net.URLEncoder.encode("AT+CGEQOSRDP=1", "UTF-8")

            // 并行获取 COPS、QENG、CESQ、CGSN、CGEQOSRDP
            val results = kotlinx.coroutines.coroutineScope {
                val copsDef = async {
                    val resp = fetchApi("/api/AT?command=$copsCmd&slot=0", t, auth, context)
                    resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
                }
                val qengDef = async {
                    val resp = fetchApi("/api/AT?command=$qengCmd&slot=0", t, auth, context)
                    resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
                }
                val cesqDef = async {
                    val resp = fetchApi("/api/AT?command=$cesqCmd&slot=0", t, auth, context)
                    resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
                }
                val cgsnDef = async {
                    val resp = fetchApi("/api/AT?command=$cgsnCmd&slot=0", t, auth, context)
                    resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
                }
                val cgeqosDef = async {
                    val resp = fetchApi("/api/AT?command=$cgeqosCmd&slot=0", t, auth, context)
                    resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
                }
                awaitAll(copsDef, qengDef, cesqDef, cgsnDef, cgeqosDef)
            }

            val copsStr: String = results[0]
            val qengStr: String = results[1]
            val cesqStr: String = results[2]
            val cgsnStr: String = results[3]
            val cgeqosStr: String = results[4]
            if (copsStr.isEmpty() && qengStr.isEmpty() && cesqStr.isEmpty()
                && cgsnStr.isEmpty() && cgeqosStr.isEmpty()) return null

            return parseAtResponses(copsStr, qengStr, cesqStr, cgsnStr, cgeqosStr)
        } catch (e: Exception) {
            Log.w(TAG, "AT network info fetch failed: ${e.message}")
            return null
        }
    }

    /**
     * 解析 AT+COPS?、AT+QENG 和 AT+CESQ 的响应。
     *
     * 优先解析 QENG（原生 dBm/dB 值），如无 QENG 数据则 fallback 到 CESQ（3GPP 编码值需换算）。
     *
     * AT+COPS? 响应格式:
     *   +COPS: <mode>,<format>,"<operator>",<act>
     *   例: +COPS: 0,0,"CHN-UNICOM",7
     *   act: 0=GSM,2=UTRAN,7=LTE(E-UTRAN),13=NR(5G)
     *
     * AT+QENG="servingcell" 响应格式 (Quectel):
     *   LTE: "servingcell","CONNECT","LTE",<earfcn>,<band_num>,<bw_ul>,<tac>,<cellId>,<pci>,<rsrp>,<rsrq>,<rssi>,<sinr>,...
     *   NR:  "servingcell","CONNECT","NR5G-SA","FDD",<mcc>,<mnc>,"n78",<arfcn>,<freq>,<bw>,<pci>,<rsrp>,<sinr>,<rsrq>,...
     *
     * AT+CESQ 响应格式 (3GPP TS 27.007):
     *   +CESQ: <rxlev>,<ber>,<rscp>,<ecno>,<rsrq>,<rsrp>
     *   扩展格式（部分 Quectel 模块）:
     *   +CESQ: <rxlev>,<ber>,<rscp>,<ecno>,<rsrq>,<rsrp>,<sinr>,<avg_rsrp>,<avg_rsrq>
     *   99/255 = 不可用/不支持该 RAT
     *
 *   CESQ 3GPP 值换算：
 *   LTE:  RSRP(dBm)=rsrp-140,  RSRQ(dB)=rsrq/2-19.5,  SINR(dB)=sinr/5-20
 *   NR:   RSRP(dBm)=rsrp-156,  RSRQ(dB)=rsrq/2-43,     SINR(dB)=sinr/2-23
     */
    private fun parseAtResponses(copsRaw: String, qengRaw: String, cesqRaw: String = "",
                                 cgsnRaw: String = "", cgeqosRaw: String = ""): AtSignalInfo? {
        // ── 解析 COPS ──
        var operator = ""
        var networkType = ""
        var actCode = -1
        val copsClean = copsRaw.replace("\r", "").replace("\nOK", "").replace("OK", "").trim()

        val copsRe = Regex("""\+COPS:\s*\d+\s*,\s*\d+\s*,\s*"([^"]*)",?\s*(\d+)?""")
        copsRe.find(copsClean)?.let { match ->
            operator = match.groupValues.getOrElse(1) { "" }
            actCode = match.groupValues.getOrElse(2) { "-1" }.toIntOrNull() ?: -1
        }
        // 也匹配无引号的数字运营商
        if (operator.isEmpty()) {
            val copsReNum = Regex("""\+COPS:\s*\d+\s*,\s*\d+\s*,\s*(\d+),?\s*(\d+)?""")
            copsReNum.find(copsClean)?.let { match ->
                actCode = match.groupValues.getOrElse(2) { "-1" }.toIntOrNull() ?: actCode
            }
        }

        networkType = when (actCode) {
            0, 1 -> "GSM"; 2 -> "3G WCDMA"; 4 -> "3G TD-SCDMA"
            6 -> "3G CDMA"; 7 -> "4G LTE"; 13 -> "5G NR"
            else -> ""
        }

        // ── 解析 QENG（优先，原生 dBm/dB 值无需换算）──
        var rsrp = Int.MIN_VALUE
        var sinr = Int.MIN_VALUE
        var rsrq = Int.MIN_VALUE
        var band = ""
        var pci = -1
        var earfcn = -1
        var dataSource = ""  // "QENG" | "CESQ" | ""
        val qengClean = qengRaw.replace("\r", "").replace("\nOK", "").replace("OK", "").trim()

        if (qengClean.isNotEmpty() && qengClean.contains("+QENG:")) {
            // 提取 "+QENG: ..." 行并按逗号分割
            val qengLine = qengClean.substringAfter("+QENG:").trim()
            val parts = qengLine.split(",").map { it.trim().removeSurrounding("\"") }

            // 查找频段字段（B 开头 = LTE，n 开头 = NR）
            val bandIdx = parts.indexOfFirst { it.matches(Regex("^[nNB]\\d+[A-Za-z]?\$")) }

            if (bandIdx >= 0) {
                band = parts[bandIdx]
                val isNr = band.startsWith("n", ignoreCase = true)

                if (isNr && parts.size > bandIdx + 6) {
                    // NR 5G: band → arfcn → freq → bw → pci → rsrp → sinr → rsrq
                    earfcn = parts.getOrElse(bandIdx + 1) { "" }.toIntOrNull() ?: -1
                    pci    = parts.getOrElse(bandIdx + 4) { "" }.toIntOrNull() ?: -1
                    rsrp   = parts.getOrElse(bandIdx + 5) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    sinr   = parts.getOrElse(bandIdx + 6) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    rsrq   = parts.getOrElse(bandIdx + 7) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                } else if (parts.size > bandIdx + 6) {
                    // LTE 4G: band → bw → tac → cellId → pci → rsrp → rsrq → rssi → sinr
                    // 需要向前查找 earfcn（band 前 2-4 个字段）
                    for (j in maxOf(0, bandIdx - 4) until bandIdx) {
                        val v = parts[j].toIntOrNull()
                        if (v != null && v in 1..300000) { earfcn = v; break }
                    }
                    pci  = parts.getOrElse(bandIdx + 4) { "" }.toIntOrNull() ?: -1
                    rsrp = parts.getOrElse(bandIdx + 5) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    rsrq = parts.getOrElse(bandIdx + 6) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    sinr = parts.getOrElse(bandIdx + 8) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                }
            } else if (parts.size >= 15) {
                // 无频段字段，用已知位置解析 LTE
                // LTE 标准位: [3]earfcn [4]band_num [6]tac [8]pci [9]rsrp [10]rsrq [12]sinr
                earfcn = parts[3].toIntOrNull() ?: -1
                pci    = parts[8].toIntOrNull() ?: -1
                rsrp   = parts[9].toIntOrNull() ?: Int.MIN_VALUE
                rsrq   = parts[10].toIntOrNull() ?: Int.MIN_VALUE
                sinr   = parts[12].toIntOrNull() ?: Int.MIN_VALUE
            }

            if (rsrp != Int.MIN_VALUE || sinr != Int.MIN_VALUE || rsrq != Int.MIN_VALUE) {
                dataSource = "QENG"
            }
        }

        // ── 解析 CESQ（fallback，3GPP 编码值需换算）──
        if (dataSource.isEmpty()) {
            // 尝试从 qengRaw 或 cesqRaw 中提取 +CESQ / ++CESQ 行
            val searchIn = listOf(qengRaw, cesqRaw)
            var cesqClean = ""
            for (raw in searchIn) {
                if (raw.isEmpty()) continue
                // 彻底清理：去掉 \r、独立的 "OK"（单词级别）、多余空白
                // 注意：不能盲目 replace "OK"，因为 RSRQ 值中不含 "OK"
                val c = raw
                    .replace("\r\n", "\n").replace("\r", "\n")
                    .replace("\nOK", "").replace("OK\n", "")  // 行首/行尾 OK
                    .replace(" OK", " ").replace("OK ", " ")  // 单词 OK
                    .replace("\n", " ").replace("  ", " ")    // 多空白压成单空格
                    .trim()
                if (c.contains("+CESQ:", ignoreCase = true)) {
                    cesqClean = c
                    break
                }
            }

            if (cesqClean.isNotEmpty()) {
                // 提取 CESQ 数据行（处理 ++CESQ: 或 +CESQ: 前缀及可能的 AT+CESQ 命令回声）
                // 策略：找到最后一次出现的 "+CESQ:" 后的内容
                val lastCesqIdx = cesqClean.lastIndexOf("+CESQ:", ignoreCase = true)
                val afterCesq = if (lastCesqIdx >= 0) {
                    cesqClean.substring(lastCesqIdx).substringAfter("+CESQ:").trim()
                } else {
                    cesqClean.substringAfter("+CESQ:").trim()
                }
                // 分离数据（逗号前）和可能的尾缀（如 "OK" 残余）
                val dataOnly = afterCesq.split(Regex("\\s+")).firstOrNull { it.contains(",") } ?: afterCesq
                val parts = dataOnly.split(",").map { s -> s.trim().filter { it.isDigit() || it == '-' } }

                if (parts.size >= 6) {
                    val rsrqRaw = parts[4].toIntOrNull() ?: 255
                    val rsrpRaw = parts[5].toIntOrNull() ?: 255
                    var sinrRaw = 255
                    var avgRsrpRaw = 255
                    var avgRsrqRaw = 255

                    // 扩展字段（部分模块在标准 6 字段后附加 sinr/avg_rsrp/avg_rsrq）
                    if (parts.size >= 9) {
                        sinrRaw = parts[6].toIntOrNull() ?: 255
                        avgRsrpRaw = parts[7].toIntOrNull() ?: 255
                        avgRsrqRaw = parts[8].toIntOrNull() ?: 255
                    } else if (parts.size >= 7) {
                        sinrRaw = parts[6].toIntOrNull() ?: 255
                    }

                    // 从 COPS 已知 RAT，或从响应内容推断
                    var resolvedNr = actCode == 13

                    // 取值策略：标准字段优先，若为 255/99 则 fallback 到扩展字段
                    // 先用宽松范围（同时囊括 LTE 和 NR 范围）提取原始值
                    val rawRsrp = if (rsrpRaw in 0..127) rsrpRaw else if (avgRsrpRaw in 0..127) avgRsrpRaw else -1
                    val rawRsrq = if (rsrqRaw in 0..127) rsrqRaw else if (avgRsrqRaw in 0..127) avgRsrqRaw else -1
                    val rawSinr = if (sinrRaw in 0..250) sinrRaw else -1

                    // 智能 RAT 检测：如果 COPS 没明确 ACT，则根据取值范围推断
                    if (!resolvedNr && (rawRsrp in 98..127 || rawRsrq in 35..127)) {
                        // RSRP > 97 或 RSRQ > 34 仅 NR 才有，断定设备在 NR 模式
                        resolvedNr = true
                        if (networkType.isEmpty()) networkType = "5G NR"
                        Log.d(TAG, "CESQ RAT auto-detected as NR (rawRsrp=$rawRsrp rawRsrq=$rawRsrq)")
                    }

                    if (rawRsrp >= 0 || rawSinr >= 0 || rawRsrq >= 0) {
                        // 根据最终确定的 RAT 执行 3GPP 换算
                        if (resolvedNr) {
                            // NR: RSRP 0-127 → -156 to -31 dBm
                            if (rawRsrp in 0..127) rsrp = rawRsrp - 156
                            // NR: SINR 0-127 → -23 to +40.5 dB
                            if (rawSinr in 0..127) sinr = Math.round((rawSinr * 0.5 - 23).toFloat())
                            // NR: RSRQ 0-127 → -43 to +20 dB
                            if (rawRsrq in 0..127) rsrq = Math.round((rawRsrq * 0.5f - 43).toFloat())
                        } else {
                            // LTE: RSRP 0-97 → -140 to -43 dBm
                            if (rawRsrp in 0..97) rsrp = rawRsrp - 140
                            // LTE: SINR 0-250 → -20 to +30 dB
                            if (rawSinr in 0..250) sinr = Math.round((rawSinr / 5.0f - 20).toFloat())
                            // LTE: RSRQ 0-34 → -19.5 to -3 dB
                            if (rawRsrq in 0..34) rsrq = Math.round((rawRsrq * 0.5f - 19.5).toFloat())
                            else if (rawRsrq in 35..127) {
                                // 保守场景：RSRQ 在 NR 范围内（可能 NR 误判为 LTE）
                                rsrq = Math.round((rawRsrq * 0.5f - 43).toFloat())
                            }
                        }
                        dataSource = "CESQ"
                        Log.d(TAG, "CESQ parsed: raw(rsrp=$rawRsrp rsrq=$rawRsrq sinr=$rawSinr) → " +
                            "converted rsrp=$rsrp sinr=$sinr rsrq=$rsrq (RAT=${if (resolvedNr) "NR" else "LTE"})")
                    }
                }
            }
        }

        // 如果 QENG 没解析到网络类型但 COPS 也没识别，从原始数据推断
        if (networkType.isEmpty()) {
            if (qengClean.contains("NR", ignoreCase = true)) {
                networkType = "5G NR"
            } else if (cesqRaw.contains("NR", ignoreCase = true)) {
                networkType = "5G NR"
            }
        }

        // ── 解析 AT+CGSN（IMEI）──
        var imei = ""
        if (cgsnRaw.isNotEmpty()) {
            val cgsnClean = cgsnRaw
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("OK", "").trim()
            // IMEI 通常是 15 位纯数字，提取第一行中的数字序列
            val imeiRe = Regex("""(\d{15,17})""")
            imeiRe.find(cgsnClean)?.let { match ->
                imei = match.groupValues[1]
            }
        }

        // ── 解析 AT+CGEQOSRDP=1（签约速率）──
        // 返回格式: +CGEQOSRDP: <cid>,<QCI>,<0>,<0>,<0>,<0>,<DL_kbps>,<UL_kbps>
        var subscriptionRate = ""
        if (cgeqosRaw.isNotEmpty()) {
            val cgeqosClean = cgeqosRaw
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("OK", "").trim()
            Log.d(TAG, "CGEQOSRDP raw: $cgeqosClean")

            // 提取逗号分隔字段
            val afterColon = cgeqosClean.substringAfter("+CGEQOSRDP:").trim()
            val fields = afterColon.split(",").map { it.trim().toLongOrNull() ?: 0L }

            if (fields.size >= 8) {
                val qci = fields[1]
                val dlKbps = fields[6]
                val ulKbps = fields[7]
                val dlPretty = if (dlKbps >= 1000) String.format("%.1f Mbps", dlKbps / 1000.0) else "${dlKbps} kbps"
                val ulPretty = if (ulKbps >= 1000) String.format("%.1f Mbps", ulKbps / 1000.0) else "${ulKbps} kbps"
                subscriptionRate = "QCI $qci: ↓ $dlPretty  ↑ $ulPretty"
            } else {
                subscriptionRate = cgeqosClean.take(200) // fallback
            }
        }

        // 没有任何有效数据 → null
        if (rsrp == Int.MIN_VALUE && sinr == Int.MIN_VALUE && rsrq == Int.MIN_VALUE
            && networkType.isEmpty() && operator.isEmpty()
            && imei.isEmpty() && subscriptionRate.isEmpty()) {
            return null
        }

        return AtSignalInfo(
            networkType = networkType,
            operator = operator,
            rsrp = rsrp,
            sinr = sinr,
            rsrq = rsrq,
            band = band,
            pci = pci,
            earfcn = earfcn,
            rawQeng = qengRaw.ifEmpty { cesqRaw },
            rawCops = copsRaw,
            imei = imei,
            subscriptionRate = subscriptionRate
        )
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
    // === AT 指令获取的网络详情（/api/AT）===
    val atNetworkInfo: AtSignalInfo?,  // AT+QENG / AT+COPS 解析结果
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
)

data class AtSignalInfo(
    val networkType: String,   // "5G SA" / "4G LTE" / "NR" / "LTE"
    val operator: String,      // 运营商名称
    val rsrp: Int,             // RSRP (dBm)，负值，如 -95
    val sinr: Int,             // SINR (dB)，如 15
    val rsrq: Int,             // RSRQ (dB)，如 -10
    val band: String,          // 频段，如 "n78" / "B3"
    val pci: Int,              // Physical Cell ID
    val earfcn: Int,           // EARFCN / NR-ARFCN
    val rawQeng: String,       // AT+QENG 原始响应（调试用）
    val rawCops: String,       // AT+COPS 原始响应（调试用）
    val imei: String,          // AT+CGSN 获取的 IMEI
    val subscriptionRate: String, // AT+CGEQOSRDP=1 获取的签约速率
)

data class CpuTempItem(
    val type: String,   // 模块名称 (如 pa-thmzone, gpu-thmzone)
    val temp: Double     // 原始温度值 (>1000 时需 /1000)
)

data class CpuFreqItem(
    val cur: Int,   // 当前频率 MHz
    val max: Int    // 最大频率 MHz
)
