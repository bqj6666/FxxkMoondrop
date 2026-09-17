package com.fxxkmoondrop.secret

/**
 * 蓝牙设备详情页里「我们自己那三行」的显示规则。
 *
 * 三行分别是：官方的「耳机控制」切片行、官方自己的加载占位行（loading_pref）、我们的功能面板。
 * 规则只依赖三个由外部传入的事实，不含机型、地址等任何写死的东西：
 *
 *  - hasSlice   设备元数据里是否带控制切片地址（带才谈得上显示切片或占位）；
 *  - connected  耳机是否已连上（模块进程上报）；
 *  - ready      GAIA 是否完成服务发现（命令真的发得出去）。
 *
 * 耳机未连接      -> 三行全隐藏（页面上不留任何属于我们的东西）
 * 已连接、GAIA 未就绪 -> 官方加载行在原位占位，切片收起，我们的面板出来（控件置灰）
 * 已连接、GAIA 就绪   -> 切片出来，加载行收起
 *
 * ready 成立时按「已连接」处理：GAIA 就绪本身就以连接为前提，这样上报顺序抖动时也不会出现
 * 「切片/面板已经出来又被判成未连接收回去」的闪烁。
 */
object DetailRows {
    /** 三行各自的可见性。 */
    data class Visibility(
        val slice: Boolean,
        val loading: Boolean,
        val panel: Boolean
    )

    fun visibility(hasSlice: Boolean, connected: Boolean, ready: Boolean): Visibility {
        if (!connected && !ready) return Visibility(slice = false, loading = false, panel = false)
        if (ready) return Visibility(slice = hasSlice, loading = false, panel = true)
        // 已连接但 GAIA 还没就绪：用官方自己的加载行在原位占位，切片收起。
        return Visibility(slice = false, loading = hasSlice, panel = true)
    }
}
