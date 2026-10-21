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
 * 各参数刻意与迁移前手写的 module.prop 对齐，便于逐字段比对生成结果：
 *   minApiVersion=101 / targetApiVersion=102 / staticScope=true / autoHotReload=true
 *   作用域 com.google.android.gms + com.android.settings
 */
@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    scope = ["com.google.android.gms", "com.android.settings"],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.AUTO,
    javaEntries = [XposedEntry::class]
)
object YukiProbe : YukiHookXposedModule {

    private const val TAG = "FxxkMoondrop-Yuki"

    override fun onInit() {
        YukiHook.configure {
            logging {
                tag = TAG
            }
        }
        Log.i(TAG, "YukiProbe.onInit 已执行")
    }

    override fun onHook() = encase {
        loadApp("com.android.settings") {
            // 刻意什么都不 hook：本分支只证明「基座能挂上、入口能被加载」。
            Log.i(TAG, "YukiProbe 已在设置进程加载（未做任何 hook，属预期）")
        }
    }
}
