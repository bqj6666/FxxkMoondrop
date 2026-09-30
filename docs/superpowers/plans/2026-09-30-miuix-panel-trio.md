# Miuix 面板三件套实现计划 · 概览 / 设置 / 导航栏

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Miuix 轨补齐为完整面板 —— 概览页、设置页、底部导航栏，视觉对齐 SonyPods（信息层级）与 OppoPods（Miuix 官方组件用法）。

**Architecture:** Miuix 模式下导航栏由 Material 的 `BottomNavigationView`（View 树）换成 Miuix 官方 `NavigationBar`（Compose），挂进 `MiuixSurface` 的 `Scaffold.bottomBar`。三页内容全部用 Miuix 官方组件，不自绘像素。Material 轨一行不改。

**Tech Stack:** Kotlin · Compose (BOM 2026.05.01) · Miuix 0.9.4 · Material3 View 组件（仅 Material 轨）

**Spec:** `docs/superpowers/specs/2026-09-30-miuix-panel-trio-design.md`

## 任务与 spec 决策对应

| Task | 对应 spec | 交付物 |
|---|---|---|
| Task 0 | 验证标准·备份清理 | src/ 树干净 |
| Task 1 | **D2** + **D3** | 整卡按下高亮 + 下拉去卡内高亮 |
| Task 2 | **D1** | Miuix 底部导航栏 |
| Task 3 | 概览页设计 | 概览页 |
| Task 4 | 设置页设计 | 设置页三组 |
| Task 5 | 关于页设计 | 关于页 |
| Task 6 | 验证标准 + 风险表 | 真机验证 + 文档 |

## Global Constraints

以下每条都直接抄自 spec，违反即回退：

1. `GaiaBleClient` / `GaiaCommands` / `GaiaRfcommTransport` / `DeviceMatcher` / `GaiaPacketHandler` **一行不改**
2. Material 轨（`M3Ui.kt` / `OverviewFragment.kt` / `SettingsFragment.kt` / `AboutFragment.kt`）**一行不改**
3. 主题切换仍走 `applyStyleSwitch()`，**禁止 `recreate()`**
4. `MiuixHostFragment` 保持**无参构造**（对应现有防崩溃单测）
5. 单测基线 **135 项不得减少**
6. **不自绘像素**：能用 Miuix 官方组件的都用官方组件
7. 圆角统一 **16dp**（= 官方 `CardDefaults.CornerRadius`）
8. 构建：`./g9.sh testDebugUnitTest` / `./g9.sh :app:assembleRelease -PfxxkKeypass=fxxk2026`
9. 装机：`pm install -r /data/local/tmp/eta/FxxkMoondrop-repo/app/build/outputs/apk/release/app-release.apk`
10. **未获用户明确同意不得 push**

## 已确认的 Miuix 官方 API（实现时直接用，不要重新发明）

| API | 签名要点 | 来源 |
|---|---|---|
| `Scaffold` | `topBar` / `bottomBar` / `popupHost` / `contentWindowInsets` | `basic/Scaffold.kt:79` |
| `NavigationBar` | `color` / `showDivider` / `mode: NavigationBarDisplayMode` / content 是 `RowScope.() -> Unit` | `basic/NavigationBar.kt:74` |
| `RowScope.NavigationBarItem` | `selected` / `onClick` / `icon: ImageVector` / `label` / `colors` | `basic/NavigationBar.kt:145` |
| `OverlayDropdownPreference` | `title` / `items` / `selectedIndex` / `summary` / `onSelectedIndexChange`；`showValue` 默认 true | `preference/OverlayDropdownPreference.kt:58` |
| `Card` | `cornerRadius` 默认 16dp | `basic/Card.kt:52,190` |
| `androidx.compose.foundation.LocalIndication` | **公开 API**，Miuix 在 `MiuixTheme.kt:36` provide | `theme/MiuixTheme.kt:36` |

> ⚠️ `LocalIndication` 坑：旧注释曾写「不可用，那是 material3 的 internal API」——**错**。
> Miuix provide 的是 foundation 版，公开可注入。这是去掉卡内高亮的唯一入口。

