package com.fxxkmoondrop.secret

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * "关于"页。
 *
 * alpha2.4: 首次按官方 M3 样式重构（头部信息卡 + 分组卡）。
 * alpha2.52: 二次重构，对齐 Material 3 规范：
 *  - 统一 16dp 横向栅格 / 8dp 纵向节奏，段落之间 24dp
 *  - 头部信息卡：96dp 图标容器（24dp 圆角）+ 24sp 名称 + 主色版本行 + 副标题
 *  - 行项一律复用 M3Ui.listRow + groupCard（去掉内联复制的卡片样式）
 *  - 新增「许可」分组，补齐开源信息
 */
class AboutFragment : Fragment() {

    private val repoUrl = "https://github.com/bqj6666/FxxkMoondrop"

    private fun dp(v: Int): Int = Math.round(requireContext().resources.displayMetrics.density * v)

    private fun spacer(h: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    /** 版本号：`V3.0.3`（与主页一致）。唯一来源是 PackageManager，不硬编码。
     *  3.0.3: 去掉 "Alpha" 后缀 —— 3.0 起已是正式版，界面不该再挂 alpha 字样。 */
    private fun verText(): String {
        val vn: String? = try {
            requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) { null }
        if (vn.isNullOrBlank()) return ""
        return "V" + vn.removePrefix("alpha")
    }

    private fun openUrl(url: String) {
        try {
            requireActivity().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { }
    }

    /** M3 段落卡：标题 + 正文（无描边、20dp 圆角，与 groupCard 同源样式） */
    private fun noteCard(lead: String, body: String, pal: ThemeUtil.Palette): View {
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(16), dp(18), dp(16))
        box.background = M3Ui.cardBg(requireContext(), pal, 20)
        val t1 = TextView(requireContext())
        t1.text = lead
        t1.textSize = 16f
        t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t1.setTextColor(pal.onSurface)
        box.addView(t1, LinearLayout.LayoutParams(-2, -2))
        val t2 = TextView(requireContext())
        t2.text = body
        t2.textSize = 14f
        t2.setTextColor(pal.onVariant)
        t2.setLineSpacing(dp(2).toFloat(), 1.25f)
        t2.setPadding(0, dp(8), 0, 0)
        box.addView(t2, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    /**
     * 更新日志（3.2.11 起**纯联网**）。
     *
     * 数据源只有 GitHub release 的 body 一个。APK 内不再内置任何日志副本 ——
     * 那份副本每次发版都要手工同步，迟早与仓库里的 CHANGELOG.md 对不上，
     * 与其维护两份不如只留权威的那一份。
     *
     * 先摆一个 loading 占位，拿到结果后整体重排；内容少时贴合高度，
     * 超上限才内部滚动 —— 不写死高度，避免短内容留大片空白。
     */
    private fun showChangelog() {
        val pal = ThemeUtil.Palette(requireContext())
        val (dlg, body) = M3Ui.materialDialog(requireContext(), pal.primary, pal.card)

        body.addView(M3Ui.dialogTitle(requireContext(),
                Lang.t("更新日志", "Changelog"), pal.onSurface), LinearLayout.LayoutParams(-1, -2))

        val ver = TextView(requireContext())
        ver.text = verText()
        ver.textSize = 14f
        ver.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ver.setTextColor(pal.primary)
        ver.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(ver, LinearLayout.LayoutParams(-1, -2))
        body.addView(spacer(dp(16)))

        val content = LinearLayout(requireContext())
        content.orientation = LinearLayout.VERTICAL
        content.addView(M3Ui.loadingRow(requireContext(), pal,
                Lang.t("正在获取更新日志…", "Fetching the changelog…")),
                LinearLayout.LayoutParams(-1, -2))

        // 宽度 = 弹窗宽（0.84 屏宽，见 M3Ui.materialDialog）减去 body 左右各 24dp 内边距
        val availW = (resources.displayMetrics.widthPixels * 0.84f).toInt() - dp(48)
        val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()

        val contentSv = ScrollView(requireContext())
        contentSv.addView(content)
        body.addView(contentSv,
                LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        body.addView(spacer(dp(16)))
        val actions = LinearLayout(requireContext())
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        actions.addView(M3Ui.textButton(requireContext(), pal,
                Lang.t("关闭", "Close")) { dlg.dismiss() },
                LinearLayout.LayoutParams(-2, -2))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        dlg.show()

        // 进弹窗自动查一次（走 6 小时缓存，不重复打网络）。
        // 联网开关关闭时根本进不到这里 —— 入口行本身就不显示。
        checkUpdate(force = false) { res ->
            if (dlg.isShowing) renderRemoteNotes(content, contentSv, actions, res, availW, maxH, pal)
        }
    }

    /**
     * 把联网结果渲染进弹窗，并重算滚动高度。
     *
     * 高度必须重算：初始高度是按 loading 占位量出来的，换成整篇日志后不重算，
     * 要么留一大片空白，要么内容被截断。
     */
    private fun renderRemoteNotes(content: LinearLayout, sv: ScrollView, actions: LinearLayout,
                                  res: UpdateChecker.Result,
                                  availW: Int, maxH: Int, pal: ThemeUtil.Palette) {
        try {
            content.removeAllViews()
            val ctx = requireContext()

            fun line(msg: String, color: Int, sizeF: Float): TextView {
                val t = TextView(ctx)
                t.text = msg
                t.textSize = sizeF
                t.setTextColor(color)
                t.setLineSpacing(dp(2).toFloat(), 1.25f)
                return t
            }

            fun notesBlock(notes: String?) {
                content.addView(line(
                        if (notes.isNullOrBlank())
                            Lang.t("本版没有更新说明", "No release notes for this build")
                        else prettyNotes(notes),
                        pal.onVariant, 13f), LinearLayout.LayoutParams(-1, -2))
            }

            when (res) {
                is UpdateChecker.Result.Disabled ->
                    content.addView(line(Lang.t("联网检查更新已关闭，无法获取更新日志",
                                    "Online update check is off; the changelog is unavailable"),
                            pal.onVariant, 13f), LinearLayout.LayoutParams(-1, -2))

                is UpdateChecker.Result.Failed ->
                    content.addView(line(Lang.t("无法获取更新日志，请确认网络后重试",
                                    "Could not fetch the changelog; verify your connection and try again"),
                            pal.onVariant, 13f), LinearLayout.LayoutParams(-1, -2))

                is UpdateChecker.Result.UpToDate -> {
                    // 已是最新：远端 latest 就是本机这一版，它的说明正是「本版改了什么」
                    content.addView(line(Lang.t("已是最新版本（" + res.currentLabel + "）",
                                    "Up to date (" + res.currentLabel + ")"),
                            pal.green, 13f), LinearLayout.LayoutParams(-1, -2))
                    content.addView(spacer(dp(10)))
                    notesBlock(res.notes)
                }

                is UpdateChecker.Result.Available -> {
                    val label = res.info.versionName ?: res.info.tag
                    val head = line(Lang.t("最新版 " + label + " 的更新内容",
                            "What's new in " + label), pal.primary, 15f)
                    head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    content.addView(head, LinearLayout.LayoutParams(-1, -2))
                    content.addView(spacer(dp(8)))
                    notesBlock(res.info.notes)
                    // 「下载」放弹窗底部的动作行，而不是塞进滚动内容里 ——
                    // 塞进去要滚到最后才看得见，而且和「关闭」分处两地，姿势与
                    // 「检查更新」弹窗也不一致。点它直接交给系统浏览器，不在应用内下载。
                    val lp = LinearLayout.LayoutParams(-2, -2)
                    lp.marginStart = dp(8)
                    actions.addView(M3Ui.filledButton(requireActivity(), pal,
                            Lang.t("下载 " + label, "Download " + label)) {
                        openUrl(res.info.downloadUrl)
                    }, lp)
                }
            }

            // 与初次构建同一套测量方式，避免顺序不同造成高度跳变
            content.measure(
                    View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val lp = sv.layoutParams
            lp.height = if (content.measuredHeight > maxH) maxH
                        else LinearLayout.LayoutParams.WRAP_CONTENT
            sv.layoutParams = lp
        } catch (t: Throwable) {
            // 弹窗里的渲染失败不值得打扰用户
        }
    }

    // ── 3.2.11: 联网检查更新 ──────────────────────────────────────

    /**
     * 后台检查更新，结果回主线程。
     *
     * UpdateChecker.check 会阻塞（最长 5s 连接 + 8s 读取），必须在子线程。
     * 联网开关关闭时它内部直接返回 Disabled，不会建立任何连接。
     */
    private fun checkUpdate(force: Boolean,
                            onResult: (UpdateChecker.Result) -> Unit) {
        val appCtx = requireContext().applicationContext
        Thread {
            val res = try {
                UpdateChecker.check(appCtx, force)
            } catch (t: Throwable) {
                UpdateChecker.Result.Failed      // 兜底：联网问题绝不允许冒泡到 UI
            }
            activity?.runOnUiThread { if (isAdded) onResult(res) }
        }.start()
    }

    /**
     * 「检查更新」行：强制重查（绕过缓存），结果用一个小弹窗明确告知。
     *
     * 两个动作必须在**同一行**、右对齐、间距 8dp，确认动作在最右（M3 弹窗规范）。
     * 「下载」要等联网结果回来才知道有没有，所以先建好动作行、再把按钮插进去：
     * 直接 body.addView 追加会另起一行，把「关闭」和「下载」拆成上下两块，
     * 看上去根本不像一个弹窗。
     */
    private fun manualCheckUpdate() {
        val pal = ThemeUtil.Palette(requireContext())
        val (dlg, body) = M3Ui.materialDialog(requireContext(), pal.primary, pal.card)
        body.addView(M3Ui.dialogTitle(requireContext(),
                Lang.t("检查更新", "Check for updates"), pal.onSurface),
                LinearLayout.LayoutParams(-1, -2))
        body.addView(spacer(dp(10)))

        val msg = TextView(requireContext())
        msg.text = Lang.t("正在检查…", "Checking…")
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(2).toFloat(), 1.25f)
        body.addView(msg, LinearLayout.LayoutParams(-1, -2))
        body.addView(spacer(dp(16)))

        val actions = LinearLayout(requireContext())
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        actions.addView(M3Ui.textButton(requireContext(), pal,
                Lang.t("关闭", "Close")) { dlg.dismiss() },
                LinearLayout.LayoutParams(-2, -2))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        dlg.show()

        checkUpdate(force = true) { res ->
            msg.text = when (res) {
                is UpdateChecker.Result.Disabled ->
                    Lang.t("联网检查更新已关闭，可在「设置 → 更新」中开启",
                            "Online update check is off; enable it in Settings → Updates")
                is UpdateChecker.Result.UpToDate ->
                    Lang.t("已是最新版本（" + res.currentLabel + "）",
                            "You are up to date (" + res.currentLabel + ")")
                is UpdateChecker.Result.Available ->
                    Lang.t("发现新版本 " + (res.info.versionName ?: res.info.tag),
                            "New version available: " + (res.info.versionName ?: res.info.tag))
                is UpdateChecker.Result.Failed ->
                    Lang.t("检查失败，请确认网络后重试",
                            "Check failed; verify your connection and try again")
            }
            if (res is UpdateChecker.Result.Available) {
                // 插到动作行最右（「关闭」之后）—— 确认动作放最右是 M3 的规定顺序
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.marginStart = dp(8)
                actions.addView(M3Ui.filledButton(requireActivity(), pal,
                        Lang.t("下载", "Download")) { openUrl(res.info.downloadUrl) }, lp)
            }
        }
    }

    /** 把 release body 的 markdown 粗加工成可读纯文本（不做完整渲染，够看即可）。 */
    private fun prettyNotes(raw: String): String {
        val out = ArrayList<String>()
        for (line in raw.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                t.startsWith("> [!") -> Unit                    // GitHub 警示块标记
                t == ">" -> Unit
                t.startsWith(">") -> out.add(t.removePrefix(">").trim())
                t.startsWith("####") -> out.add(t.removePrefix("####").trim())
                t.startsWith("###") -> out.add(t.removePrefix("###").trim())
                t.startsWith("##") -> out.add(t.removePrefix("##").trim())
                t.startsWith("#") -> out.add(t.removePrefix("#").trim())
                t.startsWith("- ") || t.startsWith("* ") -> out.add("· " + t.substring(2))
                else -> out.add(t)
            }
        }
        return out.joinToString("\n").trim()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        Lang.refresh(requireContext())
        val pal = ThemeUtil.Palette(requireContext())
        val act = requireActivity()

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarH = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        root.setPadding(0, statusBarH, 0, 0)
        // alpha2.53: M3 LargeTopAppBar —— 大标题随滚动收缩（对齐官方）
        val page = M3Ui.largeHeaderPage(act, pal, Lang.t("关于", "About"))

        val sv = page.sv
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(28))

        // ── 头部信息卡（M3 large icon 规格：96dp 容器 / 24dp 圆角 / 72dp 图标）──
        val header = LinearLayout(requireContext())
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        header.setPadding(dp(24), dp(28), dp(24), dp(26))
        header.background = M3Ui.cardBg(requireContext(), pal, 28)

        val iconWrap = LinearLayout(requireContext())
        iconWrap.gravity = Gravity.CENTER
        val iwBg = GradientDrawable()
        iwBg.setColor(pal.container)
        iwBg.setCornerRadius(dp(24).toFloat())
        iconWrap.background = iwBg
        val iconView = ImageView(requireContext())
        try {
            iconView.setImageDrawable(requireContext().packageManager
                    .getApplicationIcon(requireContext().packageName))
        } catch (_: Exception) { }
        iconWrap.addView(iconView, LinearLayout.LayoutParams(dp(72), dp(72)))
        header.addView(iconWrap, LinearLayout.LayoutParams(dp(96), dp(96)))

        header.addView(spacer(dp(16)))
        val appTitle = TextView(requireContext())
        appTitle.text = "FxxkMoondrop"
        appTitle.textSize = 24f
        appTitle.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        appTitle.setTextColor(pal.onSurface)
        header.addView(appTitle, LinearLayout.LayoutParams(-2, -2))

        header.addView(spacer(dp(6)))
        val ver = TextView(requireContext())
        ver.text = verText()
        ver.textSize = 14f
        ver.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ver.setTextColor(pal.primary)
        header.addView(ver, LinearLayout.LayoutParams(-2, -2))

        header.addView(spacer(dp(8)))
        val tagline = TextView(requireContext())
        tagline.text = Lang.t("Moondrop 蓝牙耳机助手 · GAIA 直连",
                "Moondrop Bluetooth Earbud Assistant · GAIA Direct")
        tagline.textSize = 12f
        tagline.setTextColor(pal.onVariant)
        tagline.alpha = 0.85f
        tagline.gravity = Gravity.CENTER
        header.addView(tagline, LinearLayout.LayoutParams(-2, -2))
        box.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ── 项目 ──
        box.addView(spacer(dp(24)))
        box.addView(M3Ui.sectionTitle(act, pal, Lang.t("项目", "Project")))
        // 「检查更新」「更新日志」都是联网功能：开关关着还留着入口，
        // 点下去必然失败，不如干脆不显示。
        val projRows = ArrayList<View>()
        if (UpdateChecker.isEnabled(requireContext())) {
            projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_refresh,
                    Lang.t("检查更新", "Check for updates"),
                    Lang.t("查询是否有新版本", "Query for a newer version"),
                    M3Ui.chevron(act, pal.onVariant)) { manualCheckUpdate() })
            projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_description,
                    Lang.t("更新日志", "Changelog"),
                    Lang.t("本版变更", "Changes in this build"),
                    M3Ui.chevron(act, pal.onVariant)) { showChangelog() })
        }
        projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_code,
                Lang.t("GitHub 仓库", "GitHub Repository"),
                Lang.t("查看源码与更新日志", "Source code and changelog"),
                M3Ui.chevron(act, pal.onVariant)) { openUrl(repoUrl) })
        projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_bug_report,
                Lang.t("反馈问题", "Report an issue"),
                Lang.t("提交适配问题与日志", "Submit an issue with logs"),
                M3Ui.chevron(act, pal.onVariant)) { openUrl(repoUrl + "/issues") })
        projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_person,
                Lang.t("作者", "Author"), "bqj6666", null, null))
        projRows.add(M3Ui.listRow(act, pal, R.drawable.ic_group,
                Lang.t("协助者", "Contributors"),
                "Deepseek · Qwen · ChatGPT · Kimi", null, null))
        box.addView(M3Ui.groupCard(act, pal, *projRows.toTypedArray()),
                LinearLayout.LayoutParams(-1, -2))

        // ── 许可 ──
        box.addView(spacer(dp(24)))
        box.addView(M3Ui.sectionTitle(act, pal, Lang.t("许可", "License")))
        box.addView(M3Ui.groupCard(act, pal,
                M3Ui.listRow(act, pal, R.drawable.ic_copyright,
                        Lang.t("开源许可", "Open-source license"),
                        Lang.t("GNU GPL v3.0 · 允许修改与再分发",
                                "GNU GPL v3.0 · modification and redistribution allowed"),
                        M3Ui.chevron(act, pal.onVariant)) { openUrl(repoUrl + "/blob/main/LICENSE") }),
                LinearLayout.LayoutParams(-1, -2))

        // ── 说明 ──
        box.addView(spacer(dp(24)))
        box.addView(M3Ui.sectionTitle(act, pal, Lang.t("说明", "Notes")))
        // alpha2.53: 按当前实际能力重写说明（原来只有一条，且落后于功能）
        val notes = arrayOf(
                arrayOf(
                        Lang.t("耳机连接时自动弹出 FastPair 卡片", "Auto popup FastPair card when earbuds connect"),
                        Lang.t("显示名称 / 电量 / MAC；卡片里的降噪按钮直连耳机下发命令，" +
                                "与软件内按钮行为一一对应。",
                                "Name / battery / MAC; the noise-control buttons in the card send commands straight to the earbuds, one-to-one with the in-app buttons.")),
                arrayOf(
                        Lang.t("直连协议：GAIA BLE", "Direct link: GAIA BLE"),
                        Lang.t("读取与设置电量、降噪（关闭 / 降噪 / 透传 / 抗风）、空间音频与追踪模式、" +
                                "增益、指示灯。地址全动态发现，零硬编码。",
                                "Reads and sets battery, noise control (off / ANC / transparency / wind), spatial audio and tracking mode, gain and LED. Addresses are discovered dynamically with zero hardcoding.")),
                arrayOf(
                        Lang.t("系统界面注入（需 LSPosed）", "System UI injection (requires LSPosed)"),
                        Lang.t("在 Google 快速配对弹窗与「设置 → 蓝牙 → 设备详情」注入控制面板，" +
                                "样式与软件内一致，并随连接状态自动启用或禁用。",
                                "A control panel is injected into the Google Fast Pair popup and Settings → Bluetooth → device details. It matches the in-app styling and enables or disables itself with the connection state.")),
                arrayOf(
                        Lang.t("自定义映射", "Custom mapping"),
                        Lang.t("可逐档指定发给耳机的设备码，并自定义空间音频追踪标签。" +
                                "未手动修改的档位跟随型号档案，可一键重置。",
                                "Pick the device code each level sends, and rename the spatial-audio tracking labels. Untouched levels follow the device profile; everything resets in one tap.")),
                arrayOf(
                        Lang.t("外观与语言", "Appearance and language"),
                        Lang.t("跟随系统 / 浅色 / 深色、动态取色、AMOLED 纯黑、种子颜色，" +
                                "以及跟随系统 / 中文 / English 三档语言。",
                                "Follow system / light / dark, dynamic color, AMOLED pure black, seed colors, and follow-system / Chinese / English.")),
                arrayOf(
                        Lang.t("隐私", "Privacy"),
                        Lang.t("不联网上传任何数据。「日志抓取」只在手动导出时打包设备信息与运行日志，" +
                                "导出前会展示隐私声明。",
                                "Nothing is uploaded. \"Log capture\" only packages device info and runtime logs when you export manually, and shows a privacy notice first.")),
                arrayOf(
                        Lang.t("兼容性", "Compatibility"),
                        Lang.t("系统界面注入需 Root / LSPosed；蓝牙与通知权限用于设备发现与状态提示。" +
                                "实际可用的控制项由型号档案决定。",
                                "System UI injection needs Root / LSPosed; Bluetooth and notification permissions cover discovery and status. Which controls exist is decided by the device profile."))
        )
        for (n in notes) {
            box.addView(noteCard(n[0], n[1], pal), LinearLayout.LayoutParams(-1, -2))
            box.addView(spacer(dp(12)))
        }

        sv.addView(box)
        root.addView(page.container, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}
