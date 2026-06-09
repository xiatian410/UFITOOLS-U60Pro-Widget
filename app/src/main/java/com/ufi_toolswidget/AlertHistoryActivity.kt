package com.ufi_toolswidget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 警报历史页面。
 *
 * - RecyclerView + Paging3 + Flow 实时刷新
 * - ItemTouchHelper 实现左右滑动（MaterialCardView 圆角无锯齿）
 * - ChipGroup 筛选（类型 + 已读状态）
 * - ViewModel 管理筛选状态
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
        private const val ACTION_BAR_PADDING = 6
    }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var filterTypeGroup: ChipGroup
    private lateinit var filterReadGroup: ChipGroup

    private lateinit var viewModel: AlertHistoryViewModel
    private lateinit var adapter: AlertItemAdapter

    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // ── 筛选选项 ──

    private val typeFilters = listOf(
        FilterOption("all", "全部"),
        FilterOption("daily_flow", "日用量"),
        FilterOption("monthly_flow", "月用量"),
        FilterOption("temp", "温度"),
        FilterOption("cpu", "CPU"),
        FilterOption("memory", "内存"),
        FilterOption("battery", "电池"),
        FilterOption("device_online", "设备")
    )

    private val readFilters = listOf(
        FilterOption("all", "全部"),
        FilterOption("unread", "未读"),
        FilterOption("read", "已读")
    )

    // ── 广播（未读红点同步） ──

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            adapter.refresh()
        }
    }

    // ═══════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_alert_history)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        viewModel = ViewModelProvider(this)[AlertHistoryViewModel::class.java]

        alertList = findViewById(R.id.alert_list)
        emptyState = findViewById(R.id.empty_state)
        actionBar = findViewById(R.id.action_bar)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)
        filterTypeGroup = findViewById(R.id.filter_type_group)
        filterReadGroup = findViewById(R.id.filter_read_group)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        registerRefreshReceiver()
        buildFilterChips()
        setupRecyclerView()
        observeData()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun registerRefreshReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver,
                IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver,
                IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED))
        }
    }

    // ═══════════════════════════════════════════
    // RecyclerView + ItemTouchHelper
    // ═══════════════════════════════════════════

    private fun setupRecyclerView() {
        adapter = AlertItemAdapter { record, _ -> showAlertActionDialog(record) }

        alertList.layoutManager = LinearLayoutManager(this)
        alertList.adapter = adapter
        alertList.setHasFixedSize(false)
        alertList.isNestedScrollingEnabled = true

        // ItemTouchHelper：右滑已读，左滑删除
        val swipeHelper = ItemTouchHelper(SwipeCallback())
        swipeHelper.attachToRecyclerView(alertList)
    }

    /**
     * 滑动回调：右滑标记已读，左滑删除。
     * onChildDraw 在 MaterialCardView 后面绘制彩色背景 + 操作标签。
     */
    private inner class SwipeCallback : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        private val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpF(14f)
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            val record = adapter.peek(pos) ?: return
            if (direction == ItemTouchHelper.RIGHT) {
                AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
            } else {
                AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
            }
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val itemView = vh.itemView
            if (dX == 0f) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                return
            }

            val bgPaint = Paint().apply { isAntiAlias = true }
            val cornerRadius = dpF(12f)

            if (dX > 0) {
                // 右滑 → 绿色已读
                bgPaint.color = Color.parseColor("#4CAF50")
                val rect = RectF(
                    itemView.left.toFloat(), itemView.top.toFloat(),
                    itemView.left + dX, itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
                val text = "已读  ✓"
                val tw = labelPaint.measureText(text)
                c.drawText(text,
                    itemView.left + (dX - tw) / 2f,
                    itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                    labelPaint)
            } else {
                // 左滑 → 红色删除
                bgPaint.color = Color.parseColor("#F44336")
                val rect = RectF(
                    itemView.right + dX, itemView.top.toFloat(),
                    itemView.right.toFloat(), itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
                val text = "✕  删除"
                val tw = labelPaint.measureText(text)
                c.drawText(text,
                    itemView.right + dX + (-dX - tw) / 2f,
                    itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                    labelPaint)
            }

            // 卡片微缩放
            val scale = 1f - (Math.abs(dX) / itemView.width * 0.05f).coerceAtMost(0.05f)
            itemView.scaleX = scale
            itemView.scaleY = scale

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
        }
    }

    // ═══════════════════════════════════════════
    // 数据观察
    // ═══════════════════════════════════════════

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.alerts.collect { pagingData ->
                        adapter.submitData(pagingData)
                    }
                }
                launch {
                    adapter.loadStateFlow.collect { loadStates ->
                        val refresh = loadStates.refresh
                        if (refresh is LoadState.NotLoading) {
                            val isEmpty = adapter.itemCount == 0
                            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                            alertList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            actionBar.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.unreadCount.collect { count ->
                        val total = adapter.itemCount
                        tvSubtitle.text = if (count > 0) {
                            "共 ${total} 条，${count} 条未读"
                        } else {
                            "共 ${total} 条"
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 筛选 Chip
    // ═══════════════════════════════════════════

    private fun buildFilterChips() {
        buildTypeFilterChips()
        buildReadFilterChips()
        buildActionBar()
    }

    private fun buildTypeFilterChips() {
        filterTypeGroup.removeAllViews()
        for ((index, opt) in typeFilters.withIndex()) {
            val chip = createFilterChip(opt.label, index == 0)
            chip.setOnClickListener {
                viewModel.filter.value = viewModel.filter.value.copy(type = opt.id)
                adapter.refresh()
            }
            filterTypeGroup.addView(chip)
        }
    }

    private fun buildReadFilterChips() {
        filterReadGroup.removeAllViews()
        for ((index, opt) in readFilters.withIndex()) {
            val chip = createFilterChip(opt.label, index == 0)
            chip.setOnClickListener {
                viewModel.filter.value = viewModel.filter.value.copy(readStatus = opt.id)
                adapter.refresh()
            }
            filterReadGroup.addView(chip)
        }
    }

    @SuppressLint("PrivateResource")
    private fun createFilterChip(text: String, isChecked: Boolean): Chip {
        val accent = ThemeColors.accent(this)
        return Chip(this).apply {
            this.text = text
            this.isChecked = isChecked
            isCheckable = true
            textSize = 12f
            chipMinHeight = dpF(28f)
            val hPad = dp(10)
            val vPad = dp(2)
            setPadding(hPad, vPad, hPad, vPad)
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, accent)
            ))
            chipBackgroundColor = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.TRANSPARENT)
            )
            chipStrokeColor = android.content.res.ColorStateList.valueOf(accent and 0x60FFFFFF)
            chipStrokeWidth = dpF(1f)
        }
    }

    // ═══════════════════════════════════════════
    // Action bar
    // ═══════════════════════════════════════════

    private fun buildActionBar() {
        actionBar.removeAllViews()
        val accent = ThemeColors.accent(this)
        val subColor = ThemeColors.textSecondary(this)

        actionBar.addView(buildChipButton("全部已读", R.drawable.ic_check, accent) {
            AlertHistoryManager.markAllRead(this)
        })
        actionBar.addView(buildChipButton("清空", null, subColor) {
            showClearConfirmDialog()
        })
    }

    private fun buildChipButton(text: String, iconRes: Int?, color: Int, onClick: () -> Unit): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), color and 0x60FFFFFF)
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(ACTION_BAR_PADDING)
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }
        if (iconRes != null) {
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(color)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), 0)
            })
        }
        row.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
        })
        AnimationUtil.applyScaleClickAnimation(row) { onClick() }
        return row
    }

    // ═══════════════════════════════════════════
    // 弹窗
    // ═══════════════════════════════════════════

    private fun showAlertActionDialog(record: AlertRecord) {
        val ctx = this
        val timeStr = fullTimeFormat.format(Date(record.timestamp))

        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = record.title,
            iconRes = typeToIconRes(record.type),
            onFill = { content ->
                content.addView(TextView(ctx).apply {
                    text = record.message
                    textSize = 13f
                    setTextColor(ThemeColors.textPrimary(ctx))
                    setLineSpacing(0f, 1.3f)
                })
                content.addView(TextView(ctx).apply {
                    text = "触发时间: $timeStr"
                    textSize = 12f
                    setTextColor(ThemeColors.textSecondary(ctx))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(8)
                    layoutParams = lp
                })
                if (!record.isRead) {
                    content.addView(CommonSettingsItemHelper.createDivider(ctx).apply {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp(6)
                        lp.bottomMargin = dp(6)
                        layoutParams = lp
                    })
                    content.addView(TextView(ctx).apply {
                        text = "● 未读 — 右滑卡片可快速标记已读"
                        textSize = 12f
                        setTextColor(ThemeColors.accent(ctx))
                    })
                } else {
                    content.addView(TextView(ctx).apply {
                        text = "左滑卡片可快速删除"
                        textSize = 12f
                        setTextColor(ThemeColors.textSecondary(ctx))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp(6)
                        layoutParams = lp
                    })
                }
            },
            primaryBtnText = if (record.isRead) "关闭" else "标记已读",
            onPrimaryClick = { dialog ->
                if (!record.isRead) {
                    AlertHistoryManager.markRead(ctx, record.id)
                }
                dialog.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { dialog ->
                AlertHistoryManager.remove(ctx, record.id)
                dialog.dismiss()
            }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = "清空警报历史",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(TextView(ctx).apply {
                    text = "确定要清空所有警报记录吗？此操作不可撤销。"
                    textSize = 14f
                    setTextColor(ThemeColors.textSecondary(ctx))
                })
            },
            primaryBtnText = "清空",
            onPrimaryClick = { dialog ->
                AlertHistoryManager.clearAll(ctx)
                dialog.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { dialog -> dialog.dismiss() }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private fun typeToIconRes(type: String): Int = when (type) {
        "daily_flow" -> R.drawable.ic_rocket
        "monthly_flow" -> R.drawable.ic_rocket
        "temp" -> R.drawable.ic_temp
        "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip
        "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router
        else -> R.drawable.ic_notification
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float =
        value * resources.displayMetrics.density
}

data class FilterOption(val id: String, val label: String)
