package com.ufi_toolswidget

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.*
import java.io.File

class AboutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AboutActivity"
    }

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    /** 调试模式：版本号点击计数 */
    private var versionClickCount = 0
    private var versionClickLastTime = 0L

    // 详情弹窗相关
    private var activeDialog: Dialog? = null
    private var activeDialogType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_about)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)
        DebugLogger.init(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 加载应用图标并清除滤镜
        refreshAppIcon()

        // 显示版本号
        val versionName = UpdateChecker.getLocalVersionName(this)
        val tvVersion = findViewById<TextView>(R.id.tv_app_version)
        tvVersion.text = "版本 $versionName"

        // 调试模式入口：连续点击版本号 5 次激活，再次点击进入日志页
        tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - versionClickLastTime > 1500) {
                versionClickCount = 0
            }
            versionClickLastTime = now
            versionClickCount++

            if (DebugLogger.enabled) {
                // 调试模式已启用 → 直接进入日志页
                startActivity(Intent(this, DebugLogActivity::class.java))
                versionClickCount = 0
            } else if (versionClickCount >= 5) {
                // 激活调试模式
                DebugLogger.enabled = true
                versionClickCount = 0
                DebugLogger.i(TAG, "调试模式已激活")
                Toast.makeText(this, "调试模式已激活 ✓\n再次点击版本号查看日志", Toast.LENGTH_LONG).show()
            } else if (versionClickCount >= 3) {
                Toast.makeText(this, "再点击 ${5 - versionClickCount} 次激活调试模式", Toast.LENGTH_SHORT).show()
            }
        }

        // GitHub 链接点击
        findViewById<View>(R.id.card_github).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Asunano/UFITOOLS-Widget"))
            startActivity(intent)
        }

        // 致谢链接点击
        findViewById<View>(R.id.tv_thanks_link)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/kanoqwq/UFI-TOOLS"))
            startActivity(intent)
        }

        // 更新镜像源选择器
        initMirrorSelector()

        // 检查更新按钮
        val btnCheck = findViewById<View>(R.id.btn_check_update)
        val tvUpdateStatus = findViewById<TextView>(R.id.tv_update_status)
        val progressUpdate = findViewById<ProgressBar>(R.id.progress_update)

        btnCheck.apply {
            setOnClickListener {
                btnCheck.isEnabled = false
                tvUpdateStatus.visibility = View.VISIBLE
                tvUpdateStatus.text = "正在检查更新..."
                progressUpdate.visibility = View.VISIBLE

                // 记录本次检查所使用的镜像源
                val usedMirror = currentMirror

                UpdateChecker.checkUpdate(this@AboutActivity) { info, error ->
                    progressUpdate.visibility = View.GONE
                    btnCheck.isEnabled = true

                    when {
                        error != null -> {
                            if (usedMirror == 0 && UpdateChecker.isNetworkError(error)) {
                                // 使用官方源且网络出错 → 提示切换国内镜像
                                tvUpdateStatus.text = "$error\n\n💡 检测到网络问题，建议切换至「国内镜像」源后重试"
                                showMirrorSwitchDialog()
                            } else {
                                tvUpdateStatus.text = error
                            }
                        }
                        info != null -> {
                            tvUpdateStatus.text = "发现新版本 ${info.versionName}"
                            showUpdateDialog(info)
                        }
                        else -> {
                            tvUpdateStatus.text = "当前已是最新版本 ✓"
                        }
                    }
                }
            }
            setOnTouchListener(ScaleTouchListener())
        }

    }

    // ==================== 镜像源选择器 ====================
    private var currentMirror: Int = 0 // 0=官方, 1=镜像

    private fun initMirrorSelector() {
        currentMirror = SPUtil.getUpdateMirror(this)
        val chipOfficial = findViewById<TextView>(R.id.chip_mirror_official)
        val chipProxy = findViewById<TextView>(R.id.chip_mirror_proxy)

        fun selectMirror(mirror: Int) {
            currentMirror = mirror
            SPUtil.setUpdateMirror(this, mirror)
            refreshMirrorChips()
            // 切换镜像源时清除旧的检查状态
            findViewById<TextView>(R.id.tv_update_status).visibility = View.GONE
            val name = if (mirror == 0) "GitHub 官方" else "国内镜像"
            Toast.makeText(this, "已切换至 $name 源", Toast.LENGTH_SHORT).show()
        }

        chipOfficial.setOnClickListener { selectMirror(0) }
        chipProxy.setOnClickListener { selectMirror(1) }

        refreshMirrorChips()
    }

    private fun refreshMirrorChips() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val chipRadius = 18f * resources.displayMetrics.density

        val chipSelected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = chipRadius
        }
        val chipUnselected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = chipRadius
        }

        findViewById<TextView>(R.id.chip_mirror_official).apply {
            background = if (currentMirror == 0) chipSelected else chipUnselected
            setTextColor(if (currentMirror == 0) 0xFFFFFFFF.toInt() else textPrimary)
        }
        findViewById<TextView>(R.id.chip_mirror_proxy).apply {
            background = if (currentMirror == 1) chipSelected else chipUnselected
            setTextColor(if (currentMirror == 1) 0xFFFFFFFF.toInt() else textPrimary)
        }
    }

    /** 网络连接失败时弹窗提示切换国内镜像源 */
    private fun showMirrorSwitchDialog() {
        if (currentMirror == 1) return // 已经是镜像源，不再提示
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("网络连接失败")
            .setMessage("当前使用 GitHub 官方源检查更新失败，可能是网络不通。\n\n是否切换至国内镜像源？切换后需重新点击「检查更新」。")
            .setPositiveButton("切换至国内镜像") { _, _ ->
                currentMirror = 1
                SPUtil.setUpdateMirror(this, 1)
                refreshMirrorChips()
                Toast.makeText(this, "已切换至国内镜像源，请重新检查更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("暂不切换", null)
            .show()
    }

    /**
     * 弹窗显示更新详情（基于 dialog_update.xml）
     */
    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = buildString {
            append("发现新版本: ${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) {
                append("更新日志:\n")
                append(UpdateChecker.formatChangelog(info.changelog))
                append("\n\n")
            }
            if (info.apkSize > 0) {
                append("大小: ${UpdateChecker.formatFileSize(info.apkSize)}")
            }
        }

        val dialog = createThemedDialog(R.layout.dialog_update, dialogType = "update")
        dialog.findViewById<TextView>(R.id.dialog_update_message).text = message
        dialog.findViewById<View>(R.id.dialog_update_btn_download).apply {
            setOnClickListener {
                dialog.dismiss()
                downloadAndInstall(info.apkUrl, info.tagName, info.apkSha256)
            }
            setOnTouchListener(ScaleTouchListener())
        }
        dialog.findViewById<View>(R.id.dialog_update_btn_later).apply {
            setOnClickListener { dialog.dismiss() }
            setOnTouchListener(ScaleTouchListener())
        }
        dialog.show()
    }

    // ===== 弹窗框架 (Ported from MainActivity) =====

    private fun createThemedDialog(layoutRes: Int, widthRatio: Float = 0.92f, dialogType: String): Dialog {
        if (activeDialog?.isShowing == true && activeDialogType == dialogType) {
            return activeDialog!!
        }
        dismissActiveDialog()

        val dialog = object : Dialog(this) {
            private var isDismissing = false

            fun realDismiss() {
                super.dismiss()
                activeDialog = null
                activeDialogType = null
            }

            override fun dismiss() {
                if (isDismissing || window == null) {
                    realDismiss()
                    return
                }
                isDismissing = true
                activeDialogType = null 

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AnimationUtil.applyDialogBlurOut(this) { realDismiss() }
                } else {
                    realDismiss()
                }
            }
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutRes)
        applyThemeToDialogRoot(dialog)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setWindowAnimations(R.style.DialogAnimationTheme)
            setLayout(
                (resources.displayMetrics.widthPixels * widthRatio).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        applyDialogBlur(dialog)

        dialog.setOnDismissListener {
            activeDialog = null
            activeDialogType = null
        }
        activeDialog = dialog
        activeDialogType = dialogType
        return dialog
    }

    private fun applyDialogBlur(dialog: Dialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurIn(dialog)
        } else {
            applyLegacyBlur(dialog)
        }
    }

    private fun applyLegacyBlur(dialog: Dialog) {
        try {
            val rootView = window.decorView.rootView
            val vw = rootView.width
            val vh = rootView.height
            if (vw <= 0 || vh <= 0) return
            val capture = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(capture))
            val smallW = (vw * 0.06f).toInt().coerceAtLeast(4)
            val smallH = (vh * 0.06f).toInt().coerceAtLeast(4)
            val small = Bitmap.createScaledBitmap(capture, smallW, smallH, true)
            capture.recycle()
            val blurred = Bitmap.createScaledBitmap(small, vw, vh, true)
            small.recycle()
            dialog.window?.setBackgroundDrawable(BitmapDrawable(resources, blurred))
        } catch (e: Exception) {
            Log.w(TAG, "Legacy blur failed: ${e.message}")
        }
    }

    private fun applyThemeToDialogRoot(dialog: Dialog) {
        val root = dialog.findViewById<ViewGroup>(android.R.id.content)
            ?.let { if (it.childCount > 0) it.getChildAt(0) as? ViewGroup else it } ?: return
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        val borderColor = if (SPUtil.getNightMode(this) == AppCompatDelegate.MODE_NIGHT_YES)
            0x4DFFFFFF.toInt() else 0x35000000
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = 16f
            setStroke(2, borderColor)
        }
        root.elevation = 24f

        applyThemeToViewTree(root, textPrimary, textSecondary, accent)
    }

    private fun applyThemeToViewTree(parent: ViewGroup, textPrimary: Int, textSecondary: Int, accent: Int) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is MaterialButton -> {
                    if (child.strokeWidth > 0) {
                        child.setTextColor(textPrimary)
                        child.strokeColor = ColorStateList.valueOf(textSecondary)
                        child.iconTint = ColorStateList.valueOf(accent)
                    } else {
                        child.backgroundTintList = ColorStateList.valueOf(accent)
                        child.setTextColor(0xFFFFFFFF.toInt())
                        child.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    }
                }
                is Button -> {
                    child.backgroundTintList = ColorStateList.valueOf(accent)
                    child.setTextColor(0xFFFFFFFF.toInt())
                }
                is TextView -> {
                    child.setTextColor(if (child.textSize <= 13f) textSecondary else textPrimary)
                }
                is ImageView -> {
                    child.setColorFilter(accent)
                }
                is ViewGroup -> {
                    applyThemeToViewTree(child, textPrimary, textSecondary, accent)
                }
            }
        }
    }

    private fun dismissActiveDialog() {
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        activeDialogType = null
    }

    // ===== 下载与安装逻辑 =====

    /**
     * 下载并安装（自动应用镜像加速）
     */
    private fun downloadAndInstall(url: String, tag: String, sha256: String) {
        val finalUrl = UpdateChecker.applyMirrorToUrl(this, url)
        if (finalUrl.isBlank()) {
            Toast.makeText(this, "没有可下载的 APK", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 9 及以下需要存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
                return
            }
        }

        startDownload(finalUrl, tag, sha256)
    }

    private fun startDownload(url: String, tag: String, sha256: String) {
        val fileName = "UFITOOLS-Widget-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("UFITOOLS-Widget")
            .setDescription("正在下载 $tag 版本...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show()

        // 监听下载完成
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName, sha256)
                    unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(fileName: String, sha256: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (!file.exists()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // SHA256 校验
        if (sha256.isNotBlank()) {
            if (!UpdateChecker.verifySha256(file, sha256)) {
                file.delete()
                Toast.makeText(this, "文件校验失败，已删除损坏文件\n请重新下载", Toast.LENGTH_LONG).show()
                return
            }
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请重新点击下载", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)
        refreshAppIcon()
        currentMirror = SPUtil.getUpdateMirror(this)
        refreshMirrorChips()
    }

    /** 刷新应用图标显示，确保不被主题滤镜覆盖 */
    private fun refreshAppIcon() {
        val iconView = findViewById<ImageView>(R.id.iv_app_icon) ?: return
        try {
            val icon = packageManager.getApplicationIcon(packageName)
            iconView.setImageDrawable(icon)
        } catch (_: Exception) {
            iconView.setImageResource(R.mipmap.ic_launcher)
        }
        iconView.clearColorFilter()
    }

    override fun onDestroy() {
        downloadReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