---

### Task 0: 清理 .bak 备份文件

**Files:**
- 移动: `src/com/fxxkmoondrop/secret/hook/FastPairHookEntry.kt.bak_*`（7 个）
- 移动: `src/com/fxxkmoondrop/secret/hook/HearableControlHook.kt.bak_*`（2 个）
- 移动: `src/com/fxxkmoondrop/secret/hook/FastPairHookEntry.kt.bak_lang`（1 个）

**Interfaces:**
- Consumes: 无
- Produces: 干净的 `src/` 树（仅 `.kt`/`.java`）

- [ ] **Step 1: 确认这 10 个文件确实不被编译**

```bash
cd /workspace/FxxkMoondrop-repo
find src/ -type f ! -name '*.kt' ! -name '*.java'
```

Expected: 只列出 10 个 `.bak*` 文件，无其他

- [ ] **Step 2: 建归档目录并移动**

```bash
cd /workspace/FxxkMoondrop-repo
mkdir -p backup/bak_20260930
mv src/com/fxxkmoondrop/secret/hook/*.bak* backup/bak_20260930/
ls backup/bak_20260930/ | wc -l
```

Expected: `10`

- [ ] **Step 3: 确认源码树干净且构建不受影响**

```bash
cd /workspace/FxxkMoondrop-repo
find src/ -type f ! -name '*.kt' ! -name '*.java' | wc -l
./g9.sh :app:compileDebugKotlin 2>&1 | grep -E '^e:|BUILD'
```

Expected: `0` 然后 `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
cd /workspace/FxxkMoondrop-repo
git add -A backup/bak_20260930 src/
git commit -m "chore: 移出 10 个 .bak 备份文件（752K）到 backup/bak_20260930"
```

---

### Task 1: MiuixPressableCard —— 整卡片按下高亮

**Files:**
- Create: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixPressableCard.kt`
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixComponents.kt`（`MiuixDropdownRow`）
- Test: `app/src/test/.../MiuixSpecTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  ```kotlin
  @Composable
  internal fun MiuixPressableCard(
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,
      content: @Composable ColumnScope.() -> Unit,
  )
  internal object MiuixPressableCard { val RADIUS: Dp = 16.dp }
  ```

- [ ] **Step 1: 写失败的测试**

在 `MiuixSpecTest.kt` 追加：

```kotlin
@Test
fun `PressableCard 圆角对齐官方 16dp`() {
    // 官方 CardDefaults.CornerRadius = 16.dp (Card.kt:190)
    // ListPopup 弹层圆角同为 16.dp (ListPopup.kt:604)
    // MiuixSpec.CARD_RADIUS(20dp) 只用于卡片容器，两者用途不同
    assertEquals(20f, MiuixSpec.CARD_RADIUS.value, 0.01f)
    assertEquals(16f, MiuixPressableCard.RADIUS.value, 0.01f)
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:testDebugUnitTest --tests '*MiuixSpecTest*' 2>&1 | grep -E 'FAILED|BUILD|error'
```

Expected: FAIL（`MiuixPressableCard` 未定义）

- [ ] **Step 3: 写 MiuixPressableCard.kt**

```kotlin
package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.pressable

/**
 * 整卡片按下高亮 —— 对齐 SonyPods「点整行 → 整卡变深色」。
 *
 * 原 MiuixCard 只画容器，高亮由内部 BasicComponent 画，
 * 于是出现「卡片内一圈高亮」，与参考项目不符。
 * 做法：卡片自己监听按压态，整卡背景色动画过渡。
 *
 * 圆角 16dp = 官方 CardDefaults.CornerRadius (Card.kt:190)。
 */
@Composable
internal fun MiuixPressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scheme = MiuixTheme.colorScheme

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> scheme.surfaceContainer
            pressed -> scheme.surfaceContainerHigh
            else -> scheme.surfaceContainer
        },
        label = "miuixCardPressBg",
    )

    Card(
        modifier = modifier.pressable(
            interactionSource = interactionSource,
            enabled = enabled,
            pressFeedbackType = PressFeedbackType.Sink,
            onClick = onClick,
        ),
        cornerRadius = MiuixPressableCard.RADIUS,
        color = bg,
    ) {
        content()
    }
}

