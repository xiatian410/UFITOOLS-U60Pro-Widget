package com.ufi_toolswidget.util

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R

/**
 * 翻页栏公共组件 — 固定页面底部，支持首页/上一页/页码/下一页/末页 + 点击跳转。
 *
 * 使用方式：
 * ```kotlin
 * val bar = PaginationBarHelper.create(context) { action ->
 *     when (action) {
 *         PaginationBarHelper.Action.FIRST -> viewModel.firstPage()
 *         PaginationBarHelper.Action.PREV  -> viewModel.prevPage()
 *         PaginationBarHelper.Action.NEXT  -> viewModel.nextPage()
 *         PaginationBarHelper.Action.LAST  -> viewModel.lastPage()
 *         is PaginationBarHelper.Action.Jump -> viewModel.goToPage(action.page)
 *     }
 * }
 * rootLayout.addView(bar)
 * PaginationBarHelper.update(bar, currentPage = 2, totalPages = 5)
 * ```
 */
object PaginationBarHelper {

    sealed class Action {
        object FIRST : Action()
        object PREV : Action()
        object NEXT : Action()
        object LAST : Action()
        data class Jump(val page: Int) : Action()
    }

    /**
     * 创建翻页栏（返回 LinearLayout，添加到布局即可）。
     */
    fun create(context: Context, onAction: (Action) -> Unit): LinearLayout {
        val d = context.resources.displayMetrics.density
        val accent = ThemeColors.accent(context)
        val textSec = ThemeColors.textSecondary(context)
        val cardBg = ThemeColors.cardBg(context)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hPad = (16 * d).toInt()
            setPadding(hPad, (6 * d).toInt(), hPad, (10 * d).toInt())
            tag = "pagination_bar"

            background = GradientDrawable().apply {
                setColor(cardBg)
                setStroke((1 * d).toInt(), (textSec and 0x00FFFFFF) or 0x20000000)
                cornerRadius = 16 * d
            }
        }

        // 首页
        val btnFirst = iconBtn(context, "⟨⟨", textSec).apply { tag = "btn_first" }
        btnFirst.setOnClickListener { onAction(Action.FIRST) }
        bar.addView(btnFirst, btnLp(d))

        // 上一页
        val btnPrev = iconBtn(context, "⟨", textSec).apply { tag = "btn_prev" }
        btnPrev.setOnClickListener { onAction(Action.PREV) }
        bar.addView(btnPrev, btnLp(d))

        // 页码信息（可点击跳转）
        val tvPage = TextView(context).apply {
            text = "1 / 1"
            textSize = 14f
            setTextColor(ThemeColors.textPrimary(context))
            gravity = Gravity.CENTER
            tag = "tv_page_info"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (12 * d).toInt()
                marginEnd = (12 * d).toInt()
            }
            layoutParams = lp
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor((accent and 0x00FFFFFF) or 0x15000000)
                cornerRadius = 8 * d
            }
            isClickable = true
            isFocusable = true
        }
        tvPage.setOnClickListener { showJumpDialog(context, onAction) }
        bar.addView(tvPage)

        // 下一页
        val btnNext = iconBtn(context, "⟩", textSec).apply { tag = "btn_next" }
        btnNext.setOnClickListener { onAction(Action.NEXT) }
        bar.addView(btnNext, btnLp(d))

        // 末页
        val btnLast = iconBtn(context, "⟩⟩", textSec).apply { tag = "btn_last" }
        btnLast.setOnClickListener { onAction(Action.LAST) }
        bar.addView(btnLast, btnLp(d))

        return bar
    }

    /**
     * 更新翻页栏状态（页码 + 按钮可用性）。
     */
    fun update(bar: LinearLayout, currentPage: Int, totalPages: Int) {
        val tvPage = bar.findViewWithTag<TextView>("tv_page_info")
        tvPage?.text = "$currentPage / $totalPages"

        val canPrev = currentPage > 1
        val canNext = currentPage < totalPages

        bar.findViewWithTag<View>("btn_first")?.isEnabled = canPrev
        bar.findViewWithTag<View>("btn_prev")?.isEnabled = canPrev
        bar.findViewWithTag<View>("btn_next")?.isEnabled = canNext
        bar.findViewWithTag<View>("btn_last")?.isEnabled = canNext

        // 禁用态半透明
        listOf("btn_first", "btn_prev").forEach { t ->
            bar.findViewWithTag<View>(t)?.alpha = if (canPrev) 1f else 0.35f
        }
        listOf("btn_next", "btn_last").forEach { t ->
            bar.findViewWithTag<View>(t)?.alpha = if (canNext) 1f else 0.35f
        }
    }

    /**
     * 页码跳转弹窗。
     */
    private fun showJumpDialog(context: Context, onAction: (Action) -> Unit) {
        val d = context.resources.displayMetrics.density
        val accent = ThemeColors.accent(context)
        val cardBg = ThemeColors.cardBg(context)
        val textPrimary = ThemeColors.textPrimary(context)

        CommonDialogHelper.showCommonDialog(
            context = context,
            title = "跳转到页码",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(TextView(context).apply {
                    text = "请输入目标页码"
                    textSize = 13f
                    setTextColor(ThemeColors.textSecondary(context))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * d).toInt() }
                })
                val et = EditText(context).apply {
                    hint = "页码"
                    textSize = 16f
                    setTextColor(textPrimary)
                    setHintTextColor(ThemeColors.textSecondary(context))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.CENTER
                    setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = 10 * d
                        setStroke((1 * d).toInt(), (ThemeColors.textSecondary(context) and 0x00FFFFFF) or 0x40000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()
                    )
                    tag = "jump_input"
                }
                content.addView(et)
            },
            primaryBtnText = "跳转",
            onPrimaryClick = { dialog ->
                val et = dialog.findViewById<EditText>(R.id.common_dialog_content)
                    ?.findViewWithTag<EditText>("jump_input")
                val input = et?.text?.toString()?.toIntOrNull()
                if (input != null && input > 0) {
                    onAction(Action.Jump(input))
                }
                dialog.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ── 内部工具 ──

    private fun iconBtn(context: Context, text: String, color: Int): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            textSize = 18f
            setTextColor(color)
            gravity = Gravity.CENTER
            val size = (36 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            // 点击反馈
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        (v.background as? GradientDrawable)?.setColor((color and 0x00FFFFFF) or 0x15000000)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        (v.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
                    }
                }
                false
            }
        }
    }

    private fun btnLp(d: Float) = LinearLayout.LayoutParams(
        (36 * d).toInt(), (36 * d).toInt()
    ).apply {
        marginStart = (2 * d).toInt()
        marginEnd = (2 * d).toInt()
    }
}
