# Miuix 面板三件套设计 · 概览 / 设置 / 导航栏

日期：2026-09-30 · 状态：待用户批准
关联：`2026-09-29-dual-theme-design.md`（双主题骨架，本 spec 是它的续作）

## 目标

把 Miuix 轨从「只有设置页骨架」补齐为**完整可用的面板**，视觉参考两个真实产品：

- **SonyPods**（本机 `com.mercury.sonypods.noroot` v1.5.1 实测截图）— 信息层级与大留白
- **OppoPods**（`/workspace/ref_OppoPods`）— Miuix 官方组件调用方式

三块一起做：概览页、设置页、底部导航栏。

**明确不在本次范围**：GAIA 实时数据接入（用户已选 A，先做外观 + 占位）。协议层一行不改。

## 设计参考的实测事实

| 事实 | 来源 |
|---|---|
| SonyPods 卡片为 HyperOS 直角 + 大留白，顶部产品渲染图 | 本机截图 `sp2.png` / `sp3.png` |
| SonyPods 点整行 → **整卡深色高亮**，非卡内一圈 | 本机截图 `sp3.png` |
| Miuix `CardDefaults.CornerRadius = 16.dp` | `ref_miuix/.../basic/Card.kt:190` |
| Miuix 弹层圆角 16dp | `ref_miuix/.../basic/ListPopup.kt:604` |
| `BasicComponent` 的 `clickable` 不传 indication，自动取 `LocalIndication` | `ref_miuix/.../basic/Component.kt:151` |
| `MiuixIndication.draw()` 写死 `drawRect` | `ref_miuix/.../utils/MiuixIndication.kt:133` |
| OppoPods 官方写法：`Card { OverlayDropdownPreference(...) }` | `ref_OppoPods/.../ThemeSettingsPage.kt:51` |
| `OverlayDropdownPreference` 的 `showValue` 默认 `true` | `ref_miuix/.../preference/OverlayDropdownPreference.kt:58` |
| 库自带 `MiuixIcons.Basic.Check` 指示器，20dp | `ref_miuix/.../basic/Dropdown.kt:206` |
| 耳机盒渲染图 `ga2_icon.png` 512×512 白底 | `app/src/main/assets/` |

## 核心决策

### D1 · 导航栏跨 View/Compose 边界 —— 采用 Compose 方案

**问题**：现状导航栏是 Material 的 `BottomNavigationView`（View 树，`M3Ui.navBar()`），
与 Compose 页面是两个世界。

**方案 A（采纳）**：Miuix 模式下导航栏改为 Compose 组件，进 `MiuixSurface` 的 `Scaffold.bottomBar`；
Material 模式保持 `BottomNavigationView` 原样。

**理由**：改 View 导航栏的颜色只能「像」，改不出 Miuix 的超椭圆药丸指示器与 Sink 形变。
Material 轨一行不改，删掉 Miuix 分支即回退。

**代价**：`MainActivity` 多一处 `if`；主题切换时需重建导航栏。

### D2 · 整卡片按下高亮 —— 新增 `MiuixPressableCard`

**问题**：现状 `MiuixCard` 画容器，内部 `BasicComponent` 画高亮 → 出现「卡片内一圈」。

**方案**：新增 `MiuixPressableCard`，按下时**整张卡片**背景变 `surfaceContainerHigh`，
圆角 16dp（对齐官方 `CardDefaults.CornerRadius`），松手回弹。

用户已确认：**按下亮**，非常亮。

**实现**：`Modifier.pressable(PressFeedbackType.Sink)` + 内部 `MutableInteractionSource`
驱动背景色动画；卡片自身 `onClick` 消费事件，内部行不再各自 clickable（除下拉行，见 D3）。

### D3 · 下拉行去掉卡内高亮 —— 在子树内把 `LocalIndication` 置 null

`OverlayDropdownPreference` 内部的 `BasicComponent` 不可改，但其高亮来自
`LocalIndication.current`。做法：

```kotlin
CompositionLocalProvider(LocalIndication provides null) {
    OverlayDropdownPreference(...)
}
```

配合外层 `MiuixPressableCard` 自己画整卡高亮，达到 SonyPods 的「整卡变深色」观感。

