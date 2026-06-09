package com.ufi_toolswidget.util

import android.content.Context
import android.content.Intent
import androidx.paging.PagingSource
import com.ufi_toolswidget.db.AlertDao
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 警报历史管理器（Room 实现）。
 *
 * 提供与旧 SP 版本兼容的 API，内部使用 Room 数据库存储。
 * 所有修改操作完成后发送 [ACTION_DATA_CHANGED] 广播，
 * 供外部组件（如 MainActivity 未读红点）响应数据变更。
 */
object AlertHistoryManager {

    private const val TAG = "AlertHistoryManager"
    const val ACTION_DATA_CHANGED = "com.ufi_toolswidget.ALERT_HISTORY_CHANGED"

    @Volatile
    private var dao: AlertDao? = null

    /** 初始化数据库连接（建议在 Application.onCreate 中调用） */
    fun initDatabase(context: Context) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) {
                    dao = AppDatabase.getInstance(context).alertDao()
                }
            }
        }
    }

    private fun getDao(): AlertDao =
        dao ?: throw IllegalStateException("AlertHistoryManager not initialized. Call initDatabase() first.")

    /** 分页查询（PagingSource） */
    fun getAllPaged(): PagingSource<Int, AlertRecord> = getDao().getAllPaged()

    fun getPagedByType(type: String): PagingSource<Int, AlertRecord> =
        getDao().getPagedByType(type)

    fun getPagedByReadStatus(isRead: Boolean): PagingSource<Int, AlertRecord> =
        getDao().getPagedByReadStatus(isRead)

    fun getPagedFiltered(type: String, isRead: Boolean): PagingSource<Int, AlertRecord> =
        getDao().getPagedFiltered(type, isRead)

    /** 未读数量 */
    fun getUnreadCount(ctx: Context): Int = getDao().getUnreadCount()

    /** 未读数量观察（Flow，实时响应 Room 变更） */
    fun observeUnreadCount(): Flow<Int> = getDao().observeUnreadCount()

    /** 总数观察（Flow） */
    fun observeTotalCount(): Flow<Int> = getDao().observeTotalCount()

    /** 添加一条警报记录 */
    fun addAlert(ctx: Context, type: String, title: String, message: String) {
        val record = AlertRecord(
            type = type,
            title = title,
            message = message,
            timestamp = System.currentTimeMillis()
        )
        getDao().insert(record)
        DebugLogger.logApi(TAG, "Alert added: type=$type title=$title")
        notifyChanged(ctx)
    }

    /** 标记单条为已读 */
    fun markRead(ctx: Context, id: Long) {
        getDao().markRead(id)
        notifyChanged(ctx)
    }

    /** 标记全部已读 */
    fun markAllRead(ctx: Context) {
        getDao().markAllRead()
        notifyChanged(ctx)
    }

    /** 删除单条记录 */
    fun remove(ctx: Context, id: Long) {
        getDao().deleteById(id)
        notifyChanged(ctx)
    }

    /** 清空全部记录 */
    fun clearAll(ctx: Context) {
        getDao().clearAll()
        notifyChanged(ctx)
    }

    private fun notifyChanged(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_DATA_CHANGED))
    }
}
