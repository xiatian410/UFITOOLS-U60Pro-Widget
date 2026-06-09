package com.ufi_toolswidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
 * 自定义列表项 UI（FrameLayout 包裹卡片），支持：
 * - 未读条目左侧主题色条 + 标题加粗
 * - 左右滑动：右滑标记已读（绿色 + "已读 ✓"），左滑删除（红色 + "删除 ✕"）
 * - 滑动过程中：弹性阻尼 + 模糊效果 + 缩放 + 背景混色 + 操作标签渐显
 * - 超过阈值：滑出 + 淡出 + 高度收缩动画
 * - 未达阈值：OvershootInterpolator 弹性回弹
 * - BroadcastReceiver 实时刷新
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
        private const val SWIPE_THRESHOLD = 120   // dp
        private const val ACTION_BAR_PADDING = 6   // dp
        private const val MAX_BLUR = 14f           // px — 最大模糊半径
    }

    private lateinit var alertList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var tvSubtitle: TextView

    private val shortTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // ═══════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════

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

        registerRefreshReceiver()
        buildActionBar()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    // ── 实时刷新广播 ──

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshList()
        }
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
    // Action bar
    // ═══════════════════════════════════════════

    private fun buildActionBar() {
        actionBar.removeAllViews()
        val accent = ThemeColors.accent(this)
        val subColor = ThemeColors.textSecondary(this)

        actionBar.addView(buildChipButton("全部已读", R.drawable.ic_check, accent) {
            AlertHistoryManager.markAllRead(this)
            refreshList()
        })
        actionBar.addView(buildChipButton("清空", null, subColor) {
            showClearConfirmDialog()
        })
    }

    /**
     * 构建 chip 风格文字按钮：圆角描边背景 + 图标（可选）。
     */
    private fun buildChipButton(
        text: String,
        iconRes: Int?,
        color: Int,
        onClick: () -> Unit
    ): View {
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
    // 列表刷新
    // ═══════════════════════════════════════════

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
            "共 ${records.size} 条"
        }

        for (record in records) {
            alertList.addView(buildAlertItem(record))
        }
    }

    // ═══════════════════════════════════════════
    // 列表项 UI（FrameLayout = 背景标签层 + 卡片层）
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAlertItem(record: AlertRecord): View {
        val ctx = this
        val accent = ThemeColors.accent(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 外层 FrameLayout：承载背景标签 + 可滑动卡片 ──
        val container = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            clipChildren = false
            clipToPadding = false
        }

        // ── 左侧背景标签：右滑已读（绿色） ──
        val leftLabel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dpF(12f)
                setColor(Color.parseColor("#4CAF50"))
            }
            background = bg
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START }
            alpha = 0f

            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), 0)
            })
            addView(TextView(ctx).apply {
                text = "已读"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
        }
        container.addView(leftLabel)

        // ── 右侧背景标签：左滑删除（红色） ──
        val rightLabel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            val bg = GradientDrawable().apply {
                cornerRadius = dpF(12f)
                setColor(Color.parseColor("#F44336"))
            }
            background = bg
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.END }
            alpha = 0f

            addView(TextView(ctx).apply {
                text = "删除"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), 0)
            })
            addView(TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
        }
        container.addView(rightLabel)

        // ── 卡片本体 ──
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dpF(12f)
                setColor(cardBg)
            }
            background = bg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        // 左侧未读色条
        if (!record.isRead) {
            val bar = View(ctx).apply {
                val barBg = GradientDrawable().apply {
                    cornerRadius = dpF(2f)
                    setColor(accent)
                }
                background = barBg
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(36))
            }
            card.addView(bar)
            card.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), 0)
            })
        }

        // 图标
        val iconView = ImageView(ctx).apply {
            setImageResource(typeToIconRes(record.type))
            val iconColor = if (!record.isRead) accent else textSecondary
            setColorFilter(iconColor)
            alpha = if (record.isRead) 0.5f else 1f
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        card.addView(iconView)

        // 文本区
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(12)
            layoutParams = lp
        }

        textArea.addView(TextView(ctx).apply {
            text = record.title
            textSize = 14f
            setTextColor(textPrimary)
            if (!record.isRead) setTypeface(null, Typeface.BOLD)
        })

        textArea.addView(TextView(ctx).apply {
            text = record.message
            textSize = 12f
            setTextColor(if (!record.isRead) textPrimary else textSecondary)
            maxLines = 2
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(3)
            layoutParams = lp
        })

        textArea.addView(TextView(ctx).apply {
            text = shortTimeFormat.format(Date(record.timestamp))
            textSize = 11f
            setTextColor(textSecondary)
            alpha = 0.6f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        })

        card.addView(textArea)
        container.addView(card)

        // ── 点击弹出详情 ──
        card.setOnClickListener { showAlertActionDialog(record) }

        // ── 滑动交互 ──
        attachSwipeHandler(card, leftLabel, rightLabel, record)

        return container
    }

    // ═══════════════════════════════════════════
    // 滑动处理（阻尼 + 模糊 + 缩放 + 标签渐显 + 弹性回弹/滑出）
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeHandler(card: View, leftLabel: View, rightLabel: View, record: AlertRecord) {
        val threshold = dp(SWIPE_THRESHOLD).toFloat()
        var startX = 0f
        var startY = 0f
        var isSwiping = false
        var lastDx = 0f
        var thresholdCrossed = false

        card.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwiping = false
                    lastDx = 0f
                    thresholdCrossed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY

                    if (!isSwiping && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        isSwiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isSwiping) {
                        val absDx = Math.abs(dx)

                        // ① 弹性阻尼位移
                        v.translationX = dx * 0.6f
                        lastDx = dx

                        // ② 缩放：滑动越远卡片越小（微妙深度感）
                        val scaleRatio = (absDx / threshold).coerceAtMost(1f)
                        v.scaleX = 1f - scaleRatio * 0.05f
                        v.scaleY = 1f - scaleRatio * 0.05f

                        // ③ 模糊效果（API 31+）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val blur = (absDx / threshold * MAX_BLUR).coerceAtMost(MAX_BLUR)
                            v.setRenderEffect(
                                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                            )
                        }

                        // ④ 背景混色 + 标签渐显
                        val bg = v.background as? GradientDrawable
                        val cardBgColor = ThemeColors.cardBg(this)
                        val ratio = (absDx / threshold).coerceAtMost(1f)

                        if (dx > 0) {
                            // 右滑 → 绿色 + 显示"已读"标签
                            bg?.setColor(blendColor(cardBgColor, Color.parseColor("#4CAF50"), ratio * 0.35f))
                            leftLabel.alpha = ratio * 0.95f
                            rightLabel.alpha = 0f
                        } else {
                            // 左滑 → 红色 + 显示"删除"标签
                            bg?.setColor(blendColor(cardBgColor, Color.parseColor("#F44336"), ratio * 0.35f))
                            rightLabel.alpha = ratio * 0.95f
                            leftLabel.alpha = 0f
                        }

                        // ⑤ 越过阈值时触觉反馈
                        if (absDx >= threshold && !thresholdCrossed) {
                            thresholdCrossed = true
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else if (absDx < threshold) {
                            thresholdCrossed = false
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        val absDx = Math.abs(lastDx)

                        if (absDx >= threshold) {
                            // ── 超过阈值：滑出 + 淡出 + 高度收缩 ──
                            val slideOut = if (lastDx > 0) v.width.toFloat() * 1.5f else -v.width.toFloat() * 1.5f
                            v.animate()
                                .translationX(slideOut)
                                .alpha(0f)
                                .scaleX(0.9f)
                                .scaleY(0.9f)
                                .setDuration(300)
                                .setInterpolator(DecelerateInterpolator(1.5f))
                                .setListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        // 清除渲染效果
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            v.setRenderEffect(null)
                                        }
                                        v.scaleX = 1f
                                        v.scaleY = 1f
                                        // 高度收缩动画
                                        val container = v.parent as? View
                                        container?.animate()
                                            ?.translationY(-container.height.toFloat())
                                            ?.setDuration(200)
                                            ?.setInterpolator(DecelerateInterpolator())
                                            ?.withEndAction {
                                                if (lastDx > 0) {
                                                    AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                                                } else {
                                                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                                                }
                                                refreshList()
                                            }
                                            ?.start()
                                            ?: run {
                                                // 无容器时直接刷新
                                                if (lastDx > 0) {
                                                    AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                                                } else {
                                                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                                                }
                                                refreshList()
                                            }
                                    }
                                })
                                .start()
                        } else {
                            // ── 未超过阈值：弹性回弹 ──
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                v.setRenderEffect(null)
                            }
                            val bg = v.background as? GradientDrawable
                            bg?.setColor(ThemeColors.cardBg(this))
                            leftLabel.animate().alpha(0f).setDuration(200).start()
                            rightLabel.animate().alpha(0f).setDuration(200).start()
                            v.animate()
                                .translationX(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(350)
                                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                                .setListener(null)
                                .start()
                        }
                    } else {
                        return@setOnTouchListener false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun blendColor(base: Int, overlay: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()
        val a = Color.alpha(base)
        return Color.argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
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
                    refreshList()
                }
                dialog.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { dialog ->
                AlertHistoryManager.remove(ctx, record.id)
                dialog.dismiss()
                refreshList()
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
                refreshList()
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun dpF(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
