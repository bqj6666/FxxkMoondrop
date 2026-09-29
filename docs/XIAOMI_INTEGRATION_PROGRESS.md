# 小米 HyperOS 接入 · 进度档案

> **本文件是防上下文丢失的实时落盘点。每完成一个可回滚的步骤就更新一次。**
> 恢复工作时先读本文件，再读 `docs/superpowers/specs/` 下的设计文档。

最后更新：2026-09-29（会话开始时建立）

---

## 阶段总览

| 期 | 内容 | 状态 |
|---|---|---|
| **P0** | DexKit 升级 + `XiaomiProbe` 检测门禁 + 设置页门禁 + DexKit 定位表 | 🔄 进行中 |
| **P1** | Device ID 映射 + Miuix 轨骨架 + 主题切换 | ⏳ 未开始 |
| **P2** | 系统入口 hook（设备中心卡 / MiLink / 系统蓝牙页） | ⏳ 未开始 |
| **P3** | 焦点岛实装（`focus-api:1.4`） | ⏳ 未开始 |

---

## 已定决策（不可反复）

1. **协议层 L1 完全复用，一行不改** —— `GaiaBleClient` / `GaiaCommands` / `GaiaRfcommTransport` / `DeviceMatcher`
2. **方案 1** —— 引入 Compose + Miuix，主题双轨（Material You / HyperOS）
3. **型号 → HyperOS Device ID 动态映射**（用户选 2.B），不是固定 `01010607`
4. **参考项目只读不抄** —— 用我们自己的 DexKit + API 102 热重载实现
5. **检测门禁三级判定，全部不依赖 root**（root 只是运行保证，不是检测手段）
6. **分期交付**，每期可独立验证
7. **破坏性零容忍** —— 非小米设备上一个字节都不执行；映射失败只降级，不影响核心功能

## 环境事实（实测，勿再重复探测）

```
我们的技术栈：
  Compose        无（纯 View + Material 1.14）
  minSdk         26
  compileSdk     35  targetSdk 36
  Xposed API     io.github.libxposed:api:102.0.0  ✅ 已在用
  热重载          onHotReloading / onHotReloaded  ✅ 已有清理逻辑
  DexKit         2.2.0 → 2.3.0（本轮升级中）
  root 探测      EnvProbe.isRooted()  ✅ 已就绪
  源码位置        仓库根 src/（不是 app/src），sourceSets 里 java.srcDirs("../src")

参考项目（只读）：
  PuddingPods    源码私有，只有 PUDDING_ADAPTATION.md 文档
  HyperPods      公开 3272 行，用 YukiHookAPI（我们是裸 Xposed，不能抄写法）
  OppoPods       公开 13291 行，Focus Island 实现最完整
  Miuix          top.yukonga.miuix.kmp 0.9.2
  focus-api      com.xzakota.hyper.notification:focus-api:1.4

HyperOS hook 目标类（来自 HyperPods/OppoPods 实测）：
  com.xiaomi.bluetooth
  com.milink.service
  miui.systemui.devicecenter.devices.DeviceInfoWrapper   ← 设备中心卡片
  miui.systemui.controlcenter.panel.main.MainPanelController
  com.android.bluetooth.ble.app.MiuiBluetoothNotification
  MiuiHeadsetActivity                                     ← 高级耳机页
  com.android.settings.bluetooth.BluetoothDeviceDetailsFragment

PUDDING 协议（来自 PuddingPods 文档，可信）：
  Device ID    01010607
  传输         RFCOMM/SPP  00001101-0000-1000-8000-00805f9b34fb
  ANC          TX 00 1D 40 04 <mode>   关闭00/自适应01/通透02/抗风03/基础04
  电量         TX 00 1D 1A 00 / 00 1D 1A 01 01 02   回包 00 1D 1B（类型1左2右3盒）
  增益         TX 00 1D 1E 01 查 / 00 1D 1E 02 <0|1|2>
  指示灯       TX 00 1D 26 01 查 / 00 1D 26 02 <0|1>
```

---

## 日志

### 2026-09-29 · 会话开始

**目标**：接入小米 HyperOS 系统集成

**探索结论**：
- PuddingPods 源码私有；可读的是 HyperPods + OppoPods
- 我们已有 `GaiaRfcommTransport`，与 PuddingPods 同为 RFCOMM 路线 ✅
- 我们已有 API 102 热重载 + DexKit 2.2.0，是相对参考项目的核心优势
- 阻塞点：我们零 Compose，Miuix 必须靠 Compose

**用户决策**：
- GAIA 层完全用我们的
- 方案 1（Compose + Miuix 双轨主题）
- 不抄，用我们自己的 DexKit + API 102 方案
- Device ID 动态映射
- 分期做，实时保存进度
- 先升级 DexKit

**本次动作**：
- DexKit 2.2.0 → 2.3.0（2 天前发布）