> 注：全局已注入 `MiuixSquircleIndication`（3.2.13，修直角高亮），
> 但下拉行这棵子树单独置 null，避免与整卡高亮叠加成两层。

## 目录结构

```
src/com/fxxkmoondrop/secret/
├── ui/miuix/
│   ├── MiuixHostFragment.kt        ← 改：承载导航栏
│   ├── MiuixScreens.kt             ← 改：三页实现
│   ├── MiuixComponents.kt          ← 改：新增 PressableCard
│   ├── MiuixNavBar.kt              ← 新：Miuix 底部导航
│   ├── MiuixOverview.kt            ← 新：概览页
│   ├── MiuixAbout.kt               ← 新：关于页
│   └── MiuixSquircleIndication.kt  ← 已有，不动
├── MainActivity.kt                 ← 改：一处 if 决定导航栏走哪套
└── M3Ui.kt                        ← 不动
```

## 各页设计

### 导航栏（Miuix 版）

- 高度 80dp，图标 24dp，文字 12dp
- 选中：图标与文字染 `primary`，背后**超椭圆药丸**指示器
- 未选中：`onSurfaceVariantSummary`
- 按压：`Sink` 形变（轻微下沉）
- 三项：概览 / 设置 / 关于
- 挂在 `MiuixSurface` 的 Scaffold `bottomBar`

### 概览页

```
MOONDROP              ← 小标签，onSurfaceVariantSummary
Zen Pro               ← 大标题
● 已连接 · 电量 82%    ← 状态行

┌──────────────┐
│  ga2_icon    │  ← 160dp，assets 复用弹窗那张
└──────────────┘

降噪模式
[关闭] [降噪] [透传]   ← 圆形图标按钮，选中态 primary
[电量] [音量] [多点]   ← 本次占位，未接数据
```

**占位行为**：按钮可点、有形变，但点击不触发协议调用，状态显示「—」。
GAIA 接入留给下一轮，避免视觉未定就动协议层。

### 设置页

按 SonyPods 信息层级重排为三组：

| 组 | 行 |
|---|---|
| 外观 | 界面风格（下拉）· 种子颜色 · 亮暗模式 |
| 协议 | GAIA 开关 · RFCOMM 开关 |
| 维护 | 清理缓存 · 日志 |

- 每组一张 `MiuixCard`，行间细分隔线
- 行按下 → **整卡高亮**（D2）
- 下拉行按 D3 处理

### 关于页

- 顶部同款 `ga2_icon.png`（120dp）
- 版本 / 构建类型 / 模块版本 / 签名指纹
- LSPosed 激活链接、检查更新
- 版权与开源许可

## 不变量（必须保持）

1. `GaiaBleClient` / `GaiaCommands` / `GaiaRfcommTransport` / `DeviceMatcher` /
   `GaiaPacketHandler` **一行不改**
2. Material 轨（`M3Ui.kt` / 三个 Fragment）**一行不改**
3. 设置页 →「界面风格」切换仍走 `applyStyleSwitch()`，不 `recreate()`
4. `MiuixHostFragment` 保持无参构造（防恢复崩溃，对应现有单测）
5. 单测基线 **135 项不得减少**

## 验证标准

- `testDebugUnitTest` ≥ 135 项全过
- release 构建通过，APK 含 `AndroidManifest.xml` / 非空 `res/` /
  `META-INF/xposed/{java_init.list,module.prop,scope.list}` / `CN=FxxkMoondrop`
- 真机：切 Miuix → 三页 + 导航栏渲染正常
- 整卡按下高亮肉眼可见，且**卡内无第二层高亮**
- 下拉可开 / 点外部关 / 返回键关 / 选中后持久化
- 主题即时切换、亮暗切换均无崩溃
- 备份文件清理：`src/` 下 10 个 `.bak`（752K）移出源码目录

## 风险

| 风险 | 应对 |
|---|---|
| 导航栏移入 Compose 后，Material 切 Miuix 时导航栏重建丢状态 | 重建时从 `curTab` 恢复选中项 |
| `LocalIndication provides null` 可能影响同子树其他组件 | 仅包住下拉行，不扩大范围 |
| 占位按钮误导用户以为可用 | 明确显示「—」并加副标题说明 |
