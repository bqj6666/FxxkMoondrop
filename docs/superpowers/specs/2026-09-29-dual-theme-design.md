# 双主题架构设计 · Material ⇄ Miuix

日期：2026-09-29 · 状态：待用户批准

## 目标

设置页、概览页、弹窗**全部**支持两套主题，用户可在设置页切换：

- **Material You** — 现有实现，**一行不改**
- **HyperOS (Miuix)** — 新写，Compose + Miuix

两套并存长期维护，不做替换。

## 核心原则

1. **Material 轨零改动**。现有 `M3Ui.kt`(891) / `SettingsFragment.kt`(1168) /
   `OverviewFragment.kt`(1834) / `ControlPanel.kt`(453) 完全不动。
2. **分派点唯一**：`MainActivity.showTab()` 与弹窗创建处各一处 `when`。
3. **Miuix 轨不碰 L1 协议层**。GAIA / 电量 / ANC 数据完全复用现有链路。
4. **Miuix 轨可整体删除**。全部代码集中在 `ui/miuix/` 目录，删目录即回退。

## 目录结构

```
src/com/fxxkmoondrop/secret/
├── M3Ui.kt                 ← 现有，不动
├── SettingsFragment.kt     ← 现有，不动
├── OverviewFragment.kt     ← 现有，不动
├── ControlPanel.kt         ← 现有，不动
├── UiStyle.kt              ← 现有（分派依据）
├── MiuixSurface.kt         ← 现有（P2 已完成）
└── ui/miuix/               ← 新增，Miuix 轨全部代码
    ├── MiuixTheme.kt       ← 主题与取色
    ├── MiuixComponents.kt  ← 卡片/开关/下拉等控件
    ├── MiuixScreen.kt      ← 通用页面骨架
    ├── MiuixOverviewScreen.kt
    ├── MiuixSettingsScreen.kt
    ├── MiuixAboutScreen.kt
    └── MiuixPanel.kt       ← 弹窗版
```

## 分派实现

`MainActivity.showTab()`：

```kotlin
val f: Fragment = when (id) {
    2 -> if (MiuixSurface.enabled(this)) MiuixHostFragment(Screen.SETTINGS)
         else SettingsFragment()
    3 -> if (MiuixSurface.enabled(this)) MiuixHostFragment(Screen.ABOUT)
         else AboutFragment()
    else -> if (MiuixSurface.enabled(this)) MiuixHostFragment(Screen.OVERVIEW)
            else OverviewFragment()
}
```

`MiuixHostFragment` 是唯一新增的 Fragment，内部用
`ComposeView` 承载对应的 `@Composable`。

**为什么不直接让 `@Composable` 继承 Fragment**：Compose 与 Fragment 生命周期
不同步，宿主 Fragment 只需提供 `ComposeView` + `ViewCompositionStrategy`，
生命周期交给 Compose 自己管理。

## 弹窗

现有弹窗在 GMS/settings 进程里由 hook 创建（`ControlPanel`）。Miuix 版同样
需要在**宿主进程**里渲染 —— 意味着 Miuix 轨在 hook 场景下要在
`com.android.settings` / GMS 进程加载 Compose。

**这是最大的技术风险**，处理方式：
- Compose 在宿主进程可用（依赖已在 APK 内，无进程限制）
- 但 `MiuixHostFragment` 走的是 Activity 容器，弹窗场景需改用
  `Dialog` + `ComposeView`，不经过 Fragment
- 因此弹窗的分派是**独立**的一条路径，不复用 Fragment 机制

## 数据复用

Miuix 轨不重新实现任何数据获取，直接调用现有静态入口：

| 界面 | 复用 |
|---|---|
| 概览 | `GaiaBleClient` 状态、`DetailRows.visibility`、`AncProfileLib` |
| 设置 | `getSP()` 同一份 SP、`EnvProbe`、`UpdateChecker` |
| 弹窗 | `ControlPanel` 的同一批回调与 `AncBridge` |

保证两套主题显示的数据完全一致，不会出现「Material 显示 80%、Miuix 显示 60%」。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| Compose 在 GMS/settings 进程崩溃 | 每处渲染包 try/catch，失败回落 Material 轨 |
| 两套主题行为漂移 | 数据层完全共享，只有渲染不同 |
| 弹窗 Miuix 化失败 | 先做 Activity 三个页面，弹窗作为独立子步骤 |
| 体积膨胀 | 已开 R8，实测 4.0M |

## 分步

1. **MiuixComponents** — 基础控件（卡片/开关/下拉/列表行）
2. **MiuixScreen + MiuixHostFragment** — 骨架与宿主
3. **MiuixOverviewScreen** — 概览页
4. **MiuixAboutScreen** — 关于页（最简单，先做）
5. **MiuixSettingsScreen** — 设置页（最复杂，最后做）
6. **MiuixPanel** — 弹窗（独立路径）

每步都保持 Material 轨可用、可随时切回。
