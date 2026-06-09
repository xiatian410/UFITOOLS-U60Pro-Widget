package com.ufi_toolswidget.util

import android.content.Context
import android.content.Intent
import androidx.paging.PagingSource
import com.ufi_toolswidget.db.AlertDao
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.locks.ReentrantLock

/**
 * 警报历史管理器（Room 实现）。
 *
 * 提供与旧 SP 版本兼容的 API，内部使用 Room 数据库存储。
 * 所有写操作通过 [writeLock] 串行化，防止清空与新增同时执行导致
 * PagingSource 失效崩溃。
 * 修改操作完成后发送 [ACTION_DATA_CHANGED] 广播，
 * 供外部组件（如 MainActivity 未读红点）响应数据变更。
 */
object AlertHistoryManager {

    private const val TAG = "AlertHistoryManager"
    const val ACTION_DATA_CHANGED = "com.ufi_toolswidget.ALERT_HISTORY_CHANGED"

    /** 串行化所有写操作，防止清空+新增并发导致 PagingSource 崩溃 */
    private val writeLock = ReentrantLock()

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

    /** 添加一条警报记录（加锁，防止与 clearAll 并发） */
    fun addAlert(ctx: Context, type: String, title: String, message: String) {
        writeLock.lock()
        try {
            val record = AlertRecord(
                type = type,
                title = title,
                message = message,
                timestamp = System.currentTimeMillis()
            )
            getDao().insert(record)
            DebugLogger.logApi(TAG, "Alert added: type=$type title=$title")
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 标记单条为已读（加锁） */
    fun markRead(ctx: Context, id: Long) {
        writeLock.lock()
        try {
            getDao().markRead(id)
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 标记全部已读（加锁） */
    fun markAllRead(ctx: Context) {
        writeLock.lock()
        try {
            getDao().markAllRead()
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 删除单条记录（加锁） */
    fun remove(ctx: Context, id: Long) {
        writeLock.lock()
        try {
            getDao().deleteById(id)
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 清空全部记录（加锁，防止与 addAlert 并发导致 PagingSource 崩溃） */
    fun clearAll(ctx: Context) {
        writeLock.lock()
        try {
            getDao().clearAll()
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    private fun notifyChanged(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_DATA_CHANGED))
    }
}
