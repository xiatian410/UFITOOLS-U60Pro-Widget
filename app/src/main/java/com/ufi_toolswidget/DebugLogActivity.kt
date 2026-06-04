package com.ufi_toolswidget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DebugLogActivity : AppCompatActivity() {

    private lateinit var tvLogContent: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var switchEnabled: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_log)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)

        tvLogContent = findViewById(R.id.tv_log_content)
        scrollLog = findViewById(R.id.scroll_log)
        tvStatus = findViewById(R.id.tv_log_status)
        switchEnabled = findViewById(R.id.switch_debug_enabled)

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 调试开关
        switchEnabled.isChecked = DebugLogger.enabled
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            DebugLogger.enabled = isChecked
            refreshLog()
            Toast.makeText(this, if (isChecked) "调试模式已开启" else "调试模式已关闭", Toast.LENGTH_SHORT).show()
        }

        // 刷新按钮
        findViewById<View>(R.id.btn_refresh_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { refreshLog() }
        }

        // 复制按钮
        findViewById<View>(R.id.btn_copy_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { copyAll() }
        }

        // 快照按钮
        findViewById<View>(R.id.btn_dump_state).apply {
            AnimationUtil.applyScaleClickAnimation(this) { dumpState() }
        }

        // 分享按钮
        findViewById<View>(R.id.btn_share_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { shareLog() }
        }

        // 清空按钮
        findViewById<View>(R.id.btn_clear_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) {
                DebugLogger.clear()
                refreshLog()
                Toast.makeText(this@DebugLogActivity, "日志已清空", Toast.LENGTH_SHORT).show()
            }
        }

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)
        refreshLog()
    }

    private fun refreshLog() {
        val entries = DebugLogger.getRecent(300)
        val enabled = DebugLogger.enabled
        tvStatus.text = "模式: ${if (enabled) "运行中" else "已停止"} | 缓存: ${DebugLogger.size()} 条"

        if (entries.isEmpty()) {
            val persistent = DebugLogger.getPersistentLogs(this)
            if (persistent.isNotEmpty()) {
                tvLogContent.text = "--- 内存为空，显示持久化日志 ---\n\n$persistent"
            } else {
                tvLogContent.text = if (enabled) {
                    "(暂无日志 — 请在主界面操作后刷新查看)"
                } else {
                    "(调试模式未开启 — 开启开关后将开始记录日志)"
                }
            }
        } else {
            tvLogContent.text = entries.joinToString("\n") { it.formatted() }
        }

        // 滚动到底部
        scrollLog.post {
            scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyAll() {
        val text = DebugLogger.getAllText()
        if (text.isBlank()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", text))
        Toast.makeText(this, "已复制全部日志到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        if (DebugLogger.size() == 0) {
            Toast.makeText(this, "没有日志可分享", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val fullText = withContext(Dispatchers.IO) {
                    buildString {
                        appendLine(DebugLogger.dumpState(this@DebugLogActivity))
                        appendLine()
                        appendLine("========== 详细调试日志 ==========")
                        appendLine(DebugLogger.getAllText())
                    }
                }

                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir("logs") ?: filesDir
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "ufitools_debug_log.txt")
                    f.writeText(fullText)
                    f
                }

                val uri = FileProvider.getUriForFile(
                    this@DebugLogActivity,
                    "$packageName.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "UFITOOLS-Widget 调试反馈")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "发送调试日志"))
            } catch (e: Exception) {
                Toast.makeText(this@DebugLogActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dumpState() {
        val stateText = DebugLogger.dumpState(this)
        tvLogContent.text = stateText
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_state", stateText))
        Toast.makeText(this, "状态快照已生成并复制", Toast.LENGTH_SHORT).show()
    }
}
