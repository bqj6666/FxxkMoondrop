# FxxkMoondrop alpha 构建备忘（当前版本 alpha1.15, code 113）

## 源码
- 工作目录：/data/local/tmp/fuck_andes/alpha_src（linux 环境 /workspace/alpha_src 同一份）
- 源码：src/com/fxxkmoondrop/secret/*.java（当前版本 alpha1.15）
- 备份：backup/MainActivity.bak_alpha113.java（alpha1.12 版 MainActivity）、backup/PermissionChecker.bak_alpha114.java（alpha1.14 版 PermissionChecker）
- 改动记录：backup/alpha113-to-alpha114-PermissionChecker.diff（alpha1.14 两处字符串改动）

## 完整构建流程（已验证，alpha1.14）

### 1. 编译（javac 17，必须 --release 8！）
```bash
cd /workspace/alpha_src
rm -rf classes25 dex25 && mkdir -p classes25 dex25
find src -name "*.java" > /tmp/srcs25.txt
javac --release 8 \
  -classpath /workspace/sdk/android-34-ext12/android.jar:/workspace/alpha_src/xposed-api-stub.jar \
  -d classes25 @/tmp/srcs25.txt
```
注意：不要用 `-bootclasspath android.jar` 方式——JDK 17 下 LambdaMetafactory 缺失会报
`cannot find symbol: method metafactory`。`--release 8` 用 JDK 平台 API 提供 LambdaMetafactory。

### 2. 转 dex（d8）
```bash
/workspace/sdk/android-14/d8 --release \
  --lib /workspace/sdk/android-34-ext12/android.jar \
  --min-api 26 --output dex25 classes25/com/fxxkmoondrop/secret/*.class
```

### 3. 版本号等长替换（alpha1.14→alpha1.15, code 112→113）
```bash
python3 tools/patch_version115.py
```
- 输入：build2/alpha115-signed.apk 的 AndroidManifest.xml；输出：build2/apk/AndroidManifest.xml
- alpha1.13(9字)→alpha1.14(9字) 等长字节替换；versionCode 111→112
- 下次升版本：复制 patch_version115.py 改 114→115 / 112→113，输入改上一版 signed apk
- ⚠️ 不要用 sed 批量替换版本号（连锁替换会搞乱脚本），直接改字面值

### 4. 打包（zip15.py，由 zip13.py sed 改 DEX/OUT 生成）
- 运行：python3 build2/zip15.py，输出 ALL_ALIGNED: True

### 5. 签名（apksigner v2+v3）
```bash
/workspace/sdk/android-14/apksigner sign \
  --ks app2.keystore --ks-pass pass:fxxk2026 --key-pass pass:fxxk2026 \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out build2/alpha115-signed.apk build2/alpha115-aligned-unsigned.apk
/workspace/sdk/android-14/apksigner verify build2/alpha115-signed.apk
```

### 6. 安装
```bash
pm install -r /data/local/tmp/fuck_andes/alpha_src/build2/alpha115-signed.apk
```

## 产物与备份
- build2/alpha115-signed.apk（107297 B）——已装，已备份到 backup/alpha115-signed.apk
- dex25/classes.dex、classes25/（本次编译产物）
- build2/apk/AndroidManifest.xml（版本 alpha1.14/112）

## alpha1.14 改动摘要（移除"无需 Xposed"字样）
- PermissionChecker.java 仅两处 UI 字符串：
  - "GAIA 直连（无需 Xposed）" → "GAIA 直连"
  - "alpha1.0 直连耳机 BLE，无需 LSPosed" → "alpha1.0 直连耳机 BLE"
- 影响：权限弹窗列表项 + 主界面 permStatus 缺项文本（同一 Item 来源）
- 未动：注释、XposedEntry/AncBridge/BootReceiver 等 Xposed 代码逻辑、MainActivity 弹窗实现

## 验证（真实设备）
- 弹窗显示 "✔ GAIA 直连 / alpha1.0 直连耳机 BLE"，无 Xposed/LSPosed 字样（截图确认）
- crash=0；App 进程正常
