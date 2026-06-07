package com.ufi_toolswidget

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.util.WifiEntity
import com.ufi_toolswidget.worker.WifiWorker
import com.ufi_toolswidget.widget.BaseWifiWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：持有设备数据状态，解决 Activity 重建（旋转屏幕）数据丢失问题。
 *
 * 职责：
 * - 缓存 [WifiEntity] 数据，横跨配置变更存活
 * - 从 SharedPreferences 恢复旋转后的初始数据
 * - 提供 [refreshData] 供 Activity 触发刷新，通过回调返回结果
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // ═══════════════════════════════════════════════
    // 数据状态
    // ═══════════════════════════════════════════════

    private val _wifiEntity = MutableStateFlow<WifiEntity?>(null)
    /** 最新的设备数据，支持协程 collect 观察（用于旋转恢复） */
    val wifiEntity: StateFlow<WifiEntity?> = _wifiEntity.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** 最近一次错误信息 */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** 是否正在加载中 */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 当前弹窗类型（用于旋转恢复时保持弹窗状态） */
    private val _activeDialogType = MutableStateFlow<String?>(null)
    val activeDialogType: StateFlow<String?> = _activeDialogType.asStateFlow()

    private var refreshJob: Job? = null

    init {
        loadCachedData()
    }

    // ═══════════════════════════════════════════════
    // 读写方法
    // ═══════════════════════════════════════════════

    /** 同步获取当前缓存数据（用于弹窗点击等非响应式场景） */
    fun getWifiEntity(): WifiEntity? = _wifiEntity.value

    /** 设置弹窗类型 */
    fun setActiveDialogType(type: String?) {
        _activeDialogType.value = type
    }

    /** 清除弹窗状态 */
    fun clearActiveDialog() {
        _activeDialogType.value = null
    }

    // ═══════════════════════════════════════════════
    // 核心刷新逻辑
    // ═══════════════════════════════════════════════

    /**
     * 异步刷新设备数据。
     *
     * @param ctx        上下文
     * @param onSuccess  成功回调，传入最新 [WifiEntity]
     * @param onError    达到失败阈值回调，传入原因 [WifiWorker.REASON_NETWORK] 或 [WifiWorker.REASON_API]
     * @param onToast    未达阈值时的 Toast 回调 (message, isLong)
     */
    fun refreshData(
        ctx: Context,
        onSuccess: (WifiEntity) -> Unit,
        onError: (String) -> Unit,
        onToast: (String, Boolean) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val data = try {
                WifiCrawl.getWifiData(appCtx)
            } catch (e: Exception) {
                DebugLogger.logExc(TAG, "refreshData: unexpected exception: ${e.message}")
                null
            }

            launch(Dispatchers.Main) {
                if (data != null) {
                    DebugLogger.logApi(TAG, "refreshData: success, model=${data.model}")
                    _wifiEntity.value = data
                    _lastError.value = null
                    _isLoading.value = false
                    if (_activeDialogType.value == "error") {
                        _activeDialogType.value = null
                    }
                    WifiWorker.resetFailureState(appCtx)
                    SPUtil.saveData(appCtx, data)
                    BaseWifiWidget.renderAllWidgets(appCtx)
                    onSuccess(data)
                } else {
                    _isLoading.value = false
                    val error = WifiCrawl.lastError
                    _lastError.value = error

                    val isNetworkError = error.contains("Network error", ignoreCase = true) ||
                        error.contains("timeout", ignoreCase = true) ||
                        error.contains("connect", ignoreCase = true) ||
                        error.contains("refused", ignoreCase = true)

                    if (isNetworkError) {
                        val netFails = WifiWorker.incrementNetworkFailureCount(appCtx)
                        DebugLogger.logApiErr(TAG, "refreshData: network error, netFails=$netFails/${WifiWorker.NETWORK_MAX_FAILURES}")
                        if (netFails >= WifiWorker.NETWORK_MAX_FAILURES) {
                            WifiWorker.markWorkerStoppedNetwork(appCtx)
                            DebugLogger.logExc(TAG, "refreshData: network threshold reached")
                            BaseWifiWidget.renderAllWidgets(appCtx)
                            onError(WifiWorker.REASON_NETWORK)
                            return@launch
                        }
                    } else {
                        val apiFails = WifiWorker.incrementApiFailureCount(appCtx)
                        DebugLogger.logApiErr(TAG, "refreshData: API error, apiFails=$apiFails/${WifiWorker.API_MAX_FAILURES}, error=$error")
                        if (apiFails >= WifiWorker.API_MAX_FAILURES) {
                            WifiWorker.markWorkerStoppedApi(appCtx)
                            DebugLogger.logExc(TAG, "refreshData: API threshold reached")
                            BaseWifiWidget.renderAllWidgets(appCtx)
                            onError(WifiWorker.REASON_API)
                            return@launch
                        }
                    }

                    if (error.contains("401") || error.contains("Unauthorized")) {
                        onToast("访问受限，请在设置中检查管理口令", true)
                    } else {
                        onToast("同步失败: ${error.ifEmpty { "网络超时" }}", false)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 缓存恢复
    // ═══════════════════════════════════════════════

    /** 从 SharedPreferences 加载缓存数据到 StateFlow */
    private fun loadCachedData() {
        val sp = SPUtil.getSp(getApplication())
        val model = sp.getString("model", null) ?: return
        _wifiEntity.value = reconstructFromSp(sp, model)
    }

    /**
     * 从 SharedPreferences 重建 WifiEntity（仅包含持久化字段）。
     * AT 信号、CPU 详情等瞬态字段以默认值填充，旋转恢复后首次 API 刷新会补全。
     */
    private fun reconstructFromSp(sp: android.content.SharedPreferences, model: String): WifiEntity {
        return WifiEntity(
            model = model,
            flow = sp.getString("flow", "") ?: "",
            dailyFlow = sp.getString("daily_flow", "") ?: "",
            signal = sp.getString("signal", "") ?: "",
            temp = sp.getString("temp", "") ?: "",
            battery = sp.getString("battery", "") ?: "",
            batteryPercent = sp.getInt("battery_percent", 0),
            cpu = sp.getString("cpu", "") ?: "",
            mem = sp.getString("mem", "") ?: "",
            netType = sp.getString("net_type", "") ?: "",
            appVer = sp.getString("app_ver", "") ?: "",
            appVerCode = sp.getString("app_ver_code", "") ?: "",
            batteryCurrent = sp.getString("battery_current", "") ?: "",
            batteryVoltage = sp.getString("battery_voltage", "") ?: "",
            internalStorage = sp.getString("internal_storage", "") ?: "",
            internalTotalStorage = sp.getLong("internal_total_storage", 0L),
            internalUsedStorage = sp.getLong("internal_used_storage", 0L),
            internalAvailableStorage = sp.getLong("internal_available_storage", 0L),
            externalTotalStorage = sp.getLong("external_total_storage", 0L),
            externalUsedStorage = sp.getLong("external_used_storage", 0L),
            externalAvailableStorage = sp.getLong("external_available_storage", 0L),
            clientIp = sp.getString("client_ip", "") ?: "",
            deviceModel = sp.getString("device_model", "") ?: "",
            firmwareVer = sp.getString("firmware_ver", "") ?: "",
            needToken = sp.getBoolean("need_token", false),
            // 以下为 API 瞬态字段，缓存时使用默认值
            atNetworkInfo = null,
            cpuTempList = emptyList(),
            cpuFreqInfo = emptyMap(),
            cpuUsageInfo = emptyMap(),
            memTotalKb = 0L,
            memAvailableKb = 0L,
            memUsedKb = 0L,
            swapTotalKb = 0L,
            swapUsedKb = 0L,
            swapFreeKb = 0L,
            wanIp = "",
            wanIpv6 = "",
            pdpTypeGoform = "",
            goformImei = "",
            goformImsi = "",
            goformIccid = "",
            hardwareVersion = "",
            webVersion = "",
            macAddress = "",
            pinStatusCode = -1,
            monthlyUploadBytes = 0L,
            monthlyDownloadBytes = 0L,
        )
    }
}