/** 与 Miuix 官方 CardDefaults.CornerRadius 一致。 */
internal object MiuixPressableCard {
    val RADIUS: Dp = 16.dp
}
```

- [ ] **Step 4: 运行确认通过**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:testDebugUnitTest --tests '*MiuixSpecTest*' 2>&1 | grep -E 'FAILED|BUILD'
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 下拉行去卡内高亮**

改 `MiuixComponents.kt` 的 `MiuixDropdownRow`：外层换 `MiuixPressableCard`，
并在 `OverlayDropdownPreference` 外包 `CompositionLocalProvider(LocalIndication provides null)`。

- [ ] **Step 6: 全量单测**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
```

Expected: BUILD SUCCESSFUL，测试数 ≥ 135

- [ ] **Step 7: 提交**

```bash
cd /workspace/FxxkMoondrop-repo
git add -A src/
git commit -m "feat(miuix): 整卡片按下高亮 PressableCard + 下拉行去卡内高亮"
```

---

### Task 2: MiuixNavBar —— 底部导航栏

**Files:**
- Create: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixNavBar.kt`
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixHostFragment.kt`
- Modify: `src/com/fxxkmoondrop/secret/MainActivity.kt`

**Interfaces:**
- Consumes: Task 1 的 `MiuixSurface`（已有）、`MiuixHostFragment.Screen`
- Produces:
  ```kotlin
  @Composable
  internal fun MiuixNavBar(
      selected: Int,          // 1=概览 2=设置 3=关于
      onSelect: (Int) -> Unit,
  )
  internal interface MiuixNavHost { fun onMiuixTab(id: Int) }
  ```

- [ ] **Step 1: 写 MiuixNavBar.kt**

用 Miuix 官方组件，**不自绘**：

```kotlin
package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版底部导航 —— 用官方 NavigationBar + NavigationBarItem，
 * 视觉由 Miuix 保证（超椭圆药丸 + Sink 形变），不自绘。
 *
 * 参考项目一致点：OppoPods 与 SonyPods 均为「图标 + 文字」双行，
 * 选中项图标与文字同染 primary。
 */
@Composable
internal fun MiuixNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar(
        color = MiuixTheme.colorScheme.surface,
        showDivider = true,
    ) {
        NavItem(selected, 1, MiuixIcons.Basic.Home, "概览", onSelect)
        NavItem(selected, 2, MiuixIcons.Basic.Settings, "设置", onSelect)
        NavItem(selected, 3, MiuixIcons.Basic.Info, "关于", onSelect)
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Int, id: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, onSelect: (Int) -> Unit,
) {
    NavigationBarItem(
        selected = selected == id,
        onClick = { onSelect(id) },
        icon = icon,
        label = label,
    )
}

/** MiuixHostFragment 通过它把 tab 事件送回 MainActivity。 */
internal interface MiuixNavHost {
    fun onMiuixTab(id: Int)
}
```

- [ ] **Step 2: 核对 Miuix 图标名**

```bash
cd /workspace/ref_miuix
grep -rn 'val Home\|val Settings\|val Info' miuix-icon/src/commonMain/kotlin/top/yukonga/miuix/kmp/icon/MiuixIcons.kt | head
```

若图标名不同，改用实际存在的（**不要自绘 SVG**）

- [ ] **Step 3: MiuixHostFragment 挂上导航栏**

在 `MiuixHostFragment` 的 Compose 内容里，把 `MiuixNavBar` 放进 `MiuixPage` 的 Scaffold `bottomBar`；
`onSelect` 回调走 `MiuixNavHost` 接口，Fragment 在 `onAttach` 时把 Activity 强转并保存。

