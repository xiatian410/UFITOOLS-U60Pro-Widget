# UFI-TOOLS Widget

[![Build APK](https://github.com/你的用户名/UFITOOLSWidget/actions/workflows/build.yml/badge.svg)](https://github.com/你的用户名/UFITOOLSWidget/actions/workflows/build.yml)

一个 Android 桌面小组件应用，用于实时监控随身 WiFi 设备（如 F50、U30 Air 等）的运行状态。

## 功能特性

- 🖥️ **9 种小组件尺寸**：1×1 到 3×3，适配各种桌面布局
- 📊 **设备信息监控**：信号强度、电量、温度、CPU/内存使用率
- 📶 **网络状态**：运营商、网络类型（4G/5G SA/NSA）、频段
- 📈 **流量统计**：今日流量、本月流量，大字号突出显示
- 🔋 **电池信息**：电量百分比、充放电电流、电压
- 💾 **存储空间**：内部存储已用/总容量
- 🌐 **WiFi 信息**：WiFi 名称、设备 IP 地址
- ⏰ **后台自动刷新**：每 15 分钟通过 WorkManager 定时更新

## 截图

<!-- 在此处添加小组件截图 -->

## 系统要求

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 14 (API 34) |
| 设备要求 | 需要与 UFI 随身 WiFi 设备处于同一局域网 |

## 使用方法

1. **安装 APK** 到你的 Android 手机
2. 确保手机已连接到随身 WiFi 设备的 WiFi 网络
3. 打开应用，在输入框中填入设备后台登录口令（默认 `admin`）
4. 点击「保存配置」，应用会自动同步设备信息
5. 回到桌面，添加 UFI-TOOLS Widget 小组件
6. 选择你喜欢的尺寸（1×1 到 3×3）

## 工作原理

```
┌─────────────────┐     HTTP API      ┌──────────────────┐
│  Android 小组件   │ ◄──────────────► │  UFI 随身WiFi设备   │
│  (UFI-TOOLS)     │   kano-sign 鉴权  │  (192.168.0.1)   │
└─────────────────┘                   └──────────────────┘
```

应用通过 HTTP API 与局域网内的 UFI 设备通信，获取实时设备信息并通过桌面小组件展示。

## 技术栈

- **语言**：Kotlin
- **HTTP 客户端**：OkHttp 4
- **后台任务**：AndroidX WorkManager
- **UI**：AndroidX + Material Design
- **构建**：Gradle 9.4.1 + AGP 8.7.3
- **CI/CD**：GitHub Actions

## 项目结构

```
UFITOOLSWidget/
├── app/src/main/
│   ├── java/com/ufi_toolswidget/
│   │   ├── MainActivity.kt          # 主界面
│   │   ├── WebViewActivity.kt       # 设备管理后台
│   │   ├── widget/
│   │   │   └── WifiWidget.kt        # 小组件提供者（9种尺寸）
│   │   ├── worker/
│   │   │   └── WifiWorker.kt        # 后台定时任务
│   │   └── util/
│   │       ├── NetUtil.kt           # 网络工具 & 签名算法
│   │       ├── SPUtil.kt            # 数据持久化
│   │       └── WifiCrawl.kt         # API 数据采集
│   └── res/
│       ├── layout/widget_1x1.xml ~ widget_3x3.xml  # 9种布局
│       └── xml/widget_1x1_info.xml ~ widget_3x3_info.xml
└── .github/workflows/build.yml      # CI 自动构建
```

## 构建

### 本地构建

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions

每次推送代码到 `main`/`master` 分支时，GitHub Actions 会自动编译 APK。编译产物可在 Actions 页面的 Artifacts 中下载。

> **注意**：默认不会自动发布 Release。如需开启，请修改 `.github/workflows/build.yml` 中的 `ENABLE_RELEASE` 为 `true`。

## 许可

[MIT License](LICENSE)

## 致谢

- [UFI-TOOLS](https://github.com/kanoqwq/UFI-TOOLS) - 原版 UFI 设备管理工具
- API 接口文档参考 UFI-TOOLS 项目
