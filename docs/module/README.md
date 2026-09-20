# FxxkMoondrop

Moondrop 蓝牙耳机助手（LSPosed / Xposed 模块）：耳机连接时自动弹出 **Fast Pair 卡片**，并通过 **GAIA BLE 协议**直连耳机，读取左右耳电量、控制降噪。

> 作者：[bqj6666](https://github.com/bqj6666) ｜ 版本：**3.2.4**（versionCode 324） ｜ 许可证：**GPL-3.0**（见 [LICENSE](LICENSE)）

> **本仓库是 LSPosed 模块索引 / 发布页，不含源码。** 完整源码、构建方式、Issue 与最新版本请前往源码仓库：
>
> - 🔗 **源码仓库**：[bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)
> - 📦 **Releases**：[https://github.com/bqj6666/FxxkMoondrop/releases](https://github.com/bqj6666/FxxkMoondrop/releases)

---

## 功能

- **蓝牙监听 + GAIA 直连**：BLE GATT 直连耳机，读取左右耳电量、控制降噪
- **ANC 型号档案库**：`AncProfileLib` 按设备名自动套用实测设备码映射，未实测型号回退默认映射
- **FastPairHook（LSPosed，注入 Google Play 服务）**：借道 GMS 的 BLE 扫描动态发现耳机 LE 地址并推送
- **弹窗模式**：Google Fast Pair 半屏弹窗（注入 GMS 的 HalfSheetActivity）；弹窗「设置」按钮跳转系统蓝牙设备详情页
- **系统设置蓝牙详情注入（LSPosed）**：Hook 系统蓝牙设备详情页，注入降噪与功能控制面板（抗风 / 增益 / 指示灯）；未连接时整块收起，耳机未就绪时用官方加载行原位占位
- **官方空间音频 / 头部跟踪两行（LSPosed）**：详情页直接使用系统自带的「空间音频」与「头部跟踪」开关，不再自绘控件 —— 官方那两行在本机被系统判定为不可用并自行移除，模块放开该判定后交由官方渲染，勾选态与点击接到耳机端；仅对支持的耳机接管，他牌耳机原样交还官方
- **Google 官方耳机控制面板桥接（LSPosed）**：Hook GMS 的 Hearable Controls 链路（GFPS 消息组 `0x08`）—— 以 DexKit 特征定位官方 ANC 子模块、放开 Fast Pair 缓存门禁，在意图入口与管理器发送出口两处接住面板点击（降噪 / 通透 / 音量条 / 提示音和振动），翻译成 GAIA 请求真实下发；并把应用侧真实档位注入官方 DataStore，官方界面高亮与实际一致
- **三协议自动识别**：GAIA V3（BLE）/ GAIA V4（RFCOMM/SPP，布丁）/ Moondrop 私有 9ECA0000 自动路由
- **9ECA 私有协议客户端**：音源切换 / EQ / MIC / SN（复用同一 GATT 连接）
- **设备通知**：耳机电量与降噪控制合并为一条常驻通知（只显示左右耳，不含充电盒）；档位按钮带大图标、当前档位高亮、颜色随系统动态取色，按钮按设备实际支持的能力生成
- **无 Root 模式**：未检测到 Root 时自动回落为仅通过通知栏与 App 主界面控制降噪（GAIA BLE 直连本就不需要 Root），所有 Root 依赖项静默停用，不报错、不弹窗
- **M3 界面**：主页 / 设置 / 关于，Material 3 + 动态取色
- **设置页功能开关**：官方集成（官方降噪面板 / 蓝牙详情页面板）、通知（电量通知 / 通知内降噪控制）可单独停用
- **权限检测**（整页二级界面）：6 项实时检查，按**必要权限**（蓝牙 / 通知 / GAIA 直连）与**可选权限**（电池白名单 / Root / FastPairHook 模块）分组呈现，一键跳转修复；结论只看必要项
- **日志抓取**（设备适配）：一键收集系统信息 / 应用设置 / 蓝牙 / 运行环境 / logcat 六类日志打包为 ZIP
- **保活默认常开且无需 Root**：开机自启 + 30 秒看门狗 + 系统电池优化白名单（只主动询问一次）；已 Root 时静默追加增强。另有后台隐藏可选开关
- **使用引导**：首次启动自动展示，横滑分页、每页一个功能分区（关于 / 权限申请 / 连接管理 / 系统集成 / 耳机控制 / 适配与诊断 / 欢迎使用）；页内开关与设置页同源，改完立即生效；看完或跳过后不再自动弹出，设置页最底部可随时重看
- **显示层中英文切换**：语言偏好（跟随系统 / 中文 / English）

## 软件截图

| 主页概览 | 设置 | 关于 | Fast Pair 弹窗 | 设备通知 |
|---|---|---|---|---|
| ![主页](screenshots/home.png) | ![设置](screenshots/settings.png) | ![关于](screenshots/about.png) | ![Fast Pair](screenshots/fastpair.png) | ![设备通知](screenshots/notif.png) |

## 支持设备

> 兼容性判定基于**蓝牙传输层与服务指纹**，不依赖型号名。因此只要主控为**高通 QCC** 或**中科蓝讯（Bluetrum）**，理论上即可接入。

- **已实测**：梦回2 / Golden Ages 2（GAIA）、太空漫游2 / Space Travel 2（9ECA）
- **理论上支持**：爱丽丝 / 火花 / 旅行者 / 梦回1979 / 猫饼 / 音乐胶囊 / 超声波 / 知更鸟 / 布丁（GAIA V4）
- **未知**：太空漫游（一代）/ 猫咖 / 方糖 / 太空漫游2 ULTRA / 羽翼 EDGE 等，待实机验证

更多细分的型号、主控与协议依据，见源码仓库 [FxxkMoondrop README](https://github.com/bqj6666/FxxkMoondrop)。

## 安装

1. 下载并安装 APK（见源码仓库 [Releases](https://github.com/bqj6666/FxxkMoondrop/releases)）
2. 在 **LSPosed** 中启用，作用域：`com.google.android.gms`（可选 `com.android.settings`）
3. 首次启动会展示**使用引导**，可在其中直接完成权限授权（设置页「检查权限」亦可一键跳转修复）；已跳过的话可在设置页最底部重新打开
4. **Root 可选**：没有 Root 也能用 —— 未检测到 Root 时自动进入无 Root 模式，照常读电量、切降噪（走 GAIA BLE 直连），仅官方面板注入、弹窗图标自定义、Root 保活等增强项停用

## Fast Pair 弹窗适配

> **Fast Pair 弹窗依赖完整的 Google Play 服务（GMS）**，能否弹出与手机系统的 GMS 完整程度有关，与耳机型号无关，模块本身无需单独安装 GMS 组件。

| 系统 | Fast Pair 弹窗 | 说明 |
|---|---|---|
| 类原生 / 原生（完整 GMS） | 功能完全正常 | 已实测 |
| ColorOS（OPPO / realme / 一加） | 需额外模块 | 搭配 oplus-cn2global + Luckytool 后可用 |
| 其他系统 | 理论支持 | 支持完整 GMS 即支持，待实机验证 |

## 说明

- 本项目应用代码**部分使用 AI 辅助生成**，已人工审查与实机验证，但仍可能存在逻辑错误或兼容性问题，请谨慎使用，风险自负
- 项目借助逆向 Moondrop App 私有接口实现，**仅供学习研究使用**，不含 Moondrop App 任何代码 / 资源 / 反编译产物，请勿用于商业用途
