package com.fxxkmoondrop.secret

/**
 * 应用内更新日志（3.2.10 新增）。
 *
 * 只收录「本版要让用户知道的事」，不是 CHANGELOG.md 的副本：
 * 仓库里那份是给排查问题的人看的，这份是给刚升级的用户看的，篇幅必须一眼看完。
 * 发版时同步维护此处；版本号本身取自 PackageManager，不在这里写死。
 */
object Changelog {

    /** 置顶警示：老版本已知的恶性缺陷。返回空串表示本版无需警示（不显示警示条）。 */
    fun alert(): String = Lang.t(
            "3.2.8 及更早版本存在恶性缺陷：连接耳机后可能让耳机死机 / 自动关机 / 断开。"
                    + "请使用 3.2.9 及以上版本，旧版本不要再使用。",
            "Builds up to 3.2.8 have a serious defect: connecting your earbuds can crash them, "
                    + "power them off or drop the link. Please use 3.2.9 or later and stop using older builds.")

    /** 条目：arrayOf(标题, 正文)，顺序即显示顺序。 */
    fun entries(): List<Array<String>> = listOf(
            arrayOf(
                    Lang.t("3.2.10 · 新增：应用内更新日志", "3.2.10 · New: in-app changelog"),
                    Lang.t("「关于」页新增更新日志入口。升级后不用跳浏览器，就能看到本版改了什么、"
                            + "以及有没有需要立刻更新的警示。",
                            "The About page now has a changelog entry, so you can see what this build "
                                    + "changed and any urgent update notice without opening a browser.")),
            arrayOf(
                    Lang.t("3.2.9 · 严重修复：偶发把耳机连到死机 / 关机 / 断开",
                            "3.2.9 · Critical fix: rare hang / power-off / disconnect of the earbuds"),
                    Lang.t("根因是连接管理存在三处重入竞态：重建前没关闭旧连接（旧链路永不释放，"
                            + "且与新链路共用同一回调）、延迟重连任务无去重无取消、轮询在链路正在建立时"
                            + "又拆建一次。结果是耳机被要求同时维持多条链路、并在一秒内反复拆建，"
                            + "固件处理不当即崩溃。实测：并存 GATT 连接 2 → 1，每秒链路重建 5 次 → 1 次，"
                            + "每条通知处理 2 次 → 1 次，耳机全程保持连接未死机。",
                            "Three re-entrancy races in the connection manager: a reconnect path replaced "
                                    + "the old BluetoothGatt without closing it, so the stale link stayed on the "
                                    + "stack and shared the same callback; delayed reconnect tasks were never "
                                    + "deduplicated or cancelled; and the poller tore down a link that was still "
                                    + "being established. The earbuds ended up holding several links and being "
                                    + "rebuilt several times per second. Measured: concurrent GATT links 2 → 1, "
                                    + "rebuilds per second 5 → 1, each notification handled 2 → 1, earbuds stayed "
                                    + "connected.")),
            arrayOf(
                    Lang.t("3.2.9 · 新增：连接弹窗总开关",
                            "3.2.9 · New: master switch for the popup"),
                    Lang.t("设置 → 功能 → 官方集成 → 「连接弹窗」，默认开启。关掉后不再弹 Google Fast Pair "
                            + "卡片，通知栏与软件内控制不受影响。",
                            "Settings → Features → Official integration → \"Connected popup\", on by default. "
                                    + "Turning it off stops the Google Fast Pair card; notifications and in-app "
                                    + "controls are unaffected.")),
            arrayOf(
                    Lang.t("3.2.9 · 其他", "3.2.9 · Other"),
                    Lang.t("移除已不被构建引用的 xposed-api-stub.jar 与失效的 .PopupActivity 声明；"
                            + "targetSdkVersion 对齐 36；修正文档中的构建链、组件表与支持范围口径。",
                            "Dropped the unused xposed-api-stub.jar and a dead .PopupActivity declaration, "
                                    + "aligned targetSdkVersion to 36, and corrected the build chain, component "
                                    + "table and support scope in the docs.")))
}