- [ ] **Step 4: MainActivity 实现 MiuixNavHost**

```kotlin
// MainActivity 增加
override fun onMiuixTab(id: Int) { showTab(id) }
```

并让 `MainActivity` 实现 `MiuixNavHost`。Material 模式的 `BottomNavigationView` 保持原样。

- [ ] **Step 5: 编译 + 单测**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:compileDebugKotlin 2>&1 | grep -E '^e:|BUILD'
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
```

- [ ] **Step 6: 提交**

```bash
cd /workspace/FxxkMoondrop-repo
git add -A src/
git commit -m "feat(miuix): 底部导航栏换官方 NavigationBar"
```

---

### Task 3: 概览页

**Files:**
- Create: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixOverview.kt`
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixScreens.kt`（`MiuixOverviewScreen` 委托过去）

**Interfaces:**
- Consumes: Task 1 的 `MiuixPressableCard`；素材 `assets/ga2_icon.png`
- Produces:
  ```kotlin
  @Composable
  internal fun MiuixOverviewScreen()
  @Composable
  internal fun MiuixActionButton(
      icon: ImageVector, label: String,
      selected: Boolean, enabled: Boolean, onClick: () -> Unit,
  )
  ```

- [ ] **Step 1: 确认素材可读**

```bash
cd /workspace/FxxkMoondrop-repo
python3 -c "
import struct;d=open('app/src/main/assets/ga2_icon.png','rb').read()
print(struct.unpack('>II', d[16:24]))"
```

Expected: `(512, 512)`

- [ ] **Step 2: 写 MiuixOverview.kt**

结构：`小标签 → 大标题 → 状态行 → 耳机图(160dp) → 降噪三态 → 电量/音量/多点`

关键点：
- 耳机图用 `Image(painterResource(R.drawable.miuiix_earphone_case))`，
  **需先把 `ga2_icon.png` 复制到 `res/drawable-nodpi/`**（assets 不能直接 painterResource）
- 占位按钮：状态显示 `—`，`onClick` 弹提示「GAIA 数据接入中」，**不碰协议层**
- 降噪三态用 `MiuixActionButton(selected = mode == X)`

- [ ] **Step 3: 复制素材到 res**

```bash
cd /workspace/FxxkMoondrop-repo
mkdir -p app/src/main/res/drawable-nodpi
cp app/src/main/assets/ga2_icon.png app/src/main/res/drawable-nodpi/miuiix_earphone_case.png
ls -la app/src/main/res/drawable-nodpi/
```

- [ ] **Step 4: 委托 MiuixScreens**

`MiuixScreens.kt` 的 `MiuixOverviewScreen()` 改为调用 `MiuixOverview()`，删除旧的占位内容。

- [ ] **Step 5: 编译 + 单测 + 提交**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:compileDebugKotlin 2>&1 | grep -E '^e:|BUILD'
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
git add -A src/ app/src/main/res/
git commit -m "feat(miuix): 概览页（耳机盒渲染图 + 占位功能按钮）"
```

---

### Task 4: 设置页重排

**Files:**
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixScreens.kt`（`MiuixSettingsScreen`）
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixComponents.kt`（`MiuixListRow` / `MiuixSwitchRow` 接 PressableCard）

**Interfaces:**
- Consumes: Task 1 的 `MiuixPressableCard`
- Produces: 无新公开接口

- [ ] **Step 1: 三组结构**

| 组 | 行 |
|---|---|
| 外观 | 界面风格（下拉）· 种子颜色 · 亮暗模式 |
| 协议 | GAIA 开关 · RFCOMM 开关 |
| 维护 | 清理缓存 · 日志 |

- [ ] **Step 2: 每组用 MiuixPressableCard 包裹**

组内首行绑 `onClick`（下拉/开关由行内控件处理，其余行触发对应动作）；
分隔线用现有 `MiuixDivider`。

