package com.fxxkmoondrop.secret.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.fxxkmoondrop.secret.DeviceMatcher
import com.fxxkmoondrop.secret.AncProfileLib
import com.fxxkmoondrop.secret.HookHelper
import com.fxxkmoondrop.secret.BatteryStore
import com.fxxkmoondrop.secret.GaiaBleClient
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayOutputStream
import java.io.File

class FastPairHookEntry {

    /** 【数据对接接口】本模块不内置任何模拟/占位数据。真实数据由外部注入，两条通道：
     * 1. 【推荐·跨进程】ACTION_TRIGGER 广播（device_name/battery_left/battery_right/icon_path）
     * 2. 【同进程】setDataProvider(FastPairDataProvider) 注入数据源 */
    interface FastPairDataProvider {
        /** 设备显示名称；返回 null 表示未知（ACL 回退蓝牙真实名 / TRIGGER 跳过弹窗） */
        fun getDeviceName(): String?

        /** 左耳电量 0-100；返回 -1 表示未知（不显示电量行） */
        fun getBatteryLeft(): Int

        /** 右耳电量 0-100；返回 -1 表示未知（不显示电量行） */
        fun getBatteryRight(): Int

        /** 弹窗图标 PNG 字节；返回 null 使用默认图标（文件优先，其次代码绘制） */
        fun getIconBytes(): ByteArray?
    }

    fun onGmsLoaded(module: XposedModule, cl: ClassLoader) {
        sGmsCl = cl
        sModule = module
        Log.d(TAG, "[FastPairHook] GMS loaded, classLoader=" + cl)

        // alpha1.14fix4: 拿模块自身 context（读取内置默认图标 assets）
        try {
            val app = HookHelper.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication")
            if (app is Context) {
                sModCtx = app.createPackageContext("com.fxxkmoondrop.secret",
                        Context.CONTEXT_IGNORE_SECURITY)
                Log.d(TAG, "[FastPairHook] mod ctx ready: " + sModCtx)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] mod ctx init fail: " + t)
        }

        // 1. 注册广播接收器：手动触发 + 蓝牙连接自动触发
        try {
            registerReceiverWhenReady(cl)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] register receiver failed: " + t)
        }

