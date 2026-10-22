# YukiHook 阶段 B′ 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把模块入口换成 YukiHook 单一入口（`META-INF/xposed` 由编译器生成），并让原有 4711 行 hook 逻辑在零改动的前提下继续工作。

**Architecture:** 新增 `YukiEntry : YukiHookXposedModule` 作为唯一入口，通过 `registerFrameworkEvents` 拿到 `XposedInterface`；原 `XposedEntry` 保留类名、去掉 `: XposedModule()` 继承，改为构造注入 `XposedInterface` 并提供 `hook()` 垫片，使 40 处 `hook()` 调用点一行不改。hook 类（`FastPairHookEntry` / `HearableControlHook`）只把 `XposedModule` 参数类型换成 `XposedInterface`。

**Tech Stack:** Kotlin 2.4.21 / KSP 2.3.12 / YukiHook 1.5.0（bom + core + runtime-libxposed + compiler）/ libxposed API 102 / AGP 9.4.1 / Gradle 9.6.0

**Spec:** `docs/superpowers/specs/2026-10-22-yukihook-entry-migration-design.md`

## Global Constraints

- `minSdk = 26`、`targetSdk = 36`、`compileSdk = 37` —— **本次一律不改**（提升到 API 31 是独立变更，见 spec 第十节）
- 不改任何 hook 逻辑与跨品牌门禁：`isOurSheetActivity` / `ProbeBudget` / `rejectedNames` / `KNOWN_KEYS` / `skipped_notice_ack` 出现次数必须与改动前一致
- 发布核验四项不变：`AndroidManifest.xml` / `res/` 非空 / `META-INF/xposed/{java_init.list,module.prop,scope.list}` / 签名 `CN=FxxkMoondrop`
- 本地构建只用仓库根 `./g9.sh`；完整 Release 构建走 CI
- 每个任务一个提交，提交信息用 `exp(yukihook):` / `fix(build):` 等既有前缀风格

---

### Task 1: 构建链升级与依赖接入

**Files:**
- Modify: `build.gradle.kts:1-13`
- Modify: `app/build.gradle.kts:160-175`（依赖块）

**Interfaces:**
- Produces: 可用的 `ksp()` 配置与 `com.highcapable.yukihookapi:*` 依赖，供 Task 2 的注解处理器生效

- [ ] **Step 1: 根构建脚本改 Kotlin 版本并加 KSP**

`build.gradle.kts` 中 `org.jetbrains.kotlin.android` 与 `org.jetbrains.kotlin.plugin.compose` 的版本 `2.4.10` 改为 `2.4.21`，并在 `plugins {}` 末尾追加：

```kotlin
    id("com.google.devtools.ksp") version "2.3.12" apply false
```

- [ ] **Step 2: app 模块加 KSP 插件与依赖**

`app/build.gradle.kts` 的 `plugins {}` 加 `id("com.google.devtools.ksp")`；依赖块把 `kotlin-stdlib` 版本改为 `2.4.21`，并追加：

```kotlin
    implementation(platform("com.highcapable.yukihookapi:yukihook-bom:1.5.0"))
    implementation("com.highcapable.yukihookapi:yukihook-core")
    implementation("com.highcapable.yukihookapi:yukihook-runtime-libxposed")
    // BOM 只约束 implementation 系配置，管不到 ksp；漏了这行 compiler 版本会解析为空
    ksp(platform("com.highcapable.yukihookapi:yukihook-bom:1.5.0"))
    ksp("com.highcapable.yukihookapi:yukihook-compiler")
```

- [ ] **Step 3: 本地快速编译检查**

Run: `./g9.sh :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`（此时还没有入口注解，编译器应无事可做）

- [ ] **Step 4: 提交**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "exp(yukihook): 构建链接入 KSP 2.3.12 与 YukiHook 1.5.0 依赖"
```

---

### Task 2: 新增 YukiHook 入口并把 XposedEntry 改为注入式

**Files:**
- Create: `src/com/fxxkmoondrop/secret/YukiEntry.kt`
- Modify: `src/com/fxxkmoondrop/secret/XposedEntry.kt:21`（类声明）、`:119`、`:123`、`:138`、`:154`（四个生命周期方法）

**Interfaces:**
- Consumes: Task 1 的注解处理器
- Produces: `XposedEntry(xposed: XposedInterface)` 构造函数；公共方法 `onModuleLoaded(ModuleLoadedParam)`、`onPackageReady(PackageReadyParam)`、`onHotReloading(HotReloadingParam): Boolean`、`onHotReloaded(HotReloadedParam)`

- [ ] **Step 1: 新建入口文件**

```kotlin
package com.fxxkmoondrop.secret

import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule

