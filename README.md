# FxxkMoondrop

> 作者：[bqj6666](https://github.com/bqj6666) ｜ 版本：**alpha2.13**（versionCode 229） ｜ 许可证：**GPL-3.0**（见 [LICENSE](LICENSE)）

Moondrop 蓝牙耳机助手：耳机连接 / 断开时自动弹出 **Fast Pair 风格卡片**（设备名 + 电量 + 降噪模式），并通过 **GAIA BLE 协议直连**耳机读取状态、控制降噪。项目本体是一个 **LSPosed / Xposed 模块**（单一 APK 一体打包），同时内置可直接运行的应用主体。

> **AI 生成警告**：本项目应用代码**完全由 AI 生成**（未经专业 Android 开发者人工审查），可能存在逻辑错误、安全缺陷或兼容性问题。**请仔细核对代码后再使用**，使用风险自负。
>
> **逆向声明**：本项目借助 Hook Moondrop App（`com.moondroplab.moondrop.moondrop_app`）的私有接口实现耳机控制与状态读取，**仅供学习研究使用**。项目**不包含 Moondrop App 的任何代码、资源或反编译产物**；所有 hook 目标类名仅以字符串形式引用。请勿用于商业用途，使用后果自负。

---

## 功能

- **蓝牙监听 + GAIA 直连**：自研 `GaiaBleClient`，BLE GATT 直连耳机，读取左右耳电量，控制降噪（关闭 / 降噪 / 透传）
- **FastPairHook（LSPosed 模块，注入 Google Play 服务）**：借道 GMS 的 BLE 扫描能力动态发现耳机 LE 地址并推送给应用
- **自愈闭环**：无缓存 → REQ 扫描 → GMS 推送 → 连接成功写回文件 / SP → 下次秒连；地址变化自动重新发现（**地址全动态发现、零硬编码**）
- **两种弹窗模式**：Google Fast Pair 半屏弹窗（默认，注入 GMS 的 HalfSheetActivity）/ 应用自带悬浮卡片（不依赖 Xposed，应用主体仍可独立运行）
- **三页 M3 界面**：主页（英雄卡 + 状态面板 + 降噪三按钮）、设置页（外观 / 通用 / 行为，随系统深浅色 + Material You 动态取色）、关于页，全部 Material 3 组件化
- **权限检测**（整页二级界面）：蓝牙 / 通知 / 悬浮窗 / 电池白名单 / Root / FastPairHook / GAIA 直连 7 项实时检查，缺失一键跳转修复
- **日志抓取**（设备适配）：一键收集系统信息 / 应用设置 / 蓝牙 / 运行环境 / logcat 五类日志打包为 ZIP（含 Material 隐私声明弹窗）
- **Root 强力保活**、开机自启、后台隐藏（可选开关）

## 技术栈

| 项目 | 说明 |
|---|---|
| 语言 | **100% Kotlin**（源码 28 个 `.kt`，零 Java） |
| 构建链 | Gradle 8.9（wrapper 固定）+ AGP 8.5.2 + Kotlin 1.9.22 |
| UI | Material 3（`Theme.Material3.DayNight.NoActionBar`）+ 动态取色，三页 Fragment 架构 |
| 模块 | Xposed API 93（LSPosed 推荐作用域 `com.google.android.gms;com.android.settings`） |
| 包名 | `com.fxxkmoondrop.secret` |

## 构建

```bash
./gradlew :app:assembleRelease -PfxxkKeypass=<签名密码>
```

- 产物：`app/build/outputs/apk/release/app-release.apk`
- 构建后自动执行 `postEdf`：注入 `META-INF/xposed/*`（scope.list / ascope.list）并重新签名，`VERIFY: OK` 即注入成功
- 签名密钥请自行准备（已在 `.gitignore` 中排除，不入库），构建时通过 `-PfxxkKeypass=` 传入密码

## 安装

1. 安装 APK（无需单独安装 GMS 模块，一体打包）
2. 在 **LSPosed** 中启用并勾选作用域 `com.google.android.gms`（可选 `com.android.settings`）
3. 授予蓝牙 / 通知 / 悬浮窗权限（设置页「检查权限」可一键跳转修复）
4. 弹窗默认 Google Fast Pair 半屏弹窗，也可在设置中切换为应用自带悬浮卡片

## 目录结构

```
alpha_src/
├── app/                  # Gradle 应用模块（sourceSets 指向 ../src）
│   └── src/main/         # res / assets(ga2_icon.png, xposed_init) / META-INF/xposed
├── src/                  # 全部 Kotlin 源码（com.fxxkmoondrop.secret）
├── gradle/               # Gradle wrapper（8.9）
├── build.gradle.kts      # AGP 8.5.2 + Kotlin 1.9.22（apply false）
├── settings.gradle.kts   # 模块声明与仓库
└── xposed-api-stub.jar   # Xposed 编译期 stub
```

## 致谢

- [JingMatrix](https://github.com/JingMatrix) 及其维护的 [LSPosed](https://github.com/JingMatrix/LSPosed) / [Vector](https://github.com/JingMatrix/Vector) 框架：本项目的**界面与交互风格参考了 LSPosed Manager 的设计**，特此致谢；项目亦受益于 LSPosed 生态的工具链
- [LSPlant](https://github.com/JingMatrix/LSPlant) 与 Xposed / LSPosed 社区
- AI 协助开发：Deepseek · Qwen · ChatGPT · Kimi

## 版本历史

- **alpha2.13**（当前）：Kotlin 迁移 28/28 完成（纯 Kotlin 源码）；修复设置 / 关于页标题与状态栏重叠；修复切换 AMOLED 触发 recreate 后页面丢失；Gradle + AGP 工程化完成；clean 全量构建验证通过
- **alpha2.12**：M3 三页 Fragment 架构（主页 / 设置 / 关于）
- **alpha2.0 及以前**：单体 Activity + 旧打包链（历史版本不在本仓库）

## 免责声明

本项目仅供学习与研究 Android 逆向与蓝牙协议使用，请勿用于任何商业用途或侵犯他人权益的行为。使用本项目造成的一切后果由使用者自行承担。
