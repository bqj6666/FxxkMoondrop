# FxxkMoondrop · R8 / ProGuard 保留规则
#
# 3.2.13 新增。开启 isMinifyEnabled = true 之前必须先有这份规则，
# 否则 LSPosed 入口类会被改名/移除，模块装上后完全不工作。
#
# ── 风险面实测结论（勿凭直觉改）─────────────────────────────────
# 全仓 91 处反射，但反射目标**全部是外部类**：
#   android.* / androidx.* / com.android.* / com.google.android.material.*
#   com.moondroplab.* / com.qualcomm.*
# 这些类不在我们的 APK 里（或不由 R8 处理），反射它们不受 minify 影响。
#
# 真正的风险只有两处，都已在此覆盖：
#   1. LSPosed 按**类名字符串**从 java_init.list 加载入口 → 必须 keep 入口类
#   2. 入口类及其静态初始化链会引用的成员被 R8 改名/移除 → 沿调用链 keep
#
# ⚠️ 刻意**不**使用 `-keep class com.fxxkmoondrop.secret.** { *; }`。
# 那等于关掉 R8 对我们代码的全部优化（还会顺带 keep 住 Compose 运行时
# 碰到的部分），体积收益会大幅缩水。这里的 keep 规则是**按需**的。

# ── 1. LSPosed 入口 ──────────────────────────────────────────────
# java_init.list 内容就是这一行：com.fxxkmoondrop.secret.XposedEntry
# LSPosed 运行时用纯字符串反射加载，改名即失效。
-keep class com.fxxkmoondrop.secret.XposedEntry {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public void onHotReloading(io.github.libxposed.api.XposedModuleInterface$HotReloadingParam);
    public void onHotReloaded(io.github.libxposed.api.XposedModuleInterface$HotReloadedParam);
    public void onSystemServerLoaded(io.github.libxposed.api.XposedModuleInterface$SystemServerLoadedParam);
}

# 入口类会 new 出 FastPairHookEntry（HearableControlHook 的宿主），
# 这些 hook 宿主是「被入口静态引用」而非反射，按名字找不到，
# 但其内部有大量反射调用系统类，保留其成员避免 R8 误判为未使用。
-keep class com.fxxkmoondrop.secret.hook.** {
    public <init>();
    public *** hook(...);
}

# ── 2. 跨进程配置读取 ────────────────────────────────────────────
# PrefsProvider 被 GMS 进程里的 hook 通过 ContentResolver 按
# **authority 字符串**访问，类名不参与，但整个读取链要活着。
-keep class com.fxxkmoondrop.secret.PrefsProvider { *; }

# ── 3. Manifest 声明的组件 ───────────────────────────────────────
# Activity / Service / Receiver 由系统按 manifest 里的**类名字符串**实例化，
# 不 keep 就会被移除或改名 → 点击图标崩溃 / 服务起不来。
# AGP 会自动从 manifest 提取这些，但显式声明避免 R8 规则依赖构建工具行为。
-keep class com.fxxkmoondrop.secret.MainActivity { *; }
-keep class com.fxxkmoondrop.secret.PermissionActivity { *; }
-keep class com.fxxkmoondrop.secret.OnboardingActivity { *; }
-keep class com.fxxkmoondrop.secret.EqActivity { *; }
-keep class com.fxxkmoondrop.secret.HeadsetDetectService { *; }
-keep class com.fxxkmoondrop.secret.AncTileService { *; }
-keep class com.fxxkmoondrop.secret.BootReceiver { *; }
-keep class com.fxxkmoondrop.secret.AliveReceiver { *; }
-keep class com.fxxkmoondrop.secret.NotifActionReceiver { *; }

# ── 4. Kotlin 元数据 ────────────────────────────────────────────
# 反射读取 data class 的 componentN / copy 等成员时需要。
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable

# ── 5. DexKit 定位结果 ──────────────────────────────────────────
# DexKitLocator 在运行时算出混淆类的名字，再用反射加载。
# 被定位的类都在**宿主 APK**（GMS / Settings）里，不受我们的 R8 影响；
# 但 DexKitLocator 自身必须活着。
-keep class org.luckypray.dexkit.** { *; }
-keep class com.fxxkmoondrop.secret.DexKitLocator { *; }

# ── 6. libxposed API ────────────────────────────────────────────
# compileOnly 依赖，运行时由 LSPosed 提供；保留引用签名避免混淆。
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# ── 7. 不要警告 DexKit / libxposed 的可选依赖 ─────────────────────
-dontwarn org.luckypray.dexkit.**

# ── 3.2.13: Miuix 弹层需要的 NavigationEventDispatcherOwner ──────────
#
# Miuix 的所有弹层（下拉 WindowDropdownMenu / OverlayListPopup 等）都读
# Compose 的 `LocalWindowInfo.current`，它通过
# `LocalNavigationEventDispatcherOwner` 反射/类型查找宿主 Activity 是否实现
# `androidx.navigationevent.NavigationEventDispatcherOwner`。
#
# MainActivity 已经 `implements` 了它，但 **R8 会把这个接口从 class_defs 的
# interfaces 列表里优化掉** —— 因为项目里没有任何静态引用能证明它需要
# （R8 只看得到「没人调用这个接口」）。实测：dex 里 MainActivity 的
# interfaces 列表为空，运行时必然抛
#   IllegalStateException: No NavigationEventDispatcher was provided
#   via LocalNavigationEventDispatcherOwner
#
# `-keep interface` + `-keepclassmembers class * implements` 双保险：
# 前者保证接口本身不被裁，后者保证实现关系（interfaces 列表）被保留。
-keep interface androidx.navigationevent.NavigationEventDispatcherOwner
-keep interface androidx.navigationevent.NavigationEventDispatcher
-keep class * implements androidx.navigationevent.NavigationEventDispatcherOwner {
    <init>(...);
    *** getNavigationEventDispatcher();
    *** navigationEventDispatcher();
}
