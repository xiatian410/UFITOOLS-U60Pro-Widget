package com.ufi_toolswidget

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 警报历史页面。
 *
 * 展示所有历史警报记录，支持：
 * - 单条已读 / 全部已读
 * - 单条删除 / 一键清空
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var actionBar: View
    private lateinit var tvSubtitle: TextView

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_alert_history)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        alertList = findViewById(R.id.alert_list)
        emptyState = findViewById(R.id.empty_state)
        actionBar = findViewById(R.id.action_bar)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        // 全部已读
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_mark_all_read)) {
            AlertHistoryManager.markAllRead(this)
            refreshList()
        }

        // 清空
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_clear_all)) {
            showClearConfirmDialog()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        alertList.removeAllViews()
        val records = AlertHistoryManager.getAll(this)

        if (records.isEmpty()) {
            alertList.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            actionBar.visibility = View.GONE
            tvSubtitle.text = "暂无警报记录"
            return
        }

        alertList.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        actionBar.visibility = View.VISIBLE

        val unreadCount = records.count { !it.isRead }
        tvSubtitle.text = if (unreadCount > 0) {
            "共 ${records.size} 条，${unreadCount} 条未读"
        } else {
            "共 ${records.size} 条，全部已读"
        }

        for (record in records) {
            alertList.addView(buildAlertItem(record))
        }
    }

    // ── 构建单条警报视图 ──

    private fun buildAlertItem(record: AlertRecord): View {
        val ctx = this
        val textColor = ThemeColors.textPrimary(ctx)
        val subtextColor = ThemeColors.textSecondary(ctx)
        val accentColor = ThemeColors.accent(ctx)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (record.isRead) Color.TRANSPARENT else (accentColor and 0x0DFFFFFF))
                setStroke(dp(1), if (record.isRead) (subtextColor and 0x20FFFFFF) else (accentColor and 0x30FFFFFF))
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        // 第一行：类型标签 + 时间
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val typeLabel = TextView(ctx).apply {
            text = typeToLabel(record.type)
            textSize = 11f
            setTextColor(Color.WHITE)
            val typeBg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(typeToColor(record.type, accentColor))
            }
            background = typeBg
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        topRow.addView(typeLabel)

        // 未读圆点
        if (!record.isRead) {
            val dot = View(ctx).apply {
                val dotSize = dp(6)
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentColor)
                }
                background = dotBg
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = dp(6)
                }
            }
            topRow.addView(dot)
        }

        topRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        val timeText = TextView(ctx).apply {
            text = timeFormat.format(Date(record.timestamp))
            textSize = 11f
            setTextColor(subtextColor)
        }
        topRow.addView(timeText)

        container.addView(topRow)

        // 第二行：标题
        val titleView = TextView(ctx).apply {
            text = record.title
            textSize = 14f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        container.addView(titleView)

        // 第三行：内容
        val msgView = TextView(ctx).apply {
            text = record.message
            textSize = 12f
            setTextColor(subtextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        container.addView(msgView)

        // 第四行：操作按钮
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        if (!record.isRead) {
            val readBtn = TextView(ctx).apply {
                text = "已读"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                val btnBg = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), accentColor)
                }
                background = btnBg
                isClickable = true
                isFocusable = true
            }
            AnimationUtil.applyScaleClickAnimation(readBtn) {
                AlertHistoryManager.markRead(ctx, record.id)
                refreshList()
            }
            actionRow.addView(readBtn)
        }

        val deleteBtn = TextView(ctx).apply {
            text = "删除"
            textSize = 12f
            setTextColor(subtextColor)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val btnBg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), subtextColor and 0x40FFFFFF)
            }
            background = btnBg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6)
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }
        AnimationUtil.applyScaleClickAnimation(deleteBtn) {
            AlertHistoryManager.remove(ctx, record.id)
            refreshList()
        }
        actionRow.addView(deleteBtn)

        container.addView(actionRow)

        // 点击卡片整体标记已读
        if (!record.isRead) {
            container.isClickable = true
            container.isFocusable = true
            container.setOnClickListener {
                AlertHistoryManager.markRead(ctx, record.id)
                refreshList()
            }
        }

        return container
    }

    // ── 清空确认弹窗 ──

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = "清空警报历史",
            iconRes = R.drawable.ic_notification,
            onFill = { container ->
                val msg = TextView(ctx).apply {
                    text = "确定要清空所有警报记录吗？此操作不可撤销。"
                    textSize = 14f
                    setTextColor(ThemeColors.textSecondary(ctx))
                }
                container.addView(msg)
            },
            primaryBtnText = "清空",
            onPrimaryClick = { dialog ->
                AlertHistoryManager.clearAll(ctx)
                dialog.dismiss()
                refreshList()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { dialog -> dialog.dismiss() }
        )
    }

    // ── 辅助方法 ──

    private fun typeToLabel(type: String): String = when (type) {
        "daily_flow" -> "今日流量"
        "monthly_flow" -> "本月流量"
        "temp" -> "温度"
        "cpu" -> "CPU"
        "memory" -> "内存"
        "battery" -> "电量"
        "device_online" -> "设备状态"
        else -> type
    }

    private fun typeToColor(type: String, accent: Int): Int = when (type) {
        "daily_flow", "monthly_flow" -> Color.parseColor("#2196F3")
        "temp" -> Color.parseColor("#F44336")
        "cpu" -> Color.parseColor("#FF9800")
        "memory" -> Color.parseColor("#9C27B0")
        "battery" -> Color.parseColor("#4CAF50")
        "device_online" -> Color.parseColor("#00BCD4")
        else -> accent
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
