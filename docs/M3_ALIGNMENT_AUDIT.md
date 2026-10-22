# Material 3 对齐审计

- 日期：2026-10-22
- 范围：`src/` 下 Material 轨的全部界面代码（`M3Ui.kt` / `OverviewFragment` / `SettingsFragment` / `AboutFragment` / `PermissionActivity` / `OnboardingActivity` / `EqActivity` / `ControlPanel` / `MainActivity`），以及 `app/src/main/res` 下的相关资源
- 目标：所有元素与 Google 官方 Material 3 标准对齐，不保留自绘组件或不符合规范的元素

## 一、结论摘要

**已经合规的部分（不必动）**

- 底部导航用官方 `BottomNavigationView`（`MainActivity` 的 `navBar`）
- 弹窗用官方 `MaterialAlertDialogBuilder`（10 处）与 `MaterialCardView` 版对话框（`M3Ui.applyCardLook`）
- 控件层大量使用官方组件：`MaterialButton` 21、`MaterialSwitch` 16、`Chip` 12、`MaterialToolbar` 6、`LinearProgressIndicator` 4、`CircularProgressIndicator` 3
- 圆角常量已对齐规范：`RADIUS_CARD = 12`、`RADIUS_DIALOG = 28`（`M3Ui.kt`），并有 `Material3SpecTest` 钉住

**需要整改的部分**

偏离集中在**卡片与交互状态层**这三类，而不是散落的控件：

| 类别 | 规模 | 说明 |
|---|---|---|
| 卡片背景手绘 | `GradientDrawable` 70 处，核心入口 `M3Ui.cardBg()` + `groupCard()` | 不是 `MaterialCardView`，缺 M3 的 shapeAppearance / 描边 / 状态层 / tonal elevation |
| 水波纹手写 | `RippleDrawable` 24 处 | 未使用 `?attr/selectableItemBackground*` 状态层 |
| 行分隔线自绘 | `groupCard` 内 `View` + `hairline()`（1 物理像素） | M3 Divider 规范为 1dp |
| 裸 sp 字号 | 2 处（头部 28sp / 22sp） | 应引用 `textAppearance*` 主题属性 |
| 裸像素描边 | 均衡器曲线视图 1f / 3f | 应为 dp |
| 自绘 View | 2 个（引导页指示点、均衡器曲线） | M3 无对应组件，保留但需 token 化 |

## 二、P0 结构性偏离

### P0-1 分组卡片不是 MaterialCardView

- **现状**：`M3Ui.cardBg()` 产出 `GradientDrawable`（设圆角 + 描边），`groupCard()` 把它直接设为 `LinearLayout` 的背景。同一套外观被 `SettingsFragment`、`OverviewFragment`、`PermissionActivity`、`OnboardingActivity`、`AboutFragment`、`ControlPanel` 复用，合计 70 处 `GradientDrawable` 用法。
- **规范**：M3 的 Card 必须由 `MaterialCardView` 承载 —— `shapeAppearance` 定 12dp 圆角、`strokeWidth` 1dp + `strokeColor=outlineVariant`、内容用卡片自带的 tonal elevation 与状态层；容器不应自己画背景。
- **佐证偏离是局部的**：同一文件的**弹窗**已经用了 `MaterialCardView`（`applyCardLook`），说明组件早已可用，只是设置页卡片没跟上。
- **改法**：`groupCard()` 返回 `MaterialCardView`，内部加一个纵向 `LinearLayout` 作为行容器；圆角走 `shapeAppearanceOverlay` 而不是 `setRadius()`；描边走 `strokeWidth/strokeColor`；`cardBg()` 随之废弃。
- **风险**：`groupCard` 是多页面共用入口，改一处即可覆盖；但各页面按现有内边距假设排版，需回归边距与行高。

### P0-2 手写 RippleDrawable

- **现状**：`RippleDrawable` 24 处（`OverviewFragment` 12、`ControlPanel` 5、`PermissionActivity` 3、`FastPairHookEntry` 2、`M3Ui` 1、`SettingsFragment` 1）。
- **规范**：交互反馈统一走状态层 —— 矩形用 `?attr/selectableItemBackground`，圆形（图标按钮 / 药丸）用 `?attr/selectableItemBackgroundBorderless`；需要圆角裁剪时由父容器 `clipToOutline` + `shapeAppearance` 提供，而不是自己拼 `RippleDrawable`。
- **改法**：全部替换为上述属性；若某处确需自定义圆角，保留 `clipToOutline` 但 ripple 仍取属性。

### P0-3 行分隔线自绘，且单位不合约

- **现状**：`groupCard()` 内用 `View` + `hairline()` 画组内分隔线（1 **物理像素**）。
- **规范**：M3 Divider 为 **1dp**，颜色 `outlineVariant`；MDC 提供 `MaterialDivider`。
- **改法**：换 `MaterialDivider`（或保留 View 但改为 1dp + `outlineVariant`）。

## 三、P1 视觉 token

- **P1-1 裸 sp 字号**：`M3Ui.HEADER_EXPANDED_SP = 28f`、`HEADER_COLLAPSED_SP = 22f` 直接 `setTextSize`。数值本身对应 `headlineMedium` / `titleLarge`，但应以 `textAppearance` 主题属性表达，避免主题或无障碍字号变化时失配。
- **P1-2 硬编码颜色**：UI 侧真实命中为 `DeviceNotif` 9、`ControlPanel` 7、`FastPairHookEntry` 6、`ThemeUtil` 6 处 `0xFF` 常量。应改取主题角色（`colorSurface*` / `colorOnSurfaceVariant` 等）或 `colors.xml` 中已建立的 token。
- **P1-3 裸像素描边**：`EqActivity.EqCurveView` 的 `gridPaint.strokeWidth = 1f`、`linePaint.strokeWidth = 3f` 是物理像素，不是 dp —— 高密度屏上会明显偏细/偏粗。应改为 `dp()` 换算。

## 四、P2 保留的自绘视图（M3 无对应组件）

- **P2-1 `OnboardingActivity.DotsView`**（页面指示点）：M3 未提供点状指示器组件。保留自绘，但 8dp 点径 / 28dp 活动胶囊 / 10dp 间距与颜色应来自 token（`primary` / `onSurfaceVariant`），不硬编码。
- **P2-2 `EqActivity.EqCurveView`**（频响曲线）：数据可视化，M3 无对应组件。保留自绘，除 P1-3 的描边单位外，网格与曲线颜色应取主题角色。

## 五、不构成偏离（避免误改）

- `Gaia*` 系列文件里的 `0xFF`（`GaiaPacketHandler` 14、`GaiaCommands` 10、`GaiaRfcommFramer` 9 等）是**协议字节掩码/字面量**，与主题色无关，不要动。
- `listRow` / `switchRow` / `navRow` 这类手工 `LinearLayout` 行：MDC 并未提供官方 ListItem 组件，手工行符合规范的前提是**度量正确**（单行 56dp、双行 72dp、水平内边距 16dp、状态层正确），而不是必须换成某个组件。

## 六、建议顺序

1. **P0-3 分隔线**（改动最小，先把验证链路跑通）
2. **P0-1 卡片**（影响面最大，但单点入口）
3. **P0-2 状态层**
4. **P1 token 化**
5. **P2 自绘视图 token 化**

每一步的验收都应包含：`Material3SpecTest` 通过、真机目视一致、以及 `GradientDrawable` / `RippleDrawable` / 裸 sp / 裸 px 的计数单调下降（可在单测里加断言，防止回潮）。