        // 2. hook dtes.f：真实配对弹窗 UI 设置时改写设备名
        try {
            val dtesClass = Class.forName("dtes", true, cl)
            val m = dtesClass.getDeclaredMethod("f", Context::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            module.hook(m).intercept { chain ->
                try {
                    val dtok = HookHelper.getObjectField(chain.thisObject, "c")
                    if (dtok != null) {
                        val name = sDataProvider?.getDeviceName()
                        if (!name.isNullOrEmpty()) {
                            HookHelper.setObjectField(dtok, "l", name)
                            HookHelper.setObjectField(dtok, "i", name)
                            Log.d(TAG, "[FastPairHook] (dtes.f) set name -> " + name)
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] dtes.f hook err: " + t)
                }
                chain.proceed()
            }
            Log.d(TAG, "[FastPairHook] dtes.f hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook dtes.f failed: " + t)
        }

        // 3. hook dthi.O(ImageView, dtok)：弹窗图片注入（原图为 dtok.h 字节，为空时替换）
        try {
            val dthiClass = Class.forName("dthi", true, cl)
            val dtokClass = Class.forName("dtok", true, cl)
            val m = dthiClass.getDeclaredMethod("O", ImageView::class.java, dtokClass)
            module.hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val iv = chain.args[0] as? ImageView ?: return@intercept null
                    val bmp = loadOrDrawIcon() ?: return@intercept null
                    iv.setImageBitmap(bmp)
                    Log.d(TAG, "[FastPairHook] (dthi.O) icon injected")
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] dthi.O icon inject fail: " + t)
                }
            }
            Log.d(TAG, "[FastPairHook] dthi.O hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook dthi.O failed: " + t)
        }

        // 4. hook dthi.q(ImageView, dtok, boolean)：HalfSheetModuleFragment 图片渲染主入口
        try {
            val dthiClass = Class.forName("dthi", true, cl)
            val dtokClass = Class.forName("dtok", true, cl)
            val m = dthiClass.getDeclaredMethod("q", ImageView::class.java, dtokClass,
                    Boolean::class.javaPrimitiveType)
            module.hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val iv = chain.args[0] as? ImageView ?: return@intercept null
                    val bmp = loadOrDrawIcon() ?: return@intercept null
                    iv.setImageBitmap(bmp)
                    Log.d(TAG, "[FastPairHook] (dthi.q) icon injected")
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] dthi.q icon inject fail: " + t)
                }
            }
            Log.d(TAG, "[FastPairHook] dthi.q hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook dthi.q failed: " + t)
        }

        // 5. hook 框架层 Activity.onResume：HalfSheet 弹窗出现时注入图标
        try {
            val m = android.app.Activity::class.java.getDeclaredMethod("onResume")
            module.hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val act = chain.thisObject as? android.app.Activity ?: return@intercept null
                    val cname = act.javaClass.name
                    if (!cname.contains("HalfSheet")) {
                        Log.d(TAG, "[FastPairHook] onResume seen: " + cname + " (not HalfSheet, skip)")
                        return@intercept null
                    }
                    Log.d(TAG, "[FastPairHook] HalfSheet MATCH onResume: " + cname)
                    sHalfSheetActivity = act
                                // 从 Intent 同步模拟电量（渲染进程与触发进程静态字段不共享）
                                try {
                                    val it = act.intent
                                    if (it != null) {
                                        val bl = it.getIntExtra(EXTRA_BATTERY_LEFT, -1)
                                        val br = it.getIntExtra(EXTRA_BATTERY_RIGHT, -1)
                                        // alpha2.26.6: 无条件覆盖（含 -1），防止上次弹窗(如模拟连接 86/72)
                                        // 的渲染进程静态字段残留，导致真实弹窗显示旧模拟电量
                                        if (bl >= 0) sBatteryLeft = bl
                                        if (br >= 0) sBatteryRight = br
                                        Log.d(TAG, "[FastPairHook] battery from intent: L=" + sBatteryLeft + " R=" + sBatteryRight)
                                    }
                                } catch (t: Throwable) {
                                    Log.d(TAG, "[FastPairHook] intent battery fail: " + t)
                                }
                                Log.d(TAG, "[FastPairHook] HalfSheet onResume: " + cname)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        injectIconOverlay(act)
                                        injectBatteryOverlay(act)
                                        injectModeButtons(act)
                                        injectSettingsButton(act)
                                        // alpha1.20: 已知模式立即高亮 + 向应用请求当前模式
                                        if (sLastMode >= 0) applyModeHighlight(sLastMode)
                                        sendModeRequest()
                                    } catch (t: Throwable) {
                                        Log.d(TAG, "[FastPairHook] inject fail: " + t)
                                    }
                                }, 400)
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] onResume inject fail: " + t)
                }
            }
            Log.d(TAG, "[FastPairHook] Activity.onResume hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook Activity.onResume failed: " + t)
        }

        // 5.5. hook 框架层 Activity.onDestroy：HalfSheet 弹窗关闭时通知应用
        try {
            val m = android.app.Activity::class.java.getDeclaredMethod("onDestroy")
            module.hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val act = chain.thisObject as? android.app.Activity ?: return@intercept null
                    if (!act.javaClass.name.contains("HalfSheet")) return@intercept null
                    if (act !== sHalfSheetActivity) return@intercept null
                    sHalfSheetActivity = null
                    val ctx = sAppContext
                    if (ctx != null) {
                        val i = Intent(ACTION_FP_SHEET_CLOSED)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.sendBroadcast(i)
                        Log.d(TAG, "[FastPairHook] half sheet closed -> sim restore signal")
                    }
                    null
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] onDestroy hook fail: " + t)
                }
            }
            Log.d(TAG, "[FastPairHook] Activity.onDestroy hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook Activity.onDestroy failed: " + t)
        }

        // 6. hook View.performClick：捕捉 central_btn（连接按钮）点击 -> 接管连接流程
        try {
            val m = android.view.View::class.java.getDeclaredMethod("performClick")
            module.hook(m).intercept { chain ->
                try {
                    val v = chain.thisObject as? android.view.View ?: return@intercept chain.proceed()
                    val ctx = v.context ?: return@intercept chain.proceed()
                    if (sCentralBtnId == 0) {
                        sCentralBtnId = ctx.resources.getIdentifier("central_btn", "id", PKG_GMS)
                        Log.d(TAG, "[FastPairHook] central_btn id resolved: " + sCentralBtnId)
                    }
                    if (sCentralBtnId != 0 && v.id == sCentralBtnId) {
                        Log.d(TAG, "[FastPairHook] central_btn click intercepted -> fake connect")
                        showConnectingUi()
                        startFakeConnectSequence()
                        return@intercept true  // short-circuit: don't call original
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] performClick hook err: " + t)
                }
                chain.proceed()
            }
            Log.d(TAG, "[FastPairHook] View.performClick hooked")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] hook View.performClick failed: " + t)
        }
    }

    /** 在弹窗 DecorView 上叠加自定义图标（标题与按钮之间的中央区域） */
    /** alpha2.38.4: 跨窗口屏幕绝对坐标定位。底部弹窗是独立 window，
     *  用 getLocationOnScreen 得到与窗口无关的真实屏幕像素，避免混用 decor/弹窗两个坐标系。 */
    private fun screenXY(v: android.view.View): IntArray {
        val loc = IntArray(2); v.getLocationOnScreen(loc); return loc
    }
    /** 轮询等待 ref 完成布局后执行 place 回调；回调内负责 show 与二次校验 */
    private fun schedulePlace(
        handler: android.os.Handler, view: android.view.View, decor: android.view.View,
        refId: Int, retries: Int, delayMs: Long,
        place: (android.view.View, android.view.View) -> Unit
    ) {
        handler.postDelayed({
            try {
                val ref = if (refId != 0) decor.findViewById<android.view.View>(refId) else null
                if (ref != null && ref.width > 0 && ref.height > 0) {
                    val rLoc = screenXY(ref)
                    if (rLoc[1] > 0) { place(view, ref); return@postDelayed }
                }
                if (retries > 0) schedulePlace(handler, view, decor, refId, retries - 1, delayMs, place)
            } catch (_: Throwable) { }
        }, delayMs)
    }

    private fun injectIconOverlay(act: android.app.Activity) {
        try {
            val decor = act.window.decorView
            if (decor.findViewWithTag<android.view.View>("fxxk_quickpair_icon") != null) return
            val bmp = loadOrDrawIcon() ?: return
            val prof = resolveScreenProfile(act)
            val iv = ImageView(act)
            iv.setImageBitmap(bmp)
            iv.tag = "fxxk_quickpair_icon"
            // alpha2.38.x: 从 Profile 读取集中配置坐标（不再动态追 view）
            val size = prof.iconSizePx
            val lp = android.widget.FrameLayout.LayoutParams(size, size)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            // 用固定 top 定位在标题上方区间，避免与标题/模式条叠字
            lp.topMargin = prof.iconTopPx
            (decor as android.view.ViewGroup).addView(iv, lp)
            Log.d(TAG, "[FastPairHook] icon overlay added (profile=" + prof.tag + " size=" + size + " top=" + prof.iconTopPx + ")")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] icon overlay fail: " + t)
        }
    }


    /** 递归遍历视图树：给无 drawable 的 ImageView 注入自定义图标 */
    private fun injectIconIntoTree(root: android.view.View?) {
        if (root == null) return
        if (root is ImageView) {
            val iv = root
            if (iv.drawable == null) {
                val bmp = loadOrDrawIcon()
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    // alpha1.14fix6: 放大 ImageView 布局尺寸 1.4x（仅正值，不动 wrap/match）
                    try {
                        iv.scaleType = ImageView.ScaleType.FIT_CENTER
                        val lp = iv.layoutParams
                        if (lp != null) {
                            if (lp.width > 0) lp.width = Math.round(lp.width * 1.4f)
                            if (lp.height > 0) lp.height = Math.round(lp.height * 1.4f)
                            iv.layoutParams = lp
                            iv.requestLayout()
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] icon resize fail: " + t)
                    }
                    Log.d(TAG, "[FastPairHook] tree icon injected into " + iv.id)
                }
            }
        }
        if (root is android.view.ViewGroup) {
            val vg = root
            for (i in 0 until vg.childCount) {
                injectIconIntoTree(vg.getChildAt(i))
            }
        }
    }

    // ==================== 图标 ====================

    /** 加载自定义图标（Download/moondrop_icon.png），失败则代码绘制默认图标 */
    private fun loadOrDrawIcon(): Bitmap? {
        try {
            val f = File(ICON_PATH)
            if (f.exists() && f.length() > 0) {
                val b = BitmapFactory.decodeFile(f.absolutePath)
                if (b != null) {
                    Log.d(TAG, "[FastPairHook] icon from file: " + f.length() + "B")
                    return b
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] icon file load fail: " + t)
        }

        // alpha1.14fix4: 软件自带默认图标（打包在模块 APK assets/ga2_icon.png）
        try {
            var ctx = sModCtx
            if (ctx == null) {
                val app = HookHelper.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication")
                if (app is Context) {
                    ctx = app.createPackageContext("com.fxxkmoondrop.secret",
                            Context.CONTEXT_IGNORE_SECURITY)
                    sModCtx = ctx
                }
            }
            if (ctx != null) {
                val input = ctx.assets.open(MOD_ASSET_ICON)
                try {
                    val b = BitmapFactory.decodeStream(input)
                    if (b != null) {
                        Log.d(TAG, "[FastPairHook] icon from builtin asset")
                        return b
                    }
                } finally {
                    input.close()
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] builtin asset load fail: " + t)
        }
        return drawDefaultIcon()
    }

    /** Moondrop 风格默认图标：深蓝紫渐变圆 + 品红环 + 白 M */
    private fun drawDefaultIcon(): Bitmap {
        val s = 512
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(),
                0xFF0F2E5E.toInt(), 0xFF1A1A2E.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 28f
        ring.color = 0xFFE94560.toInt()
        c.drawCircle(s / 2f, s / 2f, s / 2f - 20f, ring)
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.color = 0xFFFFFFFF.toInt()
        t.textSize = 280f
        t.typeface = Typeface.DEFAULT_BOLD
        t.textAlign = Paint.Align.CENTER
        c.drawText("M", s / 2f, s / 2f + 100f, t)
        return bmp
    }

    /** alpha2.27: 电量显示——支持部分显示 + 系统电量兜底 + GAIA 回包后自动刷新 */
    /** alpha2.38.7: 电量文字恢复写进 GMS 的 subhead（耳机名的副标题）。
     *  alpha2.38.5 误改成自绘 overlay + 硬编码 batteryTopPx=1080，导致电量跑到屏幕中央、还加了圆角背景。
     *  恢复 subhead 方案：位置由 GMS 布局决定（天然在耳机名下方、图标上方），样式沿用系统原生，无自绘背景。
     *  不再散落硬编码坐标；仅当 subhead 缺失时的回退自绘位置从 PopupProfile 读，不写死。 */
    private fun injectBatteryOverlay(act: android.app.Activity) {
        try {
            sBatteryOverlayActivity = act
            val mac = GaiaBleClient.getInstance().deviceAddress
            var l = sBatteryLeft
            var r = sBatteryRight
            var sys = sBatterySysLevel
            if (l < 0 && mac != null) l = BatteryStore.getGaiaLeft(mac).let { if (it >= 0) it else BatteryStore.get(mac) }
            if (r < 0 && mac != null) r = BatteryStore.getGaiaRight(mac).let { if (it >= 0) it else BatteryStore.get(mac) }
            if (sys < 0 && mac != null) sys = BatteryStore.get(mac)
            val text: String = when {
                l >= 0 && r >= 0 -> "左耳 " + l + "%  ·  右耳 " + r + "%"
                l >= 0 -> "左耳 " + l + "%"
                r >= 0 -> "右耳 " + r + "%"
                sys >= 0 -> "耳机电量 " + sys + "%"
                else -> "耳机电量 --%"
            }
            val decor = act.window.decorView
            val subId = act.resources.getIdentifier("subhead", "id", PKG_GMS)
            val sub = if (subId != 0) decor.findViewById<android.view.View>(subId) else null
            if (sub is android.widget.TextView) {
                sub.text = text
                sub.visibility = android.view.View.VISIBLE
                Log.d(TAG, "[FastPairHook] battery on subhead: " + text)
            } else {
                // subhead 缺失时兜底自绘；位置读 PopupProfile（不硬编码）
                val prof = resolveScreenProfile(act)
                var tv = decor.findViewWithTag<android.widget.TextView>("fxxk_batt_tv")
                if (tv == null) {
                    tv = android.widget.TextView(act)
                    tv.tag = "fxxk_batt_tv"
                    tv.gravity = android.view.Gravity.CENTER
                    tv.textSize = 15f
                    tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    val lp = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    lp.topMargin = prof.batteryTopPx
                    (decor as android.view.ViewGroup).addView(tv, lp)
                }
                tv.text = text
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.visibility = android.view.View.VISIBLE
                Log.d(TAG, "[FastPairHook] battery overlay fallback: " + text + " top=" + prof.batteryTopPx)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] battery overlay fail: " + t)
        }
    }

        fun refreshBatteryOverlay(left: Int, right: Int) {
        val act = sBatteryOverlayActivity ?: return
        if (left >= 0) sBatteryLeft = left
        if (right >= 0) sBatteryRight = right
        Handler(Looper.getMainLooper()).post {
            try { injectBatteryOverlay(act) } catch (_: Throwable) { }
        }
    }

    /** alpha2.27: 系统蓝牙电量广播更新、由广播接收器调用） */
    fun setSystemBattery(level: Int) {
        sBatterySysLevel = level
        val act = sBatteryOverlayActivity ?: return
        Handler(Looper.getMainLooper()).post {
            try { injectBatteryOverlay(act) } catch (_: Throwable) { }
        }
    }

    /** 点击连接后自绘"正在连接"状态：subhead 文字 + 隐藏按钮 + 底部载条 */
    private fun showConnectingUi() {
        try {
            val act = sHalfSheetActivity ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    val decor = act.window.decorView
                    // subhead 显示"正在连接…"
                    val subId = act.resources.getIdentifier("subhead", "id", PKG_GMS)
                    val sub = if (subId != 0) decor.findViewById<android.view.View>(subId) else null
                    if (sub is android.widget.TextView) {
                        sub.text = "正在连接…"
                        sub.visibility = android.view.View.VISIBLE
                    }
                    // v0.7: 连接中 -> 按钮禁用（原生置灰样式），不再自绘转圈
                    val btn = if (sCentralBtnId != 0) decor.findViewById<android.view.View>(sCentralBtnId) else null
                    if (btn != null) btn.isEnabled = false
                    Log.d(TAG, "[FastPairHook] connecting ui shown")
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] connecting ui fail: " + t)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] showConnectingUi fail: " + t)
        }
    }

    /** 点击"确定"后的 UI 连接流程：等待 CONNECT_DELAY_MS -> 关闭弹窗 -> 发 ACTION_CONNECTED */
    private fun startFakeConnectSequence() {
        val act = sHalfSheetActivity
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (act != null && !act.isFinishing) {
                    act.finish()
                    Log.d(TAG, "[FastPairHook] fake connect: half sheet finished")
                }
                val i = Intent(ACTION_CONNECTED)
                i.putExtra(EXTRA_DEVICE_NAME, sLastDeviceName)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ctx = sAppContext
                if (ctx != null) {
                    ctx.sendBroadcast(i)
                    Log.d(TAG, "[FastPairHook] CONNECTED broadcast sent, name=" + sLastDeviceName)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "[FastPairHook] fake connect fail: " + t)
            }
        }, CONNECT_DELAY_MS)
    }

    // ==================== 三模式按钮（v0.8） ====================

    /** v1.0: 三模式按钮点击 -> 广播给 FxxkMoondrop 应用（MODE_CHANGED, extra mode=0关闭/1降噪/2透传） */
    private fun sendModeChanged(mode: Int) {
        try {
            val ctx = sAppContext
            if (ctx == null) {
                Log.d(TAG, "[FastPairHook] MODE_CHANGED skip: no app context")
                return
            }
            val i = Intent(ACTION_MODE_CHANGED)
            i.putExtra(EXTRA_MODE, mode)
            ctx.sendBroadcast(i)
            Log.d(TAG, "[FastPairHook] MODE_CHANGED sent, mode=" + mode)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] MODE_CHANGED fail: " + t)
        }
    }

    /** alpha1.20: 向应用广播请求当前降噪模式（应用回发 MODE_STATE 驱动高亮） */
    private fun sendModeRequest() {
        try {
            val ctx = sAppContext
            if (ctx == null) {
                Log.d(TAG, "[FastPairHook] MODE_REQUEST skip: no app context")
                return
            }
            val i = Intent(ACTION_MODE_REQUEST)
            i.setPackage(PKG_APP)
            ctx.sendBroadcast(i)
            Log.d(TAG, "[FastPairHook] MODE_REQUEST sent")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] MODE_REQUEST fail: " + t)
        }
    }

    /** alpha1.20: 把弹窗三按钮中对应 mode 的按钮置为高亮（其余恢复默认） */
    private fun applyModeHighlight(mode: Int) {
        try {
            val act = sHalfSheetActivity ?: return
            val decor = act.window.decorView
            for (m in 0..3) {
                val holder = decor.findViewWithTag<android.view.View>("fxxk_mode_btn_" + m) ?: continue
                var bgV = holder.findViewWithTag<android.view.View>("fxxk_mode_bg")
                if (bgV == null) bgV = holder
                bgV.background = if (m == mode) buildModeHighlightBg(act) else buildCircleRipple(act)
                // alpha2.26.3: 高亮切换淡入动画（避免生硬跳变）
                try {
                    bgV.alpha = 0.4f
                    bgV.animate().alpha(1f).setDuration(160).start()
                } catch (_: Throwable) { }
            }
            Log.d(TAG, "[FastPairHook] mode highlight -> " + mode)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] mode highlight fail: " + t)
        }
    }

    /** alpha2.22: 按 ANC 能力状态驱动弹窗降噪按钮三态：
     *  0=探测中 -> 禁用一个 mode 按钮(置灰不可点，防误发)
     *  1=有ANC  -> 启用并恢复
     *  2=无ANC/截断 -> 隐藏整条 mode bar（不显示不支持的控件，符合跨机型适配） */
    private fun applyAncAvailability(status: Int) {
        try {
            sAncStatus = status
            val act = sHalfSheetActivity ?: return
            val decor = act.window.decorView
            val bar = decor.findViewWithTag<android.view.View>("fxxk_mode_bar") ?: return
            // alpha2.26.7: 恢复函数头注释语义——status=2(无ANC/截断) 隐藏整条 mode bar（不显示不支持的控件，跨机型适配）
            if (status == 2) {
                bar.visibility = android.view.View.GONE
                Log.d(TAG, "[FastPairHook] ANC availability -> 2 (no ANC), mode bar hidden")
                return
            }
            bar.visibility = android.view.View.VISIBLE
            val enabled = status == 1
            for (m in 0..3) {
                val holder = decor.findViewWithTag<android.view.View>("fxxk_mode_btn_" + m) ?: continue
                holder.isEnabled = enabled
                holder.alpha = if (enabled) 1f else 0.4f
            }
            Log.d(TAG, "[FastPairHook] ANC availability -> " + status +
                    (if (enabled) " enabled" else " disabled"))
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] applyAncAvailability fail: " + t)
        }
    }

    /** alpha1.20: 高亮圆底：亮色半透明填充 + 白色描边（深色弹窗上醒目） */
    private fun buildModeHighlightBg(act: android.app.Activity): android.graphics.drawable.Drawable {
        try {
            val d = act.resources.displayMetrics.density
            val g = android.graphics.drawable.GradientDrawable()
            g.shape = android.graphics.drawable.GradientDrawable.OVAL
            g.setColor(0x66FFFFFF.toInt())
            g.setStroke((2.5f * d).toInt(), 0xFFFFFFFF.toInt())
            return android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x66FFFFFF.toInt()), g, g)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] highlight bg fail: " + t)
            return buildCircleRipple(act)
        }
    }

    private fun injectModeButtons(act: android.app.Activity) {
        try {
            val decor = act.window.decorView
            if (decor.findViewWithTag<android.view.View>("fxxk_mode_bar") != null) return
            val bar = android.widget.LinearLayout(act)
            bar.tag = "fxxk_mode_bar"
            bar.orientation = android.widget.LinearLayout.HORIZONTAL
            bar.gravity = android.view.Gravity.CENTER
            bar.addView(buildModeItem(act, MODE_OFF, "关闭"))
            bar.addView(buildModeItem(act, MODE_ANC, "降噪"))
            bar.addView(buildModeItem(act, MODE_TRANS, "透传"))
            var showWind = true
            try {
                val cur = act.applicationContext.contentResolver.query(
                        Uri.parse("content://com.fxxkmoondrop.secret.prefs/show_wind"),
                        null, null, null, null)
                if (cur != null) {
                    try {
                        if (cur.moveToFirst()) {
                            showWind = cur.getInt(cur.getColumnIndexOrThrow("_value")) == 1
                        }
                    } finally { cur.close() }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "[FastPairHook] show_wind provider read fail: " + t)
            }
            if (showWind) bar.addView(buildModeItem(act, MODE_WIND, AncProfileLib.ANC_MODE_NAMES[3]))
            val prof = resolveScreenProfile(act)
            val d = act.resources.displayMetrics.density
            val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
            // alpha2.38.x: 从 Profile 读取集中配置坐标，水平居中，垂直固定 top
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = prof.modeBarTopPx
            lp.leftMargin = 0
            bar.visibility = android.view.View.INVISIBLE
            (decor as android.view.ViewGroup).addView(bar, lp)

            val btnId = act.resources.getIdentifier("central_btn", "id", PKG_GMS)
            if (sCentralBtnId != 0 || btnId != 0) {
                if (btnId != 0) sCentralBtnId = btnId
                val refId = sCentralBtnId
                val placeBar = object : Runnable {
                    override fun run() {
                        try {
                            val ref = if (refId != 0) decor.findViewById<android.view.View>(refId) else null
                            if (ref != null && ref.width > 0 && ref.height > 0 && bar.width > 0 && bar.height > 0) {
                                val rLoc = screenXY(ref); val dLoc = screenXY(decor)
                                if (rLoc[1] > 0) {
                                    val gap = (8 * d).toInt()
                                    val settingsTop = rLoc[1] - dLoc[1] - ref.height - gap
                                    val modeBarTop = settingsTop - gap - bar.height
                                    val lp2 = bar.layoutParams as android.widget.FrameLayout.LayoutParams
                                    lp2.topMargin = modeBarTop
                                    bar.layoutParams = lp2
                                    bar.visibility = android.view.View.VISIBLE
                                    Log.d(TAG, "[FastPairHook] mode bar dynamic: top=" + modeBarTop + " barH=" + bar.height)
                                    return
                                }
                            }
                            Handler(Looper.getMainLooper()).postDelayed(this, 120)
                        } catch (_: Throwable) { }
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(placeBar, 120)
            } else {
                bar.visibility = android.view.View.VISIBLE
            }

            applyAncAvailability(sAncStatus)
            Log.d(TAG, "[FastPairHook] mode buttons injected (关闭/降噪/透传" +
                    (if (showWind) "/抗风" else "") + ")")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] mode buttons fail: " + t)
        }
    }


    /** alpha2.37: 在确定按钮旁注入"设置"入口 —— 动态定位 central_btn 同行对齐 */
    /** alpha2.38: 在确定按钮旁注入"设置"入口 —— 克隆 central_btn 插入同一父容器，共享布局流与动态取色 */
    /** alpha2.38.3: 设置按钮 —— overlay 到 central_btn 正上方，完全克隆宽高/minHeight/minWidth，上下平行对齐 */
    private fun injectSettingsButton(act: android.app.Activity) {
        try {
            val decor = act.window.decorView
            val old = decor.findViewWithTag<android.view.View>("fxxk_settings_btn")
            if (old != null) (decor as android.view.ViewGroup).removeView(old)

            val btnId = act.resources.getIdentifier("central_btn", "id", PKG_GMS)
            val centralBtn = if (btnId != 0) decor.findViewById<android.view.View>(btnId) else null
            if (centralBtn == null) {
                Log.d(TAG, "[FastPairHook] settings btn: central_btn not found, skip")
                return
            }

            val btn: android.widget.TextView = try {
                Class.forName("com.google.android.material.button.MaterialButton")
                    .getConstructor(android.content.Context::class.java)
                    .newInstance(act) as android.widget.TextView
            } catch (_: Throwable) {
                android.widget.TextView(act)
            }
            btn.tag = "fxxk_settings_btn"
            btn.text = "设置"
            btn.gravity = android.view.Gravity.CENTER
            btn.isSingleLine = true

            if (centralBtn is android.widget.TextView) {
                btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, centralBtn.textSize)
                btn.setTextColor(centralBtn.textColors)
                btn.typeface = centralBtn.typeface
                btn.letterSpacing = centralBtn.letterSpacing
                btn.setPadding(centralBtn.paddingLeft,
                        centralBtn.paddingTop,
                        centralBtn.paddingRight,
                        centralBtn.paddingBottom)
                btn.minHeight = centralBtn.minHeight
                btn.minWidth = centralBtn.minWidth
            }
            try {
                val cbBg = centralBtn.background
                val cbTint = centralBtn.backgroundTintList
                val cbTintMode = centralBtn.backgroundTintMode
                if (cbBg != null) btn.background = cbBg.constantState?.newDrawable() ?: cbBg
                if (cbTint != null) btn.backgroundTintList = cbTint
                if (cbTintMode != null) btn.backgroundTintMode = cbTintMode
            } catch (_: Throwable) { }
            try { btn.elevation = centralBtn.elevation } catch (_: Throwable) { }

            btn.setOnClickListener {
                try {
                    val i = android.content.Intent()
                    i.setClassName(PKG_APP, "com.fxxkmoondrop.secret.MainActivity")
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    act.startActivity(i)
                    Log.d(TAG, "[FastPairHook] settings btn -> launch MainActivity")
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] settings btn launch fail: " + t)
                }
            }

            val screenW = act.resources.displayMetrics.widthPixels
            val prof = resolveScreenProfile(act)
            val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            lp.topMargin = -(2000)
            lp.leftMargin = 0
            btn.visibility = android.view.View.INVISIBLE
            (decor as android.view.ViewGroup).addView(btn, lp)

            if (btnId != 0) sCentralBtnId = btnId
            val handler = Handler(Looper.getMainLooper())
            schedulePlace(handler, btn, decor, sCentralBtnId, 12, 120) { v, ref ->
                val gap = (8 * act.resources.displayMetrics.density).toInt()
                val rLoc = screenXY(ref); val dLoc = screenXY(decor)
                val lp2 = v.layoutParams as android.widget.FrameLayout.LayoutParams
                lp2.width = ref.width
                lp2.height = ref.height
                lp2.leftMargin = (rLoc[0] - dLoc[0])
                lp2.topMargin = (rLoc[1] - dLoc[1] - ref.height - gap)
                lp2.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                v.layoutParams = lp2
                v.visibility = android.view.View.VISIBLE
                Log.d(TAG, "[FastPairHook] settings btn ABOVE: left=" + lp2.leftMargin +
                        " top=" + lp2.topMargin + " w=" + ref.width + " gap=" + gap +
                        " rLoc=" + rLoc[0] + "," + rLoc[1] +
                        " dLoc=" + dLoc[0] + "," + dLoc[1])
            }
            Log.d(TAG, "[FastPairHook] settings btn overlay added (pending)")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] settings button fail: " + t)
        }
    }


    /** 构建单个模式项：Material 风格圆形按钮 + 下方功能小字（克隆 central_btn 样式） */
    private fun buildModeItem(act: android.app.Activity, mode: Int, label: String): android.view.View {
        val d = act.resources.displayMetrics.density
        val prof = resolveScreenProfile(act)
        val item = android.widget.LinearLayout(act)
        item.orientation = android.widget.LinearLayout.VERTICAL
        item.gravity = android.view.Gravity.CENTER_HORIZONTAL
        // 圆形按钮：Ripple 圆形背景 + 中央 Material 图标（24dp）
        val holder = android.widget.FrameLayout(act)
        holder.tag = "fxxk_mode_btn_" + mode
        val bg = android.view.View(act)
        bg.tag = "fxxk_mode_bg" // alpha1.20: 高亮定位
        bg.background = buildCircleRipple(act)
        holder.addView(bg, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        val iconPx = (prof.modeItemIconPx * d).toInt() // Material 图标规范 24dp
        val iv = ImageView(act)
        iv.setImageDrawable(buildModeIcon(act, mode, iconPx))
        val il = android.widget.FrameLayout.LayoutParams(iconPx, iconPx)
        il.gravity = android.view.Gravity.CENTER
        holder.addView(iv, il)
        holder.setOnClickListener {
            Log.d(TAG, "[FastPairHook] mode clicked: " + mode)
            // alpha2.26.3: 点击缩放动画（Material 按压反馈：0.85 -> 回弹 1.0）
            try {
                holder.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction {
                    holder.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            } catch (_: Throwable) { }
            // alpha1.21: 点击立即乐观高亮（应用回发 MODE_STATE 仍会校准）
            sLastMode = mode
            applyModeHighlight(mode)
            sendModeChanged(mode)
        }
        item.addView(holder, android.widget.LinearLayout.LayoutParams((prof.modeItemBtnPx * d).toInt(), (prof.modeItemBtnPx * d).toInt()))
        val tv = android.widget.TextView(act)
        tv.text = label
        applyCentralTextStyle(act, tv)
        tv.gravity = android.view.Gravity.CENTER
        val tl = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        tl.topMargin = (prof.modeItemLabelTopPx * d).toInt()
        item.addView(tv, tl)
        val ilp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        ilp.leftMargin = (prof.modeItemLabelMarginPx * d).toInt()
        ilp.rightMargin = (prof.modeItemLabelMarginPx * d).toInt()
        item.layoutParams = ilp
        return item
    }

    /** 圆形 Material 底：填充色克隆 central_btn 的 backgroundTintList，ripple 用主题 colorControlHighlight */
    private fun buildCircleRipple(act: android.app.Activity): android.graphics.drawable.Drawable {
        try {
            val btnId = act.resources.getIdentifier("central_btn", "id", PKG_GMS)
            val cv = if (btnId != 0) act.window.decorView.findViewById<android.view.View>(btnId) else null
            val tint = cv?.backgroundTintList
            val fill = tint?.defaultColor ?: 0x29FFFFFF.toInt() // 兜底：半透明白
            Log.d(TAG, "[FastPairHook] circle fill = 0x" + Integer.toHexString(fill) + " (from central tint=" + (tint != null) + ")")
            val content = android.graphics.drawable.GradientDrawable()
            content.shape = android.graphics.drawable.GradientDrawable.OVAL
            content.setColor(fill)
            val typed = android.util.TypedValue()
            var rippleColor = 0x66FFFFFF.toInt()
            if (act.theme.resolveAttribute(android.R.attr.colorControlHighlight, typed, true)) {
                rippleColor = typed.data
            }
            return android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), content, content)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] circle ripple fail: " + t)
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
            gd.setColor(0x29FFFFFF.toInt())
            return gd
        }
    }

    /** 小字克隆 central_btn 的文字规格（size/color/typeface/letterSpacing） */
    private fun applyCentralTextStyle(act: android.app.Activity, dst: android.widget.TextView) {
        try {
            val btnId = act.resources.getIdentifier("central_btn", "id", PKG_GMS)
            val v = if (btnId != 0) act.window.decorView.findViewById<android.view.View>(btnId) else null
            if (v is android.widget.TextView) {
                val src = v
                dst.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        src.textSize / act.resources.displayMetrics.scaledDensity)
                dst.setTextColor(src.currentTextColor)
                dst.typeface = src.typeface
                dst.letterSpacing = src.letterSpacing
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] central text style fail: " + t)
        }
    }

    /** Material 图标（24dp，2dp 描边）：颜色克隆 central_btn 前景色；关闭=电源 / 降噪=波浪 / 透传=耳朵 */
    private fun buildModeIcon(act: android.app.Activity, mode: Int, px: Int): android.graphics.drawable.Drawable {
        var iconColor = 0xFFFFFFFF.toInt()
        try {
            val btnId = act.resources.getIdentifier("central_btn", "id", PKG_GMS)
            val v = if (btnId != 0) act.window.decorView.findViewById<android.view.View>(btnId) else null
            if (v is android.widget.TextView) {
                iconColor = v.currentTextColor
            }
        } catch (_: Throwable) { }
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        val density = act.resources.displayMetrics.density
        p.strokeWidth = 2f * density // Material 图标 2dp 描边
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = iconColor
        val cx = px / 2f
        val cy = px / 2f
        val ir = px * 0.36f
        when (mode) {
            MODE_OFF -> { // 电源符号
                val rect = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect, 50f, 260f, false, p)
                c.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.35f, p)
            }
            MODE_ANC -> { // 降噪：三条水平波浪
                for (i in -1..1) {
                    val yy = cy + i * px * 0.16f
                    val wv = android.graphics.Path()
                    wv.moveTo(cx - px * 0.30f, yy)
                    wv.cubicTo(cx - px * 0.10f, yy - px * 0.14f, cx + px * 0.10f, yy + px * 0.14f, cx + px * 0.30f, yy)
                    c.drawPath(wv, p)
                }
            }
            MODE_WIND -> { // 抗风：旋风/三弧线
                val rect3 = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect3, 70f, 220f, false, p)
                val rect4 = android.graphics.RectF(cx - ir*0.7f, cy - ir*0.7f, cx + ir*0.7f, cy + ir*0.7f)
                c.drawArc(rect4, 90f, 200f, false, p)
                val rect5 = android.graphics.RectF(cx - ir*0.4f, cy - ir*0.4f, cx + ir*0.4f, cy + ir*0.4f)
                c.drawArc(rect5, 110f, 180f, false, p)
            }
            MODE_TRANS -> { // 透传：耳朵（双 C 弧 + 耳道圆点）
                val rect = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect, 60f, 240f, false, p)
                val ir2 = ir * 0.5f
                val rect2 = android.graphics.RectF(cx - ir2, cy - ir2, cx + ir2, cy + ir2)
                c.drawArc(rect2, 90f, 180f, false, p)
                val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                dot.style = Paint.Style.FILL
                dot.color = iconColor
                c.drawCircle(cx + ir * 0.10f, cy + ir * 0.14f, px * 0.05f, dot)
            }
        }
        return android.graphics.drawable.BitmapDrawable(act.resources, bmp)
    }

    // ==================== 广播注册 ====================

    private fun registerReceiverWhenReady(cl: ClassLoader) {
        try {
            val atClass = Class.forName("android.app.ActivityThread", true, cl)
            val at = HookHelper.callStaticMethod(atClass, "currentActivityThread")
            if (at != null) {
                val app = HookHelper.callMethod(at, "getApplication")
                if (app != null) {
                    val ctx = HookHelper.callMethod(app, "getApplicationContext") as Context
                    doRegister(ctx, cl)
                    Log.d(TAG, "[FastPairHook] receiver registered via currentApplication")
                    return
                }
            }
            Log.d(TAG, "[FastPairHook] application not ready yet")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] direct register path failed: " + t)
        }
        try {
            val appCls = Class.forName("android.app.Application", true, cl)
            val m = appCls.getDeclaredMethod("attach", Context::class.java)
            sModule?.hook(m)?.intercept { chain ->
                chain.proceed()
                try {
                    val ctx = chain.args[0] as Context
                    doRegister(ctx, cl)
                    Log.d(TAG, "[FastPairHook] receiver registered via Application.attach")
                } catch (t: Throwable) {
                    Log.d(TAG, "[FastPairHook] attach register failed: " + t)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] Application.attach hook failed: " + t)
        }
    }

    private fun doRegister(ctx: Context, cl: ClassLoader) {
        sAppContext = ctx
        var exportedFlag: Int
        try {
            exportedFlag = Context::class.java.getField("RECEIVER_EXPORTED").getInt(null)
        } catch (_: Throwable) {
            exportedFlag = 0x2 // 兜底
        }
        // 手动触发广播
        try {
            val filter = IntentFilter(ACTION_TRIGGER)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // 【数据对接·跨进程主入口】FxxkMoondrop 应用触发弹窗并传真实数据
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                    try {
                        val bl = intent.getStringExtra(EXTRA_BATTERY_LEFT)
                        if (bl != null) sBatteryLeft = bl.toInt()
                        else sBatteryLeft = sDataProvider?.getBatteryLeft() ?: -1 // alpha1.14fix3
                        val br = intent.getStringExtra(EXTRA_BATTERY_RIGHT)
                        if (br != null) sBatteryRight = br.toInt()
                        else sBatteryRight = sDataProvider?.getBatteryRight() ?: -1 // alpha1.14fix3
                        // alpha2.26.7: 同步重置模式高亮残留——模拟连接的乐观高亮不得带入真实弹窗
                        sLastMode = -1
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] battery extra parse fail: " + t)
                    }
                    postShow(context, cl, deviceName)
                }
            }
            ctx.registerReceiver(receiver, filter, exportedFlag)
            Log.d(TAG, "[FastPairHook] trigger receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] trigger receiver fail: " + t)
        }
        // 蓝牙 ACL_CONNECTED 自动触发
        try {
            val acl = IntentFilter("android.bluetooth.device.action.ACL_CONNECTED")
            val btReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    var name: String? = null
                    try {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<BluetoothDevice>("android.bluetooth.device.extra.DEVICE")
                        if (dev != null) {
                            name = HookHelper.callMethod(dev, "getName") as String?
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] get bt name fail: " + t)
                    }
                    // 【数据对接】设备名优先取蓝牙真实名称，未提供时回退 provider
                    val dp = sDataProvider
                    if (name.isNullOrEmpty() && dp != null) {
                        name = dp.getDeviceName()
                    }
                    if (name.isNullOrEmpty()) {
                        Log.d(TAG, "[FastPairHook] ACL_CONNECTED skip: no device name")
                        return
                    }
                    // ** alpha1.32: 事件通道——ACL 设备地址推送给应用（动态发现，零硬编码） **
                    try {
                        @Suppress("DEPRECATION")
                        val dev2 = intent.getParcelableExtra<BluetoothDevice>("android.bluetooth.device.extra.DEVICE")
                        if (dev2 != null) {
                            val devAddr = HookHelper.callMethod(dev2, "getAddress") as String?
                            if (devAddr != null && devAddr.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
                                sendLeAddr(devAddr.uppercase())
                            }
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] acl addr push fail: " + t)
                    }
                    Log.d(TAG, "[FastPairHook] ACL_CONNECTED -> " + name
                            + " (deferred " + ACL_POSTSHOW_DELAY_MS + "ms for GAIA)")
                    val fpName = name
                    Handler(Looper.getMainLooper()).postDelayed({
                        val since = System.currentTimeMillis() - sLastShowMs
                        if (since < ACL_REPEAT_GUARD_MS) {
                            Log.d(TAG, "[FastPairHook] ACL deferred skip (shown " + since + "ms ago)")
                            return@postDelayed
                        }
                        postShow(context, cl, fpName)
                    }, ACL_POSTSHOW_DELAY_MS)
                }
            }
            ctx.registerReceiver(btReceiver, acl, exportedFlag)
            Log.d(TAG, "[FastPairHook] bluetooth ACL receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] bluetooth receiver fail: " + t)
        }
        // alpha1.20: 应用广播当前降噪模式 -> 弹窗按钮高亮
        try {
            val ms = IntentFilter(ACTION_MODE_STATE)
            val modeStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val mode = intent.getIntExtra(EXTRA_MODE, -1)
                        if (mode < 0 || mode > 3) return
                        sLastMode = mode
                        Log.d(TAG, "[FastPairHook] MODE_STATE received, mode=" + mode)
                        applyModeHighlight(mode)
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] MODE_STATE handle fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(modeStateReceiver, ms, exportedFlag)
            Log.d(TAG, "[FastPairHook] mode state receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] mode state receiver fail: " + t)
        }
        // alpha2.22: 应用广播 ANC 能力状态 -> 驱动弹窗降噪按钮三态
        try {
            val ancF = IntentFilter(ACTION_ANC_STATUS)
            val ancStatusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val st = intent.getIntExtra(EXTRA_ANC_STATUS, 0)
                        Log.d(TAG, "[FastPairHook] ANC_STATUS received, status=" + st)
                        applyAncAvailability(st)
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] ANC_STATUS handle fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(ancStatusReceiver, ancF, exportedFlag)
            Log.d(TAG, "[FastPairHook] ANC status receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] ANC status receiver fail: " + t)
        }
        // ** alpha1.32: 应用请求 LE 扫描 -> GMS 侧 receiver **
        try {
            val reqF = IntentFilter(ACTION_REQ_LE_SCAN)
            val reqReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "[FastPairHook] LE scan request received")
                    handleLeScanRequest()
                }
            }
            ctx.registerReceiver(reqReceiver, reqF, exportedFlag)
            Log.d(TAG, "[FastPairHook] LE scan request receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] LE scan req receiver fail: " + t)
        }
        // ** alpha1.34: 应用探测模块激活 -> PING/PONG **
        try {
            val pingF = IntentFilter(ACTION_FASTPAIR_PING)
            val pingReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val pong = Intent(ACTION_FASTPAIR_PONG)
                        pong.setPackage(PKG_APP)
                        pong.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        context.sendBroadcast(pong)
                        Log.d(TAG, "[FastPairHook] ping -> pong")
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] pong fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(pingReceiver, pingF, exportedFlag)
            Log.d(TAG, "[FastPairHook] ping receiver registered")
    
        // alpha2.27: 接收 app 进程广播的电量更新（GMS 弹窗刷新）
        try {
            val battF = IntentFilter(ACTION_BATTERY_UPDATE)
            val battReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val l = intent.getIntExtra("left", -1)
                        val r = intent.getIntExtra("right", -1)
                        val sys = intent.getIntExtra("sys", -1)
                        if (l >= 0 || r >= 0) refreshBatteryOverlay(l, r)
                        if (sys >= 0) setSystemBattery(sys)
                        Log.d(TAG, "[FastPairHook] battery update RX: l=" + l + " r=" + r + " sys=" + sys)
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] battery RX fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(battReceiver, battF, exportedFlag)
            Log.d(TAG, "[FastPairHook] battery update receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] battery receiver fail: " + t)
        }
    } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] ping receiver fail: " + t)
    
        // alpha2.27: 接收 app 进程广播的电量更新（GMS 弹窗刷新）
        try {
            val battF = IntentFilter(ACTION_BATTERY_UPDATE)
            val battReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val l = intent.getIntExtra("left", -1)
                        val r = intent.getIntExtra("right", -1)
                        val sys = intent.getIntExtra("sys", -1)
                        if (l >= 0 || r >= 0) refreshBatteryOverlay(l, r)
                        if (sys >= 0) setSystemBattery(sys)
                        Log.d(TAG, "[FastPairHook] battery update RX: l=" + l + " r=" + r + " sys=" + sys)
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] battery RX fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(battReceiver, battF, exportedFlag)
            Log.d(TAG, "[FastPairHook] battery update receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] battery receiver fail: " + t)
        }
    }

        // alpha2.27: 接收 app 进程广播的电量更新（GMS 弹窗刷新）
        try {
            val battF = IntentFilter(ACTION_BATTERY_UPDATE)
            val battReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val l = intent.getIntExtra("left", -1)
                        val r = intent.getIntExtra("right", -1)
                        val sys = intent.getIntExtra("sys", -1)
                        if (l >= 0 || r >= 0) refreshBatteryOverlay(l, r)
                        if (sys >= 0) setSystemBattery(sys)
                        Log.d(TAG, "[FastPairHook] battery update RX: l=" + l + " r=" + r + " sys=" + sys)
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] battery RX fail: " + t)
                    }
                }
            }
            ctx.registerReceiver(battReceiver, battF, exportedFlag)
            Log.d(TAG, "[FastPairHook] battery update receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] battery receiver fail: " + t)
        }
    }

    // ** alpha1.32: 应用请求 -> GMS 侧 BLE 扫描发现耳机 LE 地址（ColorOS 放行 GMS，第三方应用被拦） **

    /** alpha1.32b: 跨进程扫描锁——GMS 多子进程只允许一个扫描（文件锁，原子 createNewFile） */
    private fun gmsContext(): Context? {
        return try {
            val app = HookHelper.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication")
            if (app is Context) app else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun acquireScanLock(): Boolean {
        return try {
            val c = gmsContext() ?: return true
            val f = File(c.filesDir, ".fxxk_le_scan_lock")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() > 15000) {
                f.delete()
            }
            f.createNewFile()
        } catch (_: Throwable) {
            true
        }
    }

    private fun releaseScanLock() {
        try {
            val c = gmsContext() ?: return
            File(c.filesDir, ".fxxk_le_scan_lock").delete()
        } catch (_: Throwable) { }
    }

    private fun handleLeScanRequest() {
        try {
            val now = System.currentTimeMillis()
            if (now - sLastScanReqMs < 30000) {
                Log.d(TAG, "[FastPairHook] LE scan req throttled (30s)")
                return
            }
            sLastScanReqMs = now
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.d(TAG, "[FastPairHook] LE scan skip: adapter off")
                return
            }
            if (sLeScanning) return
            sLeScanning = true
            val sc = adapter.bluetoothLeScanner
            if (sc == null) {
                sLeScanning = false
                return
            }
            if (!acquireScanLock()) {
                Log.d(TAG, "[FastPairHook] LE scan skip (locked by sibling process)")
                return
            }
            val st = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val cbRef = arrayOfNulls<ScanCallback>(1)
            cbRef[0] = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    try {
                        if (result == null || result.device == null) return
                        var n = result.scanRecord?.deviceName
                        if (n == null) n = result.device.name
                        val addr = result.device.address
                        if (addr == null) return
                        Log.d(TAG, "[FastPairHook] LE scan result: " + addr
                                + " name=" + (n ?: "<null>") + " rssi=" + result.rssi)
                        var hit = false
                        if (!n.isNullOrEmpty() && DeviceMatcher.isMoondrop(n)) {
                            hit = true
                        } else if (n.isNullOrEmpty()) {
                            val pre = if (addr.length >= 12) addr.substring(0, 12).uppercase() else null
                            if (pre != null) {
                                for (d in adapter.bondedDevices) {
                                    val dn = d.name
                                    if (dn == null || !DeviceMatcher.isMoondrop(dn)) continue
                                    val da = d.address
                                    if (da != null && da.length >= 12
                                            && da.substring(0, 12).uppercase() == pre) {
                                        hit = true
                                        break
                                    }
                                }
                            }
                        }
                        if (!hit) return
                        Log.d(TAG, "[FastPairHook] LE scan hit: " + addr
                                + " name=" + (n ?: "<null>"))
                        try { sc.stopScan(cbRef[0]) } catch (_: Throwable) { }
                        sLeScanning = false
                        releaseScanLock()
                        sendLeAddr(addr.uppercase())
                    } catch (t: Throwable) {
                        Log.d(TAG, "[FastPairHook] LE scan result fail: " + t)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.d(TAG, "[FastPairHook] LE scan failed code=" + errorCode)
                    sLeScanning = false
                    releaseScanLock()
                }
            }
            sc.startScan(null, st, cbRef[0])
            Handler(Looper.getMainLooper()).postDelayed({
                if (!sLeScanning) return@postDelayed
                try { sc.stopScan(cbRef[0]) } catch (_: Throwable) { }
                sLeScanning = false
                releaseScanLock()
                Log.d(TAG, "[FastPairHook] LE scan window done (no hit)")
            }, 10000)
            Log.d(TAG, "[FastPairHook] LE scan started (GMS side)")
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] handleLeScanRequest fail: " + t)
            sLeScanning = false
            releaseScanLock()
        }
    }

    private fun sendLeAddr(addr: String) {
        try {
            if (addr == null || !addr.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) return
            val now = System.currentTimeMillis()
            if (addr == sLastPushedAddr && now - sLastPushMs < 60000) return
            sLastPushedAddr = addr
            sLastPushMs = now
            val ctx = sAppContext
            if (ctx == null) {
                Log.d(TAG, "[FastPairHook] LE addr push skip: no app context")
                return
            }
            val i = Intent(ACTION_LE_ADDR_FOUND)
            i.putExtra(EXTRA_LE_ADDR, addr)
            i.setPackage(PKG_APP)
            ctx.sendBroadcast(i)
            Log.d(TAG, "[FastPairHook] LE addr pushed: " + addr)
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] sendLeAddr fail: " + t)
        }
    }

    private fun postShow(ctx: Context, cl: ClassLoader, deviceName: String?) {
        // 【设备过滤】仅 Moondrop 品牌耳机弹窗；其他蓝牙设备连接一律不弹
        if (!DeviceMatcher.isMoondrop(deviceName)) {
            Log.d(TAG, "[FastPairHook] postShow skip: not a Moondrop device -> " + deviceName)
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                showHalfSheet(ctx, cl, deviceName)
            } catch (t: Throwable) {
                Log.d(TAG, "[FastPairHook] show failed: " + t)
            }
        }
    }

    // ==================== 弹窗 ====================

    /** 手工构造 dtok protobuf payload（field 8 = l 设备名，field 5 = i）并启动 HalfSheetActivity */
    @Throws(Throwable::class)
    private fun showHalfSheet(ctx: Context, cl: ClassLoader, deviceName: String?) {
        // 【数据对接】名称优先级：广播 extra / 蓝牙真实名 > provider > 无数据不弹窗
        var name = deviceName
        val dp = sDataProvider
        if (name.isNullOrEmpty() && dp != null) {
            name = dp.getDeviceName()
        }
        if (name.isNullOrEmpty()) {
            Log.d(TAG, "[FastPairHook] showHalfSheet skip: no device name")
            return
        }
        sLastDeviceName = name
        // 电量：触发进程静态值（由广播 extra/provider 写入）；无数据保持 -1，渲染端不显示
        sDataProvider?.let {
            if (sBatteryLeft < 0) sBatteryLeft = it.getBatteryLeft()
            if (sBatteryRight < 0) sBatteryRight = it.getBatteryRight()
        }
        val icon = readIconBytes()
        val payload = buildProtoPayload(name, icon)
        Log.d(TAG, "[FastPairHook] payload icon bytes=" + (icon?.size ?: 0))
        Log.d(TAG, "[FastPairHook] manual payload len=" + payload.size + " name=" + name)

        val intent = Intent()
        intent.setClassName(ctx, "com.google.android.gms.nearby.discovery.fastpair.HalfSheetActivity")
        intent.putExtra("com.google.android.gms.nearby.discovery.fastpair.EXTRA_HALF_SHEET_TYPE", "SPOT")
        intent.putExtra("com.google.android.gms.nearby.discovery.HALF_SHEET", payload)
        intent.putExtra("com.google.android.gms.nearby.discovery.EXTRA_SPOT_FRAGMENT_STATE", 1) // FAST_PAIR_PROMPT
        intent.putExtra(EXTRA_BATTERY_LEFT, sBatteryLeft)   // 电量经 Intent 传给渲染进程
        intent.putExtra(EXTRA_BATTERY_RIGHT, sBatteryRight)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.d(TAG, "[FastPairHook] starting HalfSheetActivity...")
        ctx.startActivity(intent)
        sLastShowMs = System.currentTimeMillis()
        Log.d(TAG, "[FastPairHook] startActivity called")
    }

    /** 手工构造 dtok protobuf 字节：field 8 (l=设备名)、field 5 (i)、field 6 (h=图片 bytes) */
    private fun buildProtoPayload(name: String, icon: ByteArray?): ByteArray {
        return try {
            val str = name.toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream()
            writeTagAndBytes(bos, 8, str)  // l: 设备名/标题
            writeTagAndBytes(bos, 5, str)  // i: 副标题
            if (icon != null && icon.isNotEmpty()) {
                writeTagAndBytes(bos, 6, icon)  // h: 图标字节（field 6 -> dtok.h）
            }
            bos.toByteArray()
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] buildProtoPayload fail: " + t)
            ByteArray(0)
        }
    }

    private fun writeTagAndBytes(bos: ByteArrayOutputStream, fieldNum: Int, data: ByteArray) {
        writeVarint(bos, (fieldNum shl 3) or 2)
        writeVarint(bos, data.size)
        bos.write(data, 0, data.size)
    }

    /** 读取自定义图标字节（Download/moondrop_icon.png） */
    private fun readIconBytes(): ByteArray? {
        try {
            val f = File(ICON_PATH)
            if (f.exists() && f.length() > 0 && f.length() < 1024 * 1024) {
                val fis = java.io.FileInputStream(f)
                val buf = ByteArray(f.length().toInt())
                var off = 0
                while (off < buf.size) {
                    val n = fis.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                fis.close()
                Log.d(TAG, "[FastPairHook] icon bytes loaded: " + buf.size)
                return buf
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[FastPairHook] readIconBytes fail: " + t)
        }
        return null
    }

    private fun writeVarint(bos: ByteArrayOutputStream, v: Int) {
        var value = v
        while ((value and -0x80) != 0) {
            bos.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        bos.write(value)
    }

    companion object {
        const val TAG = "FastPairHook"

        // ==================== 弹窗布局 Profile（集中配置，按屏幕档位选择） ====================
        // alpha2.38.x：不把坐标散落写死在函数里，统一收敛到一个可配置 Profile 表。
        // 坐标单位统一用设备绝对像素（px）。按屏幕分辨率+density 分档：
        //  - 6.1 寸档：1216x2640 / density 3.0 / 物理 460dpi（alpha2.38.x 真机验证）
        //  - 6.3 寸档：其它大屏档位（等比占位，待 6.3 寸真机精调）
        /** 单个屏幕档位的弹窗布局参数。字段均为绝对像素 px。 */
        data class PopupProfile(
            val tag: String,
            // 耳机图标
            val iconSizePx: Int,
            val iconTopPx: Int,
            // 电量文字（自绘 overlay）
            val batteryTopPx: Int,
            // 模式按钮条（关闭/降噪/透传/抗风）
            val modeBarTopPx: Int,
            val modeItemBtnPx: Int,
            val modeItemIconPx: Int,
            val modeItemLabelTopPx: Int,
            val modeItemLabelMarginPx: Int,
            // 底部设置按钮（相对确定按钮左侧的固定间距）
            val settingsOffsetFromBtnPx: Int,
            // 图标与标题/模式条的间距系数（用于不叠字的安全余量）
            val iconTitleGapPx: Int,
            val modeBarGapPx: Int,
        )

        // 6.1 寸档（1216x2640 / density 3.0）：沿用 alpha2.37 真机验证坐标
        private val PROFILE_61 = PopupProfile(
            tag = "6.1in-1216x2640",
            iconSizePx = 340,
            iconTopPx = 1520,
            batteryTopPx = 1080,
            modeBarTopPx = 1910,
            modeItemBtnPx = 46,
            modeItemIconPx = 24,
            modeItemLabelTopPx = 2,
            modeItemLabelMarginPx = 6,
            settingsOffsetFromBtnPx = 88,
            iconTitleGapPx = 12,
            modeBarGapPx = 100,
        )

        // 6.3 寸档：占位，按对角线比例缩放（6.3/6.1 ≈ 1.033），待真机精调
        private val PROFILE_63 = PopupProfile(
            tag = "6.3in-placeholder",
            iconSizePx = 352,
            iconTopPx = 1576,
            batteryTopPx = 1120,
            modeBarTopPx = 1979,
            modeItemBtnPx = 48,
            modeItemIconPx = 25,
            modeItemLabelTopPx = 2,
            modeItemLabelMarginPx = 6,
            settingsOffsetFromBtnPx = 91,
            iconTitleGapPx = 12,
            modeBarGapPx = 103,
        )

        /** 依据屏幕参数解析当前设备所属档位。6.1 寸=1216x2640/density 3.0 精确匹配，其余走 6.3 档。 */
        @JvmStatic
        fun resolveScreenProfile(act: android.app.Activity): PopupProfile {
            return try {
                val dm = act.resources.displayMetrics
                val w = dm.widthPixels; val h = dm.heightPixels; val dens = dm.density
                Log.d(TAG, "[FastPairHook] screen profile query: " + w + "x" + h + " density=" + dens)
                if (w == 1216 && h == 2640 && Math.abs(dens - 3.0f) < 0.05f) {
                    Log.d(TAG, "[FastPairHook] screen profile -> 6.1in (1216x2640)")
                    PROFILE_61
                } else {
                    Log.d(TAG, "[FastPairHook] screen profile -> 6.3in (fallback)")
                    PROFILE_63
                }
            } catch (t: Throwable) {
                Log.d(TAG, "[FastPairHook] screen profile fail: " + t)
                PROFILE_61
            }
        }
        const val PKG_GMS = "com.google.android.gms"
        const val PKG_APP = "com.fxxkmoondrop.secret"
        const val ACTION_TRIGGER = "com.fxxkmoondrop.secret.FASTPAIR_TRIGGER"
        const val EXTRA_DEVICE_NAME = "device_name"
        /** 首选自定义图标路径（放到 Download 目录即可被读取） */
        const val ICON_PATH = "/data/user/0/com.google.android.gms/files/moondrop_icon.png"
        const val MOD_ASSET_ICON = "ga2_icon.png" // alpha1.14fix4: 内置默认图标

        /** v1.0: 三模式按钮 -> FxxkMoondrop 应用（GAIA 降噪控制） */
        const val ACTION_MODE_CHANGED = "com.fxxkmoondrop.secret.FASTPAIR_MODE_CHANGED"
        const val EXTRA_MODE = "mode"
        /** alpha1.20: 弹窗按钮高亮双向同步 */
        const val ACTION_MODE_STATE = "com.fxxkmoondrop.secret.FASTPAIR_MODE_STATE"
        const val ACTION_MODE_REQUEST = "com.fxxkmoondrop.secret.FASTPAIR_MODE_REQUEST"
        /** alpha2.22: app -> GMS 广播 ANC 能力状态，驱动弹窗降噪按钮三态 */
        const val ACTION_ANC_STATUS = "com.fxxkmoondrop.secret.FASTPAIR_ANC_STATUS"
        /** alpha2.27: app -> GMS 广播电量更新（左耳/右耳/系统值） */
        const val ACTION_BATTERY_UPDATE = "com.fxxkmoondrop.secret.FASTPAIR_BATTERY_UPDATE"
        const val EXTRA_ANC_STATUS = "status"
        @JvmField @Volatile
        var sLastMode = -1 // 最近一次收到的模式状态（-1=未知/不高亮）
        /** alpha2.22: 最近一次 ANC 能力状态 0=探测中 1=有ANC 2=无ANC/截断；默认0保守禁用 */
        @JvmField @Volatile
        var sAncStatus = 0

        /** 【对接广播】弹窗点击"确定"后发出（extra: device_name），应用监听后执行真实连接 */
        const val ACTION_CONNECTED = "com.fxxkmoondrop.secret.FASTPAIR_CONNECTED"
        // alpha2.7: GMS HalfSheet 弹窗关闭回调（模拟连接弹窗消失后应用恢复模拟状态）
        const val ACTION_FP_SHEET_CLOSED = "com.fxxkmoondrop.secret.FASTPAIR_SHEET_CLOSED"
        const val ACTION_LE_ADDR_FOUND = "com.fxxkmoondrop.secret.ACTION_LE_ADDR_FOUND"
        const val ACTION_REQ_LE_SCAN = "com.fxxkmoondrop.secret.ACTION_REQ_LE_SCAN"
        /** alpha1.34: 应用探测本模块是否激活（PING -> PONG 回显） */
        const val ACTION_FASTPAIR_PING = "com.fxxkmoondrop.secret.FASTPAIR_PING"
        const val ACTION_FASTPAIR_PONG = "com.fxxkmoondrop.secret.FASTPAIR_PONG"
        const val EXTRA_LE_ADDR = "addr"
        /** 【对接广播】TRIGGER 的 extra：左耳电量（0-100，-1/缺失=不显示） */
        const val EXTRA_BATTERY_LEFT = "battery_left"
        /** 【对接广播】TRIGGER 的 extra：右耳电量（0-100，-1/缺失=不显示） */
        const val EXTRA_BATTERY_RIGHT = "battery_right"
        /** 弹窗关闭延迟：UI 呈现"正在连接…"的时长 */
        const val CONNECT_DELAY_MS = 1000L

        @JvmField @Volatile
        var sDataProvider: FastPairDataProvider? = null

        /** 【合入点】注入真实数据源（同进程）。跨进程场景请改用 ACTION_TRIGGER 广播传数据。 */
        @JvmStatic
        fun setDataProvider(provider: FastPairDataProvider?) {
            sDataProvider = provider
            Log.d(TAG, "[FastPairHook] data provider set: " + (provider != null))
        }

        /*** 当前 HalfSheet Activity 引用（用于接管连接回报） */
        @JvmField @Volatile
        var sHalfSheetActivity: android.app.Activity? = null
        /** alpha1.14fix2: ACL 自动弹窗延迟——等 GAIA 连接+电量就绪，ACL 仅作兜底 */
        const val ACL_POSTSHOW_DELAY_MS = 2000L // alpha2.26.3: 2s 即弹（GAIA 后台并行连接）
        /** 弹窗重复保护：距上次显示不足该时长则跳过兜底弹窗 */
        const val ACL_REPEAT_GUARD_MS = 60000L
        @JvmField @Volatile
        var sLastShowMs = 0L
        /** 最近一次弹窗的设备名（由广播 extra/provider/蓝牙真实名提供；null=未触发） */
        @JvmField @Volatile
        var sLastDeviceName: String? = null
        /** 电量渲染值：经 Intent 从触发进程传入渲染进程；-1=未知不显示 */
        @JvmField @Volatile
        var sBatteryLeft = -1
        @JvmField @Volatile
        var sBatteryRight = -1
        /** alpha2.27: GAIA 电量回包后刷新弹窗 */
        @JvmField @Volatile
        var sBatteryOverlayActivity: android.app.Activity? = null
        @JvmField @Volatile
        var sBatterySysLevel = -1
        /*** central_btn 资源 id（运行时解析后缓存） */
        @JvmField @Volatile
        var sCentralBtnId = 0

        @JvmField @Volatile
        var sAppContext: Context? = null
        @JvmField @Volatile
        var sModCtx: Context? = null // alpha1.14fix4: 模块 APK context（读内置 assets）
        @JvmField @Volatile
        var sGmsCl: ClassLoader? = null
        @JvmField @Volatile
        var sModule: XposedModule? = null

        const val MODE_OFF = 0
        const val MODE_ANC = 1
        const val MODE_TRANS = 2
        const val MODE_WIND = 3

        @JvmField @Volatile
        var sLeScanning = false
        @JvmField @Volatile
        var sLastScanReqMs = 0L
        @JvmField @Volatile
        var sLastPushedAddr: String? = null
        @JvmField @Volatile
        var sLastPushMs = 0L
    }
}
