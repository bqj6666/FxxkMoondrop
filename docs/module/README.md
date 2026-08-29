# FxxkMoondrop

Moondrop 蓝牙耳机助手（LSPosed / Xposed 模块）：耳机连接时自动弹出 **Fast Pair 卡片**，并通过 **GAIA BLE 协议**直连耳机，读取左右耳电量、控制降噪。

> 作者：[bqj6666](https://github.com/bqj6666) ｜ 许可证：**GPL-3.0**（见 [LICENSE](LICENSE)）

> **本仓库是 LSPosed 模块索引 / 发布页，不含源码。** 完整源码、构建方式、Issue 与最新版本请前往源码仓库：
>
> - 🔗 **源码仓库**：[bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)
> - 📦 **Releases**：[https://github.com/bqj6666/FxxkMoondrop/releases](https://github.com/bqj6666/FxxkMoondrop/releases)

---

## 功能

- **蓝牙监听 + GAIA 直连**：BLE GATT 直连耳机，读取左右耳电量、控制降噪
- **FastPairHook（LSPosed，注入 Google Play 服务）**：动态发现耳机 LE 地址并推送
- **弹窗模式**：Google Fast Pair 半屏弹窗（注入 GMS 的 HalfSheetActivity）
- **M3 界面**：主页 / 设置 / 关于，Material 3 + 动态取色
- **权限检测**：蓝牙 / 通知 / 悬浮窗 / 电池白名单 / Root / FastPairHook / GAIA 直连，一键跳转修复
- **日志抓取**：一键收集系统信息 / 应用设置 / 蓝牙 / 运行环境 / logcat 五类日志打包为 ZIP
- **显示层中英文切换**：语言偏好（跟随系统 / 中文 / English）

## 软件截图

| 主页概览 | 设置 | 关于 | Fast Pair 弹窗 |
|---|---|---|---|
| ![主页](screenshots/home.png) | ![设置](screenshots/settings.png) | ![关于](screenshots/about.png) | ![Fast Pair](screenshots/fastpair.png) |

## 安装

1. 下载并安装 APK（见源码仓库 [Releases](https://github.com/bqj6666/FxxkMoondrop/releases)）
2. 在 **LSPosed** 中启用，作用域：`com.google.android.gms`（可选 `com.android.settings`）
3. 授予蓝牙 / 通知 / 悬浮窗权限（设置页「检查权限」可一键跳转修复）

## 说明

- 本项目应用代码**部分使用 AI 辅助生成**，已人工审查与实机验证，但仍可能存在逻辑错误或兼容性问题，请谨慎使用，风险自负
- 项目借助逆向 Moondrop App 私有接口实现，**仅供学习研究使用**，不含 Moondrop App 任何代码 / 资源 / 反编译产物，请勿用于商业用途
