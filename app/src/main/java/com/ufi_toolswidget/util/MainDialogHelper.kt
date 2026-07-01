package com.ufi_toolswidget.util

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 主界面详情弹窗的内容构建工具 — 从 MainActivity 抽离。
 *
 * 提供：
 * - 各详情弹窗的 LinearLayout fillXxx() 扩展函数
 * - 弹窗复用的 UI 辅助方法（sectionTitle、keyValue、divider、emptyHint）
 * - 格式化辅助方法（温度、电池、存储等）
 */
object MainDialogHelper {

    // ==================== 弹窗内容构建（LinearLayout 扩展函数） ====================

    fun LinearLayout.fillNetworkDetail(context: Context, data: WifiEntity) {
        var hasAny = false

        // 当前网络（运营商 + 制式，均来自 Goform）
        if (data.carrier.isNotEmpty() || data.netType.isNotEmpty()) {
            addView(sectionTitleView(context, "当前网络"))
            if (data.carrier.isNotEmpty()) { addView(keyValueView(context, "运营商", data.carrier)); hasAny = true }
            if (data.netType.isNotEmpty()) { addView(keyValueView(context, "网络制式", data.netType)); hasAny = true }
        }

        // 信号（Goform RSRP）
        if (data.signal.isNotEmpty() && data.signal != "--") {
            addView(dividerView(context))
            addView(sectionTitleView(context, "信号"))
            addView(keyValueView(context, "RSRP", data.signal))
            hasAny = true
        }

        // 设备标识 + SIM
        if (data.goformImei.isNotEmpty() || data.goformImsi.isNotEmpty() || data.goformIccid.isNotEmpty()) {
            addView(dividerView(context))
            addView(sectionTitleView(context, "设备标识"))
            if (data.goformImei.isNotEmpty()) addView(keyValueView(context, "IMEI", data.goformImei))
            if (data.goformImsi.isNotEmpty()) addView(keyValueView(context, "IMSI", data.goformImsi))
            if (data.goformIccid.isNotEmpty()) addView(keyValueView(context, "ICCID", data.goformIccid))
            hasAny = true
        }

        // SIM PIN 状态（Goform pin_status）
        val pinDisplay = when (data.pinStatusCode) {
            0 -> "READY (已解锁)"
            1 -> "SIM PIN (需输入)"
            2 -> "SIM PUK (已锁定)"
            else -> ""
        }
        if (pinDisplay.isNotEmpty()) {
            addView(dividerView(context))
            addView(sectionTitleView(context, "SIM 状态"))
            addView(keyValueView(context, "PIN 状态", pinDisplay, pinDisplay.contains("PUK") || pinDisplay.contains("SIM PIN")))
            hasAny = true
        }

        // 月流量明细
        if (data.monthlyUploadBytes > 0 || data.monthlyDownloadBytes > 0) {
            addView(dividerView(context))
            addView(sectionTitleView(context, "月流量明细"))
            if (data.monthlyDownloadBytes > 0) {
                val dlGb = data.monthlyDownloadBytes / (1024.0 * 1024.0 * 1024.0)
                addView(keyValueView(context, "下行", String.format(Locale.getDefault(), "%.2f GB", dlGb)))
            }
            if (data.monthlyUploadBytes > 0) {
                val ulGb = data.monthlyUploadBytes / (1024.0 * 1024.0 * 1024.0)
                addView(keyValueView(context, "上行", String.format(Locale.getDefault(), "%.2f GB", ulGb)))
            }
            hasAny = true
        }

        if (!hasAny) {
            addView(emptyHintView(context, "暂无网络数据"))
        }
    }