/**
 * 唯一入口。hooks 仍由 XposedEntry 用 libxposed 原生接口安装（阶段 B′ 不用 YukiHook 的 hook DSL）。
 * 单一 Java 入口 ⇒ 热重载合法；META-INF/xposed 由 yukihook-compiler 生成。
 */
@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    scope = ["com.google.android.gms", "com.android.settings"],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.AUTO,
)
object YukiEntry : YukiHookXposedModule {

    override fun onInit() {
        registerFrameworkEvents {
            onModuleLoaded { param -> XposedEntry(this).onModuleLoaded(param) }
            onPackageReady { param -> XposedEntry(this).onPackageReady(param) }
            onHotReloading { param -> XposedEntry(this).onHotReloading(param) }
            onHotReloaded { param -> XposedEntry(this).onHotReloaded(param) }
        }
    }

    override fun onHook() = Unit
}
```

- [ ] **Step 2: 改 XposedEntry 的类声明**

把 `class XposedEntry : XposedModule() {` 改为：

```kotlin
class XposedEntry(private val xposed: XposedInterface) {
```

并在类体首部加垫片（位置随意，建议紧贴类声明之后）：

```kotlin
    // 垫片：调用点保持 `hook(x).intercept { ... }` 原样，实际走注入的接口
    private fun hook(e: java.lang.reflect.Executable) = xposed.hook(e)
```

- [ ] **Step 3: 四个生命周期方法去掉 override**

`override fun onModuleLoaded(...)` → `fun onModuleLoaded(...)`，其余三个同理（`onHotReloading` 的返回类型 `Boolean` 保持不变）。

- [ ] **Step 4: 校正 import 与调用点**

- 删 `import io.github.libxposed.api.XposedModule`，加 `import io.github.libxposed.api.XposedInterface`
- `grep -nE 'onGmsLoaded\(|HearableControlHook\(' src/com/fxxkmoondrop/secret/XposedEntry.kt`，把传 `this` 的地方改为传 `xposed`

- [ ] **Step 5: 编译并确认没有遗留继承**

Run: `./g9.sh :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

Run: `grep -n 'XposedModule' src/com/fxxkmoondrop/secret/XposedEntry.kt`
Expected: 无输出

- [ ] **Step 6: 提交**

```bash
git add src/com/fxxkmoondrop/secret/YukiEntry.kt src/com/fxxkmoondrop/secret/XposedEntry.kt
git commit -m "exp(yukihook): 新增唯一入口 YukiEntry，XposedEntry 改为注入 XposedInterface"
```

---

### Task 3: hook 类的参数类型替换

**Files:**
- Modify: `src/com/fxxkmoondrop/secret/hook/FastPairHookEntry.kt:56`
- Modify: `src/com/fxxkmoondrop/secret/hook/HearableControlHook.kt`（含 `XposedModule` 的那几处）

**Interfaces:**
- Consumes: Task 2 产出的 `XposedInterface` 注入
- Produces: `FastPairHookEntry.onGmsLoaded(module: XposedInterface, cl: ClassLoader)`

- [ ] **Step 1: 枚举全部站点**

Run: `grep -rn 'XposedModule' src/ | grep -v '/build/'`
Expected: 只剩这几处待改（`YukiEntry.kt` 里不应有 `XposedModule` 字样）

- [ ] **Step 2: 逐个替换类型与 import**

把 `module: XposedModule` 改为 `module: XposedInterface`，import 同步替换。若某处只用 `hook()` / `log()`，无需其它改动。

- [ ] **Step 3: 编译**

Run: `./g9.sh :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add src/com/fxxkmoondrop/secret/hook/
git commit -m "exp(yukihook): hook 类改为接收 XposedInterface"
```

---

### Task 4: META-INF/xposed 交接与 R8 keep

**Files:**
- Move: `app/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}` → `.bak_yukihook_entry/META-INF-xposed/`
- Modify: `app/proguard-rules.pro`

**Interfaces:**
- Produces: 编译器生成的 `META-INF/xposed/*`；R8 下入口类不被裁剪

- [ ] **Step 1: 移出手写件（编译器要求源码目录不存在这些文件）**

```bash
mkdir -p .bak_yukihook_entry/META-INF-xposed
git mv app/src/main/resources/META-INF/xposed/java_init.list .bak_yukihook_entry/META-INF-xposed/
git mv app/src/main/resources/META-INF/xposed/module.prop    .bak_yukihook_entry/META-INF-xposed/
git mv app/src/main/resources/META-INF/xposed/scope.list     .bak_yukihook_entry/META-INF-xposed/
rmdir app/src/main/resources/META-INF/xposed app/src/main/resources/META-INF app/src/main/resources 2>/dev/null || true
```

- [ ] **Step 2: 补 R8 keep（不补则 release 包静默失效）**

`app/proguard-rules.pro` 追加：

```proguard
# YukiHook 生成的入口类名是「入口类 + _YukiHookXposedInit」，LSPosed 按字符串从 java_init.list 加载。
-keep class com.fxxkmoondrop.secret.YukiEntry_YukiHookXposedInit { *; }
```

- [ ] **Step 3: 构建 Release 包**

Run: `./g9.sh :app:assembleRelease -PfxxkKeypass="$(cat /root/.fxxk-keystore-pass)"`
Expected: `BUILD SUCCESSFUL`
（若手机内存吃紧，改为 push 到分支让 CI 出包，Task 5 同样适用）

- [ ] **Step 4: 验包内四件套与单入口**

```bash
APK=app/build/outputs/apk/release/app-release.apk
unzip -p "$APK" META-INF/xposed/java_init.list
unzip -p "$APK" META-INF/xposed/scope.list
unzip -p "$APK" META-INF/xposed/module.prop
```
Expected:
- `java_init.list` **只有一行** `com.fxxkmoondrop.secret.YukiEntry_YukiHookXposedInit`
- `scope.list` = `com.google.android.gms` + `com.android.settings`
- `module.prop` 含 `minApiVersion=101` / `targetApiVersion=102` / `staticScope=true` / `autoHotReload=true`

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "exp(yukihook): META-INF/xposed 交编译器生成，补入口类 R8 keep"
```

---

### Task 5: CI 出包与产物核验

**Files:** 无源码改动（仅验证）

- [ ] **Step 1: 推分支并等 CI**

```bash
git push -u origin exp/yukihook-entry
```
在 Actions 里等 `Android CI` 完成；`push` 事件只构建、不发版。

- [ ] **Step 2: 下载产物并复核**

```bash
# artifact id 取自 Actions 运行页或 API
curl -sSL -H "Authorization: token $(grep -o 'ghp_[A-Za-z0-9]*' /root/.git-credentials|head -1)" \
  -o /tmp/e.zip "https://api.github.com/repos/bqj6666/FxxkMoondrop/actions/artifacts/<ID>/zip"
unzip -o /tmp/e.zip -d /tmp/e && unzip -p /tmp/e/release/app-release.apk META-INF/xposed/java_init.list
```
Expected: 与 Task 4 Step 4 相同

- [ ] **Step 3: 核 R8 后入口类仍存在**

```bash
unzip -o /tmp/e/release/app-release.apk 'classes*.dex' -d /tmp/dex
for f in /tmp/dex/classes*.dex; do strings "$f" | grep -c 'YukiEntry_YukiHookXposedInit'; done
```
Expected: 至少一个 dex 命中 ≥1

- [ ] **Step 4: 核签名指纹未变**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 /workspace/sdk/build-tools/36.0.0/apksigner \
  verify --print-certs /tmp/e/release/app-release.apk | grep -i 'SHA-256 digest'
```
Expected: `f8f26c619f5eba8a160a7fbe702dc86f1d8dc37237f1b48625051153c5f56286`

---

### Task 6: 真机验证（需耳机）

**Files:** 无

- [ ] **Step 1: 安装（同签名可直接覆盖）**

```bash
pm install -r /data/local/tmp/eta/<下载路径>/app-release.apk
dumpsys package com.fxxkmoondrop.secret | grep -E 'versionCode|versionName'
```

- [ ] **Step 2: 确认 LSPosed 识别与作用域**

```bash
python3 -c "
import sqlite3;c=sqlite3.connect('/data/adb/lspd/config/modules_config.db')
print(c.execute(\"select * from modules_state where module_pkg_name='com.fxxkmoondrop.secret'\").fetchall())
print(c.execute(\"select app_pkg_name from scope where module_pkg_name='com.fxxkmoondrop.secret'\").fetchall())"
```
Expected: `enabled=1`，scope 为 gms + settings

- [ ] **Step 3: 热重载专项（本次最关键）**

```bash
am force-stop com.android.settings && am start -a android.settings.SETTINGS
logcat -d | grep -E 'onHotReloading|onHotReloaded|hookSettings' | tail
```
在 LSPosed 里对该模块触发一次「重新加载」后重复上面命令。
Expected: 重载后仍能打出 `hookSettings:` 系列日志，且没有 receiver 重复注册的告警

- [ ] **Step 4: 功能回归（接耳机）**

依次确认：设置页注入条目存在、配对弹窗正常、ANC 切换生效、电量通知刷新。
Expected: 与 B′ 前一致

- [ ] **Step 5: 记录结果**

把上面四项的实际输出贴进 `docs/BACKGROUND_MONITORING.md` 同级的验证记录或提交信息里。
