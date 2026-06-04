package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 调试日志收集器。
 * 支持内存缓存与文件持久化，提供敏感信息脱敏功能。
 */
object DebugLogger {

    private const val TAG = "DebugLogger"
    private const val MAX_ENTRIES = 800

    /** 所有日志条目（线程安全） */
    private val entries = ConcurrentLinkedQueue<Entry>()

    /** 是否启用调试模式 (通过 SP 持久化) */
    var enabled = false
        set(value) {
            field = value
            contextRef?.get()?.let { SPUtil.setDebugEnabled(it, value) }
        }

    private var contextRef: java.lang.ref.WeakReference<Context>? = null

    data class Entry(
        val time: Long,
        val level: String,
        val tag: String,
        val message: String,
    ) {
        fun formatted(): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            return "[${sdf.format(Date(time))}] [$level] [$tag] $message"
        }
    }

    /** 初始化，载入持久化配置 */
    fun init(context: Context) {
        contextRef = java.lang.ref.WeakReference(context.applicationContext)
        enabled = SPUtil.getDebugEnabled(context)
    }

    /** 记录一条调试日志 */
    fun log(level: String, tag: String, message: String) {
        if (!enabled) return
        
        // 对消息进行基础脱敏处理
        val maskedMsg = maskSensitiveInfo(message)
        val entry = Entry(System.currentTimeMillis(), level, tag, maskedMsg)
        entries.add(entry)
        
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }

        when (level) {
            "E" -> Log.e(tag, maskedMsg)
            "W" -> Log.w(tag, maskedMsg)
            "D" -> Log.d(tag, maskedMsg)
            "I" -> Log.i(tag, maskedMsg)
            else -> Log.d(tag, maskedMsg)
        }

        saveEntryToFile(entry)
    }

    private fun saveEntryToFile(entry: Entry) {
        contextRef?.get()?.let { ctx ->
            try {
                val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
                if (file.exists() && file.length() > 3 * 1024 * 1024) {
                    file.writeText("[Log truncated due to size]\n")
                }
                file.appendText(entry.formatted() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save log to file: ${e.message}")
            }
        }
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)

    fun getAll(): List<Entry> = entries.toList().reversed()
    fun getRecent(n: Int = 100): List<Entry> = entries.toList().takeLast(n).reversed()
    fun getAllText(): String = getAll().joinToString("\n") { it.formatted() }
    fun size(): Int = entries.size

    fun clear() {
        entries.clear()
        contextRef?.get()?.let { ctx ->
            try {
                val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }

    /** 获取持久化的日志内容 */
    fun getPersistentLogs(ctx: Context): String {
        return try {
            val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            "读取持久化日志失败: ${e.message}"
        }
    }

    /** 转储当前状态（含脱敏） */
    fun dumpState(context: Context): String {
        val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.appendLine("========== 系统状态快照 ==========")
        sb.appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("应用版本: ${UpdateChecker.getLocalVersionName(context)}")
        sb.appendLine()

        sb.appendLine("[连接配置]")
        sb.appendLine("目标地址: ${desensitize(SPUtil.getDeviceAddress(context))}")
        sb.appendLine("完整 URL: ${desensitize(SPUtil.buildBaseUrl(context))}")
        sb.appendLine("认证令牌: [ENCRYPTED]")
        sb.appendLine()

        sb.appendLine("[运行状态]")
        sb.appendLine("Worker 停止: ${sp.getBoolean("worker_stopped_by_failure", false)}")
        sb.appendLine("失败计数 (API/Net): ${sp.getInt("worker_api_failure_count", 0)} / ${sp.getInt("worker_network_failure_count", 0)}")
        sb.appendLine("停止原因: ${SPUtil.getWorkerStopReason(context)}")
        sb.appendLine("探测协议: ${SPUtil.getDeviceProtocol(context)}")
        sb.appendLine()

        sb.appendLine("[最后请求结果]")
        sb.appendLine("错误描述: ${WifiCrawl.lastError}")
        val rawResp = WifiCrawl.lastRawResponse
        sb.appendLine("原始响应 (脱敏): ${maskSensitiveInfo(rawResp).take(800)}")
        sb.appendLine()

        return sb.toString()
    }

    /** 智能识别并脱敏敏感信息 (IP, Token, Password, IMEI) */
    private fun maskSensitiveInfo(input: String): String {
        if (input.isEmpty()) return input
        return input
            // 脱敏 IP 地址 (只保留首位段)
            .replace(Regex("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), "$1.***.***.***")
            // 脱敏 IMEI (15-17位数字)
            .replace(Regex("\\b\\d{15,17}\\b"), "***************")
            // 脱敏 JSON 中的敏感字段
            .replace(Regex("\"(token|password|auth_token|imei)\"\\s*:\\s*\"[^\"]+\""), "\"$1\":\"***\"")
            // 脱敏 Authorization 响应头
            .replace(Regex("Authorization: [^\\s]+"), "Authorization: [MASKED]")
    }

    private fun desensitize(input: String): String {
        if (input.isEmpty()) return ""
        if (input.length <= 6) return "****"
        return input.take(3) + "****" + input.takeLast(3)
    }
}