    fun LinearLayout.fillTemperature(context: Context, data: WifiEntity) {
        if (data.cpuTempList.isEmpty()) {
            addView(emptyHintView(context, "暂无温度数据"))
        } else {
            addView(sectionTitleView(context, "各分区温度"))
            data.cpuTempList.sortedByDescending { it.temp }.forEach { item ->
                val celsius = if (item.temp > 1000) item.temp / 1000.0 else item.temp
                val name = item.type.removeSuffix("-thmzone").replace("_", " ")
                addView(keyValueView(context, name, "%.1f℃".format(celsius), celsius >= 60))
            }
        }
    }

    fun LinearLayout.fillCpuDetail(context: Context, data: WifiEntity) {
        var hasData = false
        if (data.cpuFreqInfo.isNotEmpty()) {
            addView(sectionTitleView(context, "核心频率 (MHz)"))
            data.cpuFreqInfo.toSortedMap(compareBy { it.removePrefix("cpu").toIntOrNull() ?: 99 })
                .forEach { (key, freq) ->
                    addView(keyValueView(context, key, "${freq.cur} / ${freq.max} MHz"))
                }
            hasData = true
        }
        if (data.cpuUsageInfo.isNotEmpty()) {
            if (hasData) addView(dividerView(context))
            addView(sectionTitleView(context, "核心使用率"))
            val sorted = data.cpuUsageInfo.toList().sortedBy { (k, _) ->
                if (k == "cpu") -1 else k.removePrefix("cpu").toIntOrNull() ?: 99
            }
            sorted.forEach { (key, usage) ->
                val label = if (key == "cpu") "总体" else key
                addView(keyValueView(context, label, "$usage%"))
            }
            hasData = true
        }
        if (!hasData) addView(emptyHintView(context, "暂无详细 CPU 数据"))
    }

    fun LinearLayout.fillMemDetail(context: Context, data: WifiEntity) {
        var hasData = false
        if (data.memTotalKb > 0) {
            addView(sectionTitleView(context, "内存 (RAM)"))
            addView(keyValueView(context, "总量", formatKb(data.memTotalKb)))
            addView(keyValueView(context, "已用", formatKb(data.memUsedKb)))
            addView(keyValueView(context, "可用", formatKb(data.memAvailableKb)))
            addView(keyValueView(context, "使用率", data.mem))
            hasData = true
        }
        if (data.swapTotalKb > 0) {
            if (hasData) addView(dividerView(context))
            addView(sectionTitleView(context, "交换分区 (SWAP)"))
            addView(keyValueView(context, "总量", formatKb(data.swapTotalKb)))
            addView(keyValueView(context, "已用", formatKb(data.swapUsedKb)))
            addView(keyValueView(context, "空闲", formatKb(data.swapFreeKb)))
            val swapPct = if (data.swapTotalKb > 0)
                "%.1f%%".format(data.swapUsedKb * 100.0 / data.swapTotalKb) else "--"
            addView(keyValueView(context, "使用率", swapPct))
            hasData = true
        }
        if (!hasData) addView(emptyHintView(context, "暂无详细内存数据"))
    }

    fun LinearLayout.fillStorageDetail(context: Context, data: WifiEntity) {
        var hasData = false
        if (data.internalTotalStorage > 0) {
            addView(sectionTitleView(context, "内部存储"))
            addView(keyValueView(context, "总量", formatStorageGb(data.internalTotalStorage)))
            addView(keyValueView(context, "已用", formatStorageGb(data.internalUsedStorage)))
            val avail = if (data.internalAvailableStorage > 0) data.internalAvailableStorage
                else data.internalTotalStorage - data.internalUsedStorage
            addView(keyValueView(context, "可用", formatStorageGb(avail)))
            val pct = if (data.internalTotalStorage > 0)
                "%.1f%%".format(data.internalUsedStorage * 100.0 / data.internalTotalStorage) else "--"
            addView(keyValueView(context, "使用率", pct))
            hasData = true
        }
        if (data.externalTotalStorage > 0) {
            if (hasData) addView(dividerView(context))
            addView(sectionTitleView(context, "外部存储"))
            addView(keyValueView(context, "总量", formatStorageGb(data.externalTotalStorage)))
            addView(keyValueView(context, "已用", formatStorageGb(data.externalUsedStorage)))
            val extAvail = if (data.externalAvailableStorage > 0) data.externalAvailableStorage
                else data.externalTotalStorage - data.externalUsedStorage
            addView(keyValueView(context, "可用", formatStorageGb(extAvail)))
            val extPct = if (data.externalTotalStorage > 0)
                "%.1f%%".format(data.externalUsedStorage * 100.0 / data.externalTotalStorage) else "--"
            addView(keyValueView(context, "使用率", extPct))
            hasData = true
        }
        if (!hasData) addView(emptyHintView(context, "暂无详细存储数据"))
    }

