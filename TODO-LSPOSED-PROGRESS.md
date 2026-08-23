# FxxkMoondrop · LSPosed M3 设计移植 · 进度档案（2026-08-23 更新）

> 本文件是对话崩溃恢复用的任务档案。修改源码前先读本文件 + 下方"待办"。
> ⚠️ 基线以本机实际为准：dumpsys package 查 versionCode。此前档案写 alpha2.3(221) 已过期，设备实际到 alpha2.8(223)。

## 当前版本
- alpha2.9（vc 224，已安装验证）：build2/alpha224-lsposed-signed.apk

## alpha2.10 修复（2026-08-23）
- M3 合规统一：对话框标题 19\/20sp → 22sp（titleLarge，Main 权限\/图标\/简单弹窗 + Settings 三个弹窗）
- Main.showSimpleDialog 对齐新规格：圆角 28 + 0.84 宽 + cardSurfaceColor 底 + 标题 onSurface 色
- 权限弹窗 okBtn 统一走 makeMaterialTextButton（14sp labelLarge）；图标弹窗箭头 "›" 文本 → M3Ui.chevron 16dp 矢量
- M3Ui.listRow 图标 22 → 24dp（与 navRow 统一）；主页降噪分区标题 12 → 13sp（对齐 sectionTitle）
- 权限\/图标弹窗卡片底部 padding 10 → 24dp
- .bak 文件不影响构建（javac 只收集 .java），未清理

## alpha2.9 修复（2026-08-23）
- 问题：模拟连接弹窗自动恢复后，主页电量行残留显示（86%/72% 或 --%）
- 根因：恢复只改模拟态/清电量数据，UI 无刷新触发——updateStatus()（onResume 调用）只刷英雄卡；stateReceiver 只刷降噪
- 修复：①MainActivity.updateStatus() 末尾加 updateStatusPanel()（onResume 同步面板）②HeadsetReceiver 恢复模拟态后发 STATE_UPDATED 广播（setPackage 应用，前台立即刷新）
- 验证：主页 GAIA/耳机显示未连接、电量行隐藏、降噪面板常驻置灰 ✅
- 注意：LSPosed 重装后有"模块尚未激活"heads-up 通知属正常提示，截图前左滑划掉

## 版本线（2026-08-23）
- alpha2.3(221)：英雄卡 V2.3Alpha 贴勾、权限行去 alpha1.35 字样、模拟连接恢复链路（FastPairHook onDestroy → FASTPAIR_SHEET_CLOSED → HeadsetReceiver 恢复）、降噪面板未连接常驻置灰、外观开关 recreate 延迟 350ms
- alpha2.8(223)：电量行动画（battRowShown fade+slide，alpha2.8 注释在 MainActivity）
- alpha2.9(224)：电量行残留显示修复（本档案）

## 技术坑（铁证级）
- 模拟连接弹窗走 GMS FastPair 路径（ACTION_FP_TRIGGER → HalfSheetActivity），不走 PopupOverlay！PopupOverlay hide 钩子仅覆盖自带弹窗
- FastPairHookEntry onDestroy hook：框架层 Activity.onDestroy + cname.contains("HalfSheet") + sHalfSheetActivity==act 三重过滤
- HeadsetReceiver 恢复用常量 "AA:BB:CC:DD:EE:FF"（PopupOverlay.SIM_MAC 是 private，同 SettingsActivity.SIM_MAC）
- updateStatus() ≠ updateStatusPanel()：前者只刷英雄卡+按钮，后者刷 GAIA/耳机/电量行；onResume 只调前者
- GMS 冷启动慢：模拟连接后弹窗可能 10s+ 才显示，验证等 logcat 信号
- LinearLayout(HORIZONTAL)+gravity=CENTER 使 wrap 子项塌陷 1px → texts 必须 weight=1
- 主页呼吸动画 → uiautomator dump idle 失败，用 dumpsys activity top 或 logcat
- Eta 宿主强前台：am start 后同命令内 screencap
- 装 APK 版本号必须 > 当前 vc，否则 INSTALL_FAILED_VERSION_DOWNGRADE

## 构建命令
cd /workspace/alpha_src && FXXK_KEYPASS=fxxk2026 python3 tools/build_m3.py --new alpha2.9 --vc 224 --out build2/alpha224-lsposed-signed.apk

## M3 合规审查（2026-08-23，仅审查未改动）
### ✅ 已符合
- 主题：Material3 DayNight + 动态取色(system_accent1) + AMOLED + 种子色（ThemeUtil，LSPosed 架构对位）
- 组件：MaterialToolbar 64dp / MaterialButton(52dp 高,圆角28) / MaterialCardView(28dp) / MaterialSwitch / BottomNavigationView(pill+动态色) / Ripple
- 字体：Roboto 系（normal/medium/black），层级 26/22/19/16/15/14/13/12/11
- 间距：8dp 网格；弹窗 PopupOverlay M3 Dialog 28+1.5dp 描边+16elevation+spring 入场
- 主页英雄卡：状态指示器呼吸 + API 徽章 + 拟态 LSPosed StatusHeader

### ⚠️ 不一致点（未改，待用户确认）
1. 对话框标题 19/20/22sp 三档混用：Main:573,847,1469=19sp；Settings:593,631=20sp；RootWarn(Main:996,Settings:494)=22sp → M3 标准 titleLarge 22sp
2. Main.showSimpleDialog(1461) 圆角24/82%宽/container色 vs Settings 系列 28/84%/card色（老实现未跟进）
3. 按钮三规格：权限弹窗 okBtn 15sp 圆角20 透明底 / makeMaterialTextButton 14sp 圆角22 灰底 / makeSmallButton 14sp 圆角24（M3 标准 labelLarge 14sp）
4. 权限/图标弹窗 cardBody 底部 padding 10dp（Main:565,842；标准 22-24）
5. M3Ui.listRow 图标 22dp vs navRow 24dp（M3 列表图标 24dp）
6. 图标弹窗箭头 "›" 文本 20sp（Main:889）vs M3Ui.chevron 矢量 16dp
7. 主页降噪分区标题 12sp（Main:276）vs 设置页 sectionTitle 13sp
8. src 残留 .bak/.bak2/.bak3/.bak4/.bak5/.bak_m3/.legacy_bak 13 个（不参与 javac，仅卫生问题）

### 不改（有依据）
- FastPairHook 170px/2050px：GMS 弹窗 px 坐标系；PopupOverlay 断开卡 20/16：通知角色卡片
- 主页 root 24dp vs 其他页 16dp：英雄页边距（LSPosed 同款）
