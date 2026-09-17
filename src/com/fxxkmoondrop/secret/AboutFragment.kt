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
        t1.textSize = 15f
        t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t1.setTextColor(pal.onSurface)
        box.addView(t1, LinearLayout.LayoutParams(-2, -2))
        val t2 = TextView(requireContext())
        t2.text = body
        t2.textSize = 13f
        t2.setTextColor(pal.onVariant)
        t2.setLineSpacing(dp(2).toFloat(), 1.25f)
        t2.setPadding(0, dp(8), 0, 0)
        box.addView(t2, LinearLayout.LayoutParams(-1, -2))
        return box
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
        box.addView(M3Ui.groupCard(act, pal,
                M3Ui.listRow(act, pal, R.drawable.ic_code,
                        Lang.t("GitHub 仓库", "GitHub Repository"),
                        Lang.t("查看源码与更新日志", "Source code and changelog"),
                        M3Ui.chevron(act, pal.onVariant)) { openUrl(repoUrl) },
                M3Ui.listRow(act, pal, R.drawable.ic_bug_report,
                        Lang.t("反馈问题", "Report an issue"),
                        Lang.t("提交设备适配问题与日志", "Submit device issue with logs"),
                        M3Ui.chevron(act, pal.onVariant)) { openUrl(repoUrl + "/issues") },
                M3Ui.listRow(act, pal, R.drawable.ic_person,
                        Lang.t("作者", "Author"), "bqj6666", null, null),
                M3Ui.listRow(act, pal, R.drawable.ic_group,
                        Lang.t("协助者", "Contributors"),
                        "Deepseek · Qwen · ChatGPT · Kimi", null, null)),
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
                                "未手动修改的档位一律跟随型号档案，随时可一键重置。",
                                "Pick the device code each level sends, and rename the spatial-audio tracking labels. Untouched levels follow the device profile, and everything resets in one tap.")),
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