    fun LinearLayout.fillFirmwareDetail(context: Context, data: WifiEntity) {
        addView(sectionTitleView(context, "软件版本"))
        try {
            val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            addView(keyValueView(context, "应用版本", "v$appVersion"))
        } catch (e: Exception) { DebugLogger.w("MainDialogHelper", "fillFirmwareDetail failed: ${e.message}") }
        if (data.appVer.isNotEmpty()) addView(keyValueView(context, "接口版本", data.appVer))

        if (data.hardwareVersion.isNotEmpty() || data.webVersion.isNotEmpty() || data.macAddress.isNotEmpty()) {
            addView(dividerView(context))
            addView(sectionTitleView(context, "设备信息"))
            if (data.hardwareVersion.isNotEmpty()) addView(keyValueView(context, "硬件版本", data.hardwareVersion))
            if (data.webVersion.isNotEmpty()) addView(keyValueView(context, "固件版本", data.webVersion))
            if (data.macAddress.isNotEmpty()) addView(keyValueView(context, "MAC 地址", data.macAddress))
        }
    }

    fun LinearLayout.fillIpDetail(context: Context, data: WifiEntity) {
        addView(sectionTitleView(context, "局域网 (LAN)"))
        if (data.clientIp.isNotEmpty()) {
            addView(keyValueView(context, "设备 IP", data.clientIp))
        }
        val gateway = SPUtil.getDeviceHost(context)
        addView(keyValueView(context, "网关地址", gateway))

        val wanIpv4 = data.wanIp
        if (wanIpv4.isNotEmpty() || data.wanIpv6.isNotEmpty()) {
            addView(dividerView(context))
            addView(sectionTitleView(context, "广域网 (WAN)"))
            if (wanIpv4.isNotEmpty()) {
                addView(keyValueView(context, "IPv4 地址", wanIpv4))
            }
            if (data.wanIpv6.isNotEmpty()) {
                addView(keyValueView(context, "IPv6 地址", data.wanIpv6))
            }
            if (data.pdpTypeGoform.isNotEmpty()) {
                addView(keyValueView(context, "承载类型", data.pdpTypeGoform))
            }
        }
    }

    // ==================== UI 辅助方法 ====================

