package com.fxxkmoondrop.secret

import android.util.Log
import com.highcapable.yukihookapi.YukiHook
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule

/**
 * exp/yukihook-1.5 试验入口：只验证 YukiHook 链路能否在我们的构建链里跑通。
 *
 * 并存关系（关键）：
 *   YukiHook 的编译器会接管 META-INF/xposed/ 的四个文件，且一个构建变体只允许
 *   一个入口注解 —— 但它提供 `javaEntries` 用来挂**框架原生入口**，
 *   这些类会被追加在生成入口之后写进 java_init.list。
 *   所以我们把原有的 XposedEntry 挂在这里，老功能与 YukiHook 同时被 LSPosed 加载。
 *
 * 与迁移前手写 module.prop 的对齐情况，便于逐字段比对生成结果：
 *   minApiVersion=101 / targetApiVersion=102 / staticScope=true 已对齐；
 *   作用域 com.google.android.gms + com.android.settings 已对齐。
 *   ⚠️ autoHotReload 拿不到：YukiHook 要求「热重载必须只有一个 Java 入口」，
 *      javaEntries 会追加一个入口，二者互斥。本试验选择保住老功能、放弃热重载。
 *      想两者兼得，就必须把 XposedEntry 整体改写成 YukiHook 入口（后续正式迁移）。
 *
 * 刻意不做的事：
 *   不调用 YukiHook.configure{} —— 自定义日志 TAG 属可选配置，
 *   最小面上先跑通，配置项逐个再加。
 */
@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    scope = ["com.google.android.gms", "com.android.settings"],
    staticScope = true,
    javaEntries = [XposedEntry::class]
)
object YukiProbe : YukiHookXposedModule {

    private const val TAG = "FxxkMoondrop-Yuki"

    override fun onInit() {
        Log.i(TAG, "YukiProbe.onInit 已执行")
    }

    // 注意：encase 不是 YukiHookXposedModule 的成员，而是
    // com.highcapable.yukihookapi.hook.factory 下的顶层函数（需 import 才能简写）。
    // 这里直接用官方 demo 里被验证过的完整形式 YukiHook.encase{}。
    override fun onHook() = YukiHook.encase {
        loadApp("com.android.settings") {
            // 刻意什么都不 hook：本分支只证明「基座能挂上、入口能被加载」。
            Log.i(TAG, "YukiProbe 已在设置进程加载（未做任何 hook，属预期）")
        }
    }
}
