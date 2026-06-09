package com.ufi_toolswidget.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条警报记录。
 */
data class AlertRecord(
    val id: Long,              // 唯一 ID（System.currentTimeMillis）
    val type: String,          // 类型标识（daily_flow / monthly_flow / temp / cpu / mem / battery / device_online）
    val title: String,
    val message: String,
    val timestamp: Long,       // 触发时间戳
    val isRead: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("title", title)
        put("message", message)
        put("timestamp", timestamp)
        put("isRead", isRead)
    }

    companion object {
        fun fromJson(json: JSONObject): AlertRecord = AlertRecord(
            id = json.optLong("id"),
            type = json.optString("type", ""),
            title = json.optString("title", ""),
            message = json.optString("message", ""),
            timestamp = json.optLong("timestamp"),
            isRead = json.optBoolean("isRead", false)
        )
    }
}

/**
 * 警报历史管理器。
 *
 * 使用 SharedPreferences + JSON 存储所有警报记录。
 * 最多保留 200 条，超出时自动清理最早记录。
 */
object AlertHistoryManager {

    private const val TAG = "AlertHistoryManager"
    private const val SP_KEY = "alert_history_json"
    private const val MAX_RECORDS = 200

    private var cachedList: MutableList<AlertRecord>? = null

    /** 获取全部警报记录（最新在前） */
    @Synchronized
    fun getAll(ctx: Context): List<AlertRecord> {
        if (cachedList == null) {
            cachedList = loadFromSp(ctx)
        }
        return cachedList!!.toList()
    }

    /** 未读数量 */
    @Synchronized
    fun getUnreadCount(ctx: Context): Int {
        return getAll(ctx).count { !it.isRead }
    }

    /** 添加一条警报记录 */
    @Synchronized
    fun addAlert(ctx: Context, type: String, title: String, message: String) {
        val list = (cachedList ?: loadFromSp(ctx))
        val record = AlertRecord(
            id = System.currentTimeMillis(),
            type = type,
            title = title,
            message = message,
            timestamp = System.currentTimeMillis()
        )
        list.add(0, record)  // 最新在前
        // 超过上限则裁剪
        while (list.size > MAX_RECORDS) {
            list.removeAt(list.size - 1)
        }
        cachedList = list
        saveToSp(ctx, list)
        DebugLogger.logApi(TAG, "Alert added: type=$type title=$title (total=${list.size})")
    }

    /** 标记单条为已读 */
    @Synchronized
    fun markRead(ctx: Context, id: Long) {
        val list = (cachedList ?: loadFromSp(ctx))
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0 && !list[idx].isRead) {
            list[idx] = list[idx].copy(isRead = true)
            cachedList = list
            saveToSp(ctx, list)
        }
    }

    /** 标记全部已读 */
    @Synchronized
    fun markAllRead(ctx: Context) {
        val list = (cachedList ?: loadFromSp(ctx))
        var changed = false
        for (i in list.indices) {
            if (!list[i].isRead) {
                list[i] = list[i].copy(isRead = true)
                changed = true
            }
        }
        if (changed) {
            cachedList = list
            saveToSp(ctx, list)
        }
    }

    /** 删除单条记录 */
    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val list = (cachedList ?: loadFromSp(ctx))
        val removed = list.removeAll { it.id == id }
        if (removed) {
            cachedList = list
            saveToSp(ctx, list)
        }
    }

    /** 清空全部记录 */
    @Synchronized
    fun clearAll(ctx: Context) {
        cachedList = mutableListOf()
        saveToSp(ctx, cachedList!!)
    }

    /** 清除缓存，下次读取重新加载 */
    @Synchronized
    fun invalidateCache() {
        cachedList = null
    }

    // ── 内部存储 ──

    private fun loadFromSp(ctx: Context): MutableList<AlertRecord> {
        return try {
            val json = SPUtil.getSp(ctx).getString(SP_KEY, "") ?: ""
            if (json.isEmpty()) return mutableListOf()
            val arr = JSONArray(json)
            val list = mutableListOf<AlertRecord>()
            for (i in 0 until arr.length()) {
                list.add(AlertRecord.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            DebugLogger.e(TAG, "loadFromSp failed: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveToSp(ctx: Context, list: List<AlertRecord>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            SPUtil.getSp(ctx).edit().putString(SP_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "saveToSp failed: ${e.message}")
        }
    }
}