    fun sectionTitleView(context: Context, title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(ThemeColors.textPrimary(context))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 6)
        }
    }

    fun keyValueView(context: Context, key: String, value: String, highlightRed: Boolean = false): TextView {
        return TextView(context).apply {
            text = "  $key :  $value"
            textSize = 13f
            setTextColor(if (highlightRed) 0xFFE53935.toInt()
                else ThemeColors.textPrimary(context))
            setPadding(0, 3, 0, 3)
        }
    }

    fun dividerView(context: Context): View {
        return View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2
            )
            setBackgroundColor(ThemeColors.divider(context))
            alpha = 0.3f
        }
    }

    fun emptyHintView(context: Context, hint: String): TextView {
        return TextView(context).apply {
            text = hint
            textSize = 14f
            setTextColor(ThemeColors.textSecondary(context))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
    }

    // ==================== 格式化辅助方法 ====================

    /** 从 cpu_temp_list 取最高温度显示 */
    fun getHighestTemp(data: WifiEntity): String {
        if (data.cpuTempList.isNotEmpty()) {
            val highest = data.cpuTempList.maxByOrNull { it.temp }?.temp ?: 0.0
            if (highest > 0) {
                val celsius = if (highest > 1000) highest / 1000.0 else highest
                return "%.1f℃".format(celsius)
            }
        }
        return data.temp.ifEmpty { "--" }
    }

    /** 电池：分离为主百分比和副信息 */
    fun buildBatteryParts(data: WifiEntity): Pair<String, String?> {
        val pct = data.battery.ifEmpty { null }
        val curStr = data.batteryCurrent.ifEmpty { null }
        val vol = data.batteryVoltage.ifEmpty { null }
        val main = pct ?: "--"
        val curMa = curStr?.let { parseCurrentMa(it) } ?: -1
        if (curMa > 50 && curStr != null && vol != null && vol != "--") {
            val sub = "⚡充电 $curStr · $vol"
            return Pair(main, sub)
        }
        return Pair(main, null)
    }

    fun LinearLayout.fillBatteryDetail(context: Context, data: WifiEntity) {
        val pct = data.battery.ifEmpty { null }
        val curStr = data.batteryCurrent.ifEmpty { null }
        val volStr = data.batteryVoltage.ifEmpty { null }

        var hasData = false

        if (pct != null || volStr != null || curStr != null) {
            addView(sectionTitleView(context, "电池状态"))

            // 电池百分比
            if (pct != null) {
                addView(keyValueView(context, "电量", pct))
                hasData = true
            }

            // 电压 (V)
            if (volStr != null && volStr != "--") {
                addView(keyValueView(context, "电压", volStr))
                hasData = true
            }

            // 电流 (mA)
            if (curStr != null && curStr != "--") {
                addView(keyValueView(context, "电流", curStr))
                hasData = true

                // 功率估算: P = V × I
                if (volStr != null && volStr != "--") {
                    try {
                        val v = volStr.replace("V", "").trim().toFloatOrNull()
                        val curMa = curStr.replace("mA", "").trim().toFloatOrNull()
                        if (v != null && curMa != null && v > 0 && curMa > 0) {
                            val powerW = v * curMa / 1000.0
                            addView(keyValueView(context, "功率", "%.2f W".format(powerW)))
                            hasData = true
                        }
                    } catch (e: Exception) { DebugLogger.w("MainDialogHelper", "power calc failed: ${e.message}") }
                }

                // 充电状态
                val curMaValue = curStr.replace("mA", "").trim().toIntOrNull() ?: 0
                val chargeStatus = when {
                    curMaValue > 50 -> "充电中"
                    curMaValue > 0 -> "涓流充电"
                    else -> "未充电"
                }
                addView(keyValueView(context, "状态", chargeStatus))
            }
        }

        if (!hasData) {
            addView(emptyHintView(context, "暂无电池数据"))
        }
    }

    fun LinearLayout.fillTrafficDetail(context: Context, data: WifiEntity) {
        addView(sectionTitleView(context, "当日流量"))
        addView(keyValueView(context, "已使用", data.dailyFlow.ifEmpty { "--" }))
        addView(dividerView(context))
        addView(sectionTitleView(context, "月度流量"))
        addView(keyValueView(context, "已使用", data.flow.ifEmpty { "--" }))
    }

    /** 解析电流字符串返回数值 mA，解析失败返回 -1 */
    fun parseCurrentMa(curStr: String): Int {
        return try {
            curStr.replace("mA", "").replace("A", "").trim().toFloatOrNull()?.toInt() ?: -1
        } catch (_: Exception) { -1 }
    }

    /** KB → 可读格式 */
    fun formatKb(kb: Long): String {
        return when {
            kb >= 1024 * 1024 -> String.format("%.2f GB", kb / (1024.0 * 1024.0))
            kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
            else -> "${kb} KB"
        }
    }

    /** Bytes → GB */
    fun formatStorageGb(bytes: Long): String {
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
