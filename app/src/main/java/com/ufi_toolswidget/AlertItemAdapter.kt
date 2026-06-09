package com.ufi_toolswidget

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.ThemeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 警报列表 Adapter（传统分页）。
 *
 * 卡片设计：左侧常驻色条 + 图标 + 标题/消息/时间文本区。
 * 未读：色条=accent，标题粗体，图标高亮。
 * 已读：色条=secondary(半透)，标题常规，图标半透。
 */
class AlertItemAdapter(
    private val onItemClick: (AlertRecord, View) -> Unit
) : ListAdapter<AlertRecord, AlertItemAdapter.AlertViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AlertRecord>() {
            override fun areItemsTheSame(oldItem: AlertRecord, newItem: AlertRecord) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: AlertRecord, newItem: AlertRecord) =
                oldItem == newItem
        }
    }

    inner class AlertViewHolder(val card: com.google.android.material.card.MaterialCardView) :
        RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = 14 * d
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(cardBg)
            val lp = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (10 * d).toInt()
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }

        // 外层容器
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        }

        // 色条（常驻，未读=accent，已读=secondary半透）
        val bar = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((3 * d).toInt(), (40 * d).toInt())
            tag = "bar"
        }
        content.addView(bar)

        // 色条间距
        val barGap = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((12 * d).toInt(), 0)
            tag = "barGap"
        }
        content.addView(barGap)

        // 图标
        val icon = ImageView(ctx).apply {
            val size = (24 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            tag = "icon"
        }
        content.addView(icon)

        // 文本区
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = (12 * d).toInt()
            layoutParams = lp
        }

        // 标题行（标题 + 时间并排）
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleRow.addView(TextView(ctx).apply {
            textSize = 14.5f
            setTextColor(textPrimary)
            tag = "title"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        titleRow.addView(TextView(ctx).apply {
            textSize = 10.5f
            setTextColor(textSecondary)
            alpha = 0.55f
            tag = "time"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = (6 * d).toInt()
            layoutParams = lp
            setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
        })

        textArea.addView(titleRow)

        // 消息（第二行）
        textArea.addView(TextView(ctx).apply {
            textSize = 12.5f
            maxLines = 2
            setTextColor(textSecondary)
            tag = "message"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * d).toInt()
            layoutParams = lp
            setLineSpacing(0f, 1.25f)
        })

        content.addView(textArea)
        card.addView(content)

        return AlertViewHolder(card)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val record = getItem(position) ?: return
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val accent = ThemeColors.accent(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)

        val content = holder.card.getChildAt(0) as LinearLayout

        // 色条 — 常驻显示
        val bar = content.findViewWithTag<View>("bar")
        val barGap = content.findViewWithTag<View>("barGap")
        if (!record.isRead) {
            bar.visibility = View.VISIBLE
            bar.background = GradientDrawable().apply {
                cornerRadius = 2 * d
                setColor(accent)
            }
            barGap.visibility = View.VISIBLE
        } else {
            bar.visibility = View.VISIBLE
            bar.background = GradientDrawable().apply {
                cornerRadius = 2 * d
                setColor((textSecondary and 0x00FFFFFF) or 0x30000000)
            }
            barGap.visibility = View.VISIBLE
        }

        // 图标
        val icon = content.findViewWithTag<ImageView>("icon")
        icon.setImageResource(typeToIconRes(record.type))
        val iconColor = if (!record.isRead) accent else textSecondary
        icon.setColorFilter(iconColor)
        icon.alpha = if (record.isRead) 0.45f else 1f

        // 标题
        val title = content.findViewWithTag<TextView>("title")
        title.text = record.title
        title.setTextColor(textPrimary)
        title.setTypeface(null, if (!record.isRead) Typeface.BOLD else Typeface.NORMAL)
        title.alpha = if (record.isRead) 0.75f else 1f

        // 时间（标题行右侧）
        val time = content.findViewWithTag<TextView>("time")
        time.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(record.timestamp))

        // 消息
        val message = content.findViewWithTag<TextView>("message")
        message.text = record.message
        message.setTextColor(if (!record.isRead) textPrimary else textSecondary)
        message.alpha = if (record.isRead) 0.65f else 0.85f

        holder.card.setOnClickListener { onItemClick(record, holder.card) }
    }

    private fun typeToIconRes(type: String): Int = when (type) {
        "daily_flow", "monthly_flow" -> R.drawable.ic_rocket
        "temp" -> R.drawable.ic_temp
        "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip
        "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router
        else -> R.drawable.ic_notification
    }
}
