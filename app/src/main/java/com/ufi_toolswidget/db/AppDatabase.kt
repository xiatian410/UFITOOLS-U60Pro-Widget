package com.ufi_toolswidget.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import org.json.JSONArray

/**
 * Room 数据库。
 *
 * 首次创建时自动从旧 SharedPreferences 迁移历史警报数据。
 */
@Database(entities = [AlertRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ufitools.db"
                )
                    .allowMainThreadQueries()
                    .addCallback(MigrationCallback(context.applicationContext))
                    .build()
                    .also { instance = it }
            }
        }
    }

    /**
     * 数据库首次创建回调：从旧 SharedPreferences 迁移历史警报。
     */
    private class MigrationCallback(private val appContext: Context) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            migrateFromSp()
        }

        private fun migrateFromSp() {
            try {
                val sp = SPUtil.getSp(appContext)
                val json = sp.getString("alert_history_json", "") ?: return
                if (json.isEmpty()) return

                val arr = JSONArray(json)
                if (arr.length() == 0) return

                val database = getInstance(appContext)
                val dao = database.alertDao()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    dao.insert(
                        AlertRecord(
                            type = obj.optString("type", ""),
                            title = obj.optString("title", ""),
                            message = obj.optString("message", ""),
                            timestamp = obj.optLong("timestamp"),
                            isRead = obj.optBoolean("isRead", false)
                        )
                    )
                }

                sp.edit().putBoolean("alert_history_migrated_to_room", true).apply()
                DebugLogger.logApi("AppDatabase", "Migrated ${arr.length()} alerts from SP to Room")
            } catch (e: Exception) {
                DebugLogger.e("AppDatabase", "SP to Room migration failed: ${e.message}")
            }
        }
    }
}
