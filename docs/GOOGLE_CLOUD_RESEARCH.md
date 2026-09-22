# Fast Pair「Google 云」侧研究报告

> 分支 `FxxkMoondrop&Sony` ・ 2026-09-22
> 全部结论基于本机实测（GMS 26.34.36 / AVIUM UI 16.2.1 / OnePlus 13T）
> 目的：判定「国行 WF-1000XM5 伪支持 Fast Pair」在云侧能走多远

## 1. 三层结构（实测）

| 层 | 位置 | 内容 | 实测证据 |
|---|---|---|---|
| 本地 | `/data/data/com.google.android.gms/files/nearby/shared/hearable_control_settings.pb` | 耳机地址 → ANC 状态 | 82 B，2 条记录 |
| 本地 | `files/nearby-fast-pair/nearby_fast_pair_item_cache.db` | FP 设备缓存（LevelDB） | `*.log` = **0 字节**（本机从未配对真 FP 设备） |
| 本地 | `files/nearby/presence/nearby_presence_device_directory/` | 凭证库（credential book） | 目录存在 |
| 云 | `nearbydevices-pa.googleapis.com` | FP 设备注册 | GMS dex 字符串 |
| 云 | `footprints-pa.googleapis.com` | Footprints 档案 | GMS dex 字符串 |
| 云 | `spot-pa.googleapis.com` | SPOT（Fast Pair 后端服务名） | GMS dex 字符串 |
| 云 | `nearby.googleapis.com` | Nearby | GMS dex 字符串 |

GMS dex 内核心类（命中次数）：`Footprints` 43 ・ `accountKey` 34 ・ `ACCOUNT_KEY` 22 ・
`FootprintsService` 7 ・ `SavedDevicesChanged` 6 ・ `SavedDeviceMetadataFfi` 6 ・
`AccountKeyManager` 5 ・ `AccountKeyRequest/Response` 4 ・ `SavedDevicesEnabled` 3

## 2. 云上到底存什么

- **accountKey** — 256-bit AES，由 anti-spoofing key + 随机数派生，配对时写入用户账号
- **model ID** — 3 字节，广播里为 `0xFE2C` + model ID
- **设备名 / 图像** — 按 model ID 索引，属于公开数据
- **电量** — 每次连接上报
- **SavedDeviceMetadata** — 账号级设备档案

## 3. 官方 10 项功能的云依赖（据 Google 官方规格）

| 功能 | 依赖云 | 对本项目的意义 |
|---|---|---|
| 初始配对半屏通知 | ✗ 本地 | ✅ 已可伪造（`showHalfSheet`） |
| 账号关联设备 | **✓ 云** | ❌ 需真 accountKey |
| 跨设备后续配对通知 | **✓ 云** | ❌ 需真档案 |
| 个性化名称 | ✓ 云（写账号） | ❌ |
| 电池通知 | ✗ 本地 GATT | ✅ 可读真值 |
| Android 11+ 设备详情 | ✗ 本地 | ✅ |
| 查找耳机（FMD） | **✓ 云** | ❌ |
| 离线配对 | ✗ 本地 | ✅ |
| 音频切换 SASS | 部分云 | ⚠️ |
| **Hearable Controls** | **✗ 本地** | ✅ **关键突破口** |

## 4. hearable_control_settings.pb 解析

```
0a 27                          field 1, LEN 39
   0a 11 "24:01:30:B5:9C:39"  field 1, LEN 17  ← 设备地址
   10 02                      field 2, varint 2
   20 02                      field 4, varint 2
   30 01                      field 6, varint 1
   42 05 01 00 01 00 01       field 8, LEN 5   ← ANC 状态组
   4a 05 01 00 01 00 01       field 9, LEN 5
```

**纯 MAC 地址索引，无 accountKey、无签名、无云校验。**
对照 GFPS 规格：Hearable Controls = 消息组 `0x08`，消息码 `0x11` GET / `0x12` SET / `0x13` NOTIFY，
ANC 控制数据 4 字节（版本 / 界面切换 / 可设置 / 当前状态）。
设备详情页的 `HEARABLE_CONTROL_SLICE` 元数据由 GMS 依据此文件写入。

## 5. 判定

| 路径 | 结论 |
|---|---|
| 让 Google 云认为 XM5 是 FP 设备 | ❌ 不可能（需真 accountKey + anti-spoofing 密钥交换，XM5 不广播 ⇒ 无从截获） |
| 让本地 GMS 显示 FP 弹窗 | ✅ 已实现（手工构造 dtok + 直接 startActivity） |
| 让系统蓝牙页出现「耳机控制」 | ✅ 本地 pb 注入即可能 |
| 云端能力（查找设备 / 跨设备 / 账号关联） | ❌ 放弃 |

**核心结论：Hearable Controls 链路完全在本地闭环，云只在"账号级同步"上把关。
对 XM5 而言，本地闭环已经够用 —— 不需要也不可能骗过云。**