- [ ] **Step 3: 编译 + 单测 + 提交**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:compileDebugKotlin 2>&1 | grep -E '^e:|BUILD'
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
git add -A src/
git commit -m "feat(miuix): 设置页按 SonyPods 层级重排为三组"
```

---

### Task 5: 关于页

**Files:**
- Create: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixAbout.kt`
- Modify: `src/com/fxxkmoondrop/secret/ui/miuix/MiuixScreens.kt`

**Interfaces:**
- Consumes: Task 3 的耳机图资源 `R.drawable.miuiix_earphone_case`
- Produces: `internal fun MiuixAboutScreen()`

- [ ] **Step 1: 内容**

顶部耳机图（120dp）→ 版本/构建/模块版本 → LSPosed 激活链接、检查更新 → 版权与开源许可。

版本信息从现有 `AboutFragment` 的取值方式读取（**只读，不改它**）。

- [ ] **Step 2: 编译 + 单测 + 提交**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh :app:compileDebugKotlin 2>&1 | grep -E '^e:|BUILD'
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
git add -A src/
git commit -m "feat(miuix): 关于页"
```

---

### Task 6: 真机验证与文档更新

**Files:**
- Modify: `docs/XIAOMI_INTEGRATION_PROGRESS.md`
- Modify: `docs/superpowers/specs/2026-09-30-miuix-panel-trio-design.md`（状态改「已实现」）

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 无代码

- [ ] **Step 1: 全量单测 + release 构建**

```bash
cd /workspace/FxxkMoondrop-repo
./g9.sh testDebugUnitTest 2>&1 | grep -E 'BUILD'
N=0; for f in app/build/test-results/testDebugUnitTest/*.xml; do
  n=$(grep -o 'tests="[0-9]*"' "$f"|head -1|grep -o '[0-9]*'); N=$((N+n)); done; echo "测试数: $N"
./g9.sh :app:assembleRelease -PfxxkKeypass=fxxk2026 2>&1 | grep -E 'BUILD'
```

Expected: 测试数 **≥ 135**，BUILD SUCCESSFUL

- [ ] **Step 2: APK 四项完整性检查**

```bash
cd /workspace/FxxkMoondrop-repo
APK=app/build/outputs/apk/release/app-release.apk
unzip -l "$APK" | grep -c 'AndroidManifest.xml'
unzip -l "$APK" | grep -c 'res/'
unzip -l "$APK" | grep -E 'META-INF/xposed/'
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 /workspace/sdk/build-tools/36.0.0/apksigner verify --print-certs "$APK" 2>&1 | grep 'certificate DN'
```

Expected: `1` / 非 0 / 三个 xposed 文件 / `CN=FxxkMoondrop`

- [ ] **Step 3: 装机**

```bash
pm install -r /data/local/tmp/eta/FxxkMoondrop-repo/app/build/outputs/apk/release/app-release.apk
```

- [ ] **Step 4: 真机逐项验证**

- [ ] 设置页 → 界面风格切到 Miuix → 三页 + 导航栏渲染正常
- [ ] 整卡按下高亮可见，**卡内无第二层高亮**
- [ ] 下拉：打开 / 点外部关 / 返回键关 / 选中后持久化
- [ ] 主题即时切换、亮暗切换零 FATAL

```bash
logcat -c; # 操作后
logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION'
```

- [ ] **Step 5: 让用户视觉确认**

按用户要求（3.2.13 起）**不由助手截图判定视觉**，请用户截图确认。

- [ ] **Step 6: 更新进度文档**

在 `docs/XIAOMI_INTEGRATION_PROGRESS.md` 记录：
- 面板三件套已完成
- 导航栏已从 View 迁到 Compose（Material 轨不变）
- 整卡按下高亮实现方式
- 备份文件已清理
- GAIA 接入留待下一轮

- [ ] **Step 7: 提交（不 push）**

```bash
cd /workspace/FxxkMoondrop-repo
git add -A docs/
git commit -m "docs: 面板三件套完成记录"
git rev-list --count origin/main..HEAD
```

Expected: 领先数 ≥ 24。**不 push**（需用户明确同意）
