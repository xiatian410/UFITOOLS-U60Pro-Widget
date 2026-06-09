package com.ufi_toolswidget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
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
import com.google.android.material.button.MaterialButton
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
 * - ItemTouchHelper 智能滑动：onChildDraw 追踪峰值位移，clearView 判定操作
 * - 独立操作按钮（全部已读 + 清空）+ 可折叠筛选面板（双列类型 + 单行状态）
 * - ViewModel 管理筛选状态，subtitleInfo combine 流实时更新副标题
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var filterPanel: LinearLayout
    private lateinit var filterTypeGroup: GridLayout
    private lateinit var filterReadGroup: LinearLayout
    private lateinit var tvSubtitle: TextView

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

    private var selectedTypeIndex = 0
    private var selectedReadIndex = 0
    private val typeButtons = mutableListOf<MaterialButton>()
    private val readButtons = mutableListOf<MaterialButton>()
    private var isFilterExpanded = false

    // ── 速度追踪 ──
    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    // ── 广播 ──

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                adapter.refresh()
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Adapter refresh failed: ${e.message}")
            }
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
        btnFilterToggle = findViewById(R.id.btn_filter_toggle)
        filterPanel = findViewById(R.id.filter_panel)
        filterTypeGroup = findViewById(R.id.filter_type_group)
        filterReadGroup = findViewById(R.id.filter_read_group)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        registerRefreshReceiver()
        setupActionBar()
        setupFilterToggle()
        buildFilterPanel()
        setupRecyclerView()
        observeData()
    }

    override fun onDestroy() {
        super.onDestroy()
        velocityTracker?.recycle()
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
    // 操作按钮 — 文字颜色在 applyScaleClickAnimation 之后强制设置
    // ═══════════════════════════════════════════

    private fun setupActionBar() {
        val accent = ThemeColors.accent(this)
        val themeText = ThemeColors.textPrimary(this)
        val dangerColor = Color.parseColor("#F44336")

        // 全部已读 — accent 背景 + 主题文字色（深浅色自适应）
        val btnMarkAllRead = findViewById<MaterialButton>(R.id.btn_mark_all_read)
        btnMarkAllRead.backgroundTintList = ColorStateList.valueOf(accent)
        btnMarkAllRead.strokeWidth = 0
        btnMarkAllRead.strokeColor = ColorStateList.valueOf(accent)
        btnMarkAllRead.iconTint = ColorStateList.valueOf(themeText)
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) {
            AlertHistoryManager.markAllRead(this)
        }
        // 在 applyScaleClickAnimation 之后强制覆盖文字颜色
        btnMarkAllRead.setTextColor(themeText)

        // 清空记录 — 红色背景 + 白色文字
        val btnClearAll = findViewById<MaterialButton>(R.id.btn_clear_all)
        btnClearAll.backgroundTintList = ColorStateList.valueOf(dangerColor)
        btnClearAll.strokeWidth = 0
        btnClearAll.strokeColor = ColorStateList.valueOf(dangerColor)
        btnClearAll.iconTint = ColorStateList.valueOf(Color.WHITE)
        AnimationUtil.applyScaleClickAnimation(btnClearAll) {
            showClearConfirmDialog()
        }
        // 在 applyScaleClickAnimation 之后强制覆盖文字颜色
        btnClearAll.setTextColor(Color.WHITE)
    }

    // ═══════════════════════════════════════════
    // 筛选面板（可折叠，默认收起，带渐变滑入/滑出动画）
    // ═══════════════════════════════════════════

    private fun setupFilterToggle() {
        val textSecondary = ThemeColors.textSecondary(this)
        btnFilterToggle.setTextColor(textSecondary)
        btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)
        btnFilterToggle.setOnClickListener {
            isFilterExpanded = !isFilterExpanded
            updateFilterToggleState()
        }
    }

    private fun updateFilterToggleState() {
        val accent = ThemeColors.accent(this)
        val textSecondary = ThemeColors.textSecondary(this)

        if (isFilterExpanded) {
            filterPanel.visibility = View.VISIBLE
            filterPanel.alpha = 0f
            filterPanel.translationY = -dp(10).toFloat()
            filterPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            btnFilterToggle.text = "收起筛选"
            btnFilterToggle.setTextColor(accent)
            btnFilterToggle.iconTint = ColorStateList.valueOf(accent)
        } else {
            filterPanel.animate()
                .alpha(0f)
                .translationY(-dp(10).toFloat())
                .setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    filterPanel.visibility = View.GONE
                    filterPanel.alpha = 1f
                    filterPanel.translationY = 0f
                }
                .start()
            btnFilterToggle.text = "筛选"
            btnFilterToggle.setTextColor(textSecondary)
            btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)
        }
    }

    private fun buildFilterPanel() {
        // 类型筛选 — GridLayout 双列
        filterTypeGroup.removeAllViews()
        typeButtons.clear()
        for ((index, opt) in typeFilters.withIndex()) {
            val btn = createFilterChip(opt.label, false, isGrid = true)
            btn.setOnClickListener {
                val clickedIndex = typeButtons.indexOf(btn)
                if (clickedIndex != -1 && clickedIndex != selectedTypeIndex) {
                    selectedTypeIndex = clickedIndex
                    updateFilterButtonStyles()
                    viewModel.filter.value = viewModel.filter.value.copy(type = typeFilters[clickedIndex].id)
                    adapter.refresh()
                }
            }
            typeButtons.add(btn)
            filterTypeGroup.addView(btn)
        }

        // 状态筛选 — LinearLayout 单行
        filterReadGroup.removeAllViews()
        readButtons.clear()
        for ((index, opt) in readFilters.withIndex()) {
            val btn = createFilterChip(opt.label, false, isGrid = false)
            btn.setOnClickListener {
                val clickedIndex = readButtons.indexOf(btn)
                if (clickedIndex != -1 && clickedIndex != selectedReadIndex) {
                    selectedReadIndex = clickedIndex
                    updateFilterButtonStyles()
                    viewModel.filter.value = viewModel.filter.value.copy(readStatus = readFilters[clickedIndex].id)
                    adapter.refresh()
                }
            }
            readButtons.add(btn)
            filterReadGroup.addView(btn)
        }

        // 初始选中态
        updateFilterButtonStyles()
    }

    /**
     * 创建筛选标签。isGrid=true 时用 GridLayout.LayoutParams（等宽两列），
     * isGrid=false 时用 LinearLayout.LayoutParams（自适应宽度单行）。
     */
    @SuppressLint("PrivateResource")
    private fun createFilterChip(text: String, selected: Boolean, isGrid: Boolean): MaterialButton {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        return MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            if (selected) {
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(Color.WHITE)
            } else {
                backgroundTintList = ColorStateList.valueOf(cardBg)
                setTextColor(textPrimary)
            }
            strokeWidth = 0
            cornerRadius = dp(8)
            rippleColor = ColorStateList.valueOf(Color.argb(32,
                Color.red(accent), Color.green(accent), Color.blue(accent)))
            this.text = text
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)

            layoutParams = if (isGrid) {
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(36)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                ).apply {
                    marginEnd = dp(6)
                }
            }
        }
    }

    private fun updateFilterButtonStyles() {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        typeButtons.forEachIndexed { i, btn ->
            val sel = i == selectedTypeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (sel) accent else cardBg)
            btn.setTextColor(if (sel) Color.WHITE else textPrimary)
        }
        readButtons.forEachIndexed { i, btn ->
            val sel = i == selectedReadIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (sel) accent else cardBg)
            btn.setTextColor(if (sel) Color.WHITE else textPrimary)
        }
    }

    // ═══════════════════════════════════════════
    // RecyclerView + ItemTouchHelper
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        adapter = AlertItemAdapter { record, _ -> showAlertActionDialog(record) }

        alertList.layoutManager = LinearLayoutManager(this)
        alertList.adapter = adapter
        alertList.setHasFixedSize(false)
        alertList.isNestedScrollingEnabled = true

        // 速度追踪（用于 clearView 中的多条件判定）
        alertList.setOnTouchListener { _, event ->
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.let { vt ->
                    vt.computeCurrentVelocity(1000)
                    lastSwipeVelocityDpPerSec = Math.abs(vt.xVelocity) / resources.displayMetrics.density
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            false
        }

        val swipeHelper = ItemTouchHelper(SwipeCallback())
        swipeHelper.attachToRecyclerView(alertList)
    }

    /**
     * 滑动回调。
     *
     * 核心设计：
     * 1. getSwipeThreshold() = Float.MAX_VALUE → onSwiped 永远不触发
     * 2. onChildDraw 中追踪峰值位移（peakTranslationX），因为 clearView
     *    被调用时 translationX 可能已被 ItemTouchHelper 重置为 0
     * 3. clearView 使用 peakTranslationX 判定操作
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

        /** 本次滑动过程中记录的最大位移（含方向） */
        private var peakTranslationX = 0f

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            // 永远不会被调用（getSwipeThreshold = Float.MAX_VALUE）。
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float =
            Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float): Float =
            Float.MAX_VALUE

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val itemView = vh.itemView
            if (dX == 0f && !isCurrentlyActive) {
                // 滑动结束/重置
                peakTranslationX = 0f
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                return
            }

            // 追踪峰值位移
            if (Math.abs(dX) > Math.abs(peakTranslationX)) {
                peakTranslationX = dX
            }

            val bgPaint = Paint().apply { isAntiAlias = true }
            val cornerRadius = dpF(12f)
            val absDx = Math.abs(dX)

            if (dX > 0) {
                // 右滑 → 绿色已读
                val alpha = (absDx / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 76, 175, 80)
                c.drawRoundRect(
                    RectF(itemView.left.toFloat(), itemView.top.toFloat(),
                        itemView.left + dX, itemView.bottom.toFloat()),
                    cornerRadius, cornerRadius, bgPaint)

                if (absDx > dpF(30f)) {
                    val text = "已读  ✓"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.left + (dX - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            } else {
                // 左滑 → 红色删除
                val alpha = (absDx / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 244, 67, 54)
                c.drawRoundRect(
                    RectF(itemView.right + dX, itemView.top.toFloat(),
                        itemView.right.toFloat(), itemView.bottom.toFloat()),
                    cornerRadius, cornerRadius, bgPaint)

                if (absDx > dpF(30f)) {
                    val text = "✕  删除"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.right + dX + (absDx - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            }

            // 卡片微缩放
            val scale = 1f - (absDx / itemView.width * 0.03f).coerceAtMost(0.03f)
            itemView.scaleX = scale
            itemView.scaleY = scale

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        /**
         * 手指松开后由 ItemTouchHelper 调用。
         * 使用 peakTranslationX（而非 itemView.translationX）判定操作，
         * 因为 clearView 被调用时 translationX 可能已被重置为 0。
         */
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            val savedPeakX = peakTranslationX
            val itemWidth = vh.itemView.width.toFloat()
            val pos = vh.bindingAdapterPosition
            val record = adapter.peek(pos)

            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            peakTranslationX = 0f

            if (record == null || pos == RecyclerView.NO_POSITION) return

            val absDx = Math.abs(savedPeakX)
            if (absDx < dpF(10f)) return

            if (shouldExecuteSwipe(absDx, itemWidth)) {
                if (savedPeakX > 0) {
                    AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                } else if (savedPeakX < 0) {
                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                }
            }
        }

        private fun shouldExecuteSwipe(absDx: Float, itemWidth: Float): Boolean {
            val distanceRatio = absDx / itemWidth
            val velocity = lastSwipeVelocityDpPerSec
            return when {
                distanceRatio > 0.4f -> true
                velocity > 800f && distanceRatio > 0.15f -> true
                distanceRatio > 0.25f && velocity > 500f -> true
                else -> false
            }
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
                            // 只在列表真正为空时隐藏按钮，避免筛选切换时闪烁
                            if (isEmpty) {
                                actionBar.visibility = View.GONE
                                btnFilterToggle.visibility = View.GONE
                            } else {
                                actionBar.visibility = View.VISIBLE
                                btnFilterToggle.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                launch {
                    viewModel.subtitleInfo.collect { (total, unread) ->
                        tvSubtitle.text = if (unread > 0) {
                            "共 ${total} 条，${unread} 条未读"
                        } else {
                            "共 ${total} 条"
                        }
                    }
                }
            }
        }
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
            iconRes = R.drawable.ic_trash,
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
