#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建后处理：向已签名 APK 注入 LSPosed 推荐作用域 EDF 并重新签名。
用法: post_edf.py <apk> <scope.list> <ascope.list> <keystore> <ks-pass> <apksigner>
版本：AGP 8.x 签名内嵌 packageRelease（无 signRelease 任务），zip 追加会破坏 v2/v3，
      故只能在最终 APK 上注入后重签。
"""
import subprocess, sys, zipfile, os

def main():
    apk, scope_list, ascope_list, ks, ks_pass, apksigner = sys.argv[1:7]
    with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
        for name, path in (("META-INF/xposed/scope.list", scope_list),
                           ("META-INF/xposed/ascope.list", ascope_list)):
            if path is None or not os.path.isfile(path):
                continue
            if name in z.namelist():
                print("SKIP (already):", name)
                continue
            z.write(path, name)
            print("INJECTED:", name)
    tmp = apk + ".resigned"
    r = subprocess.run([
        apksigner, 'sign', '--ks', ks, '--ks-pass', 'pass:%s' % ks_pass,
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--out', tmp, apk
    ], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-2000:])
        return r.returncode
    os.replace(tmp, apk)
    v = subprocess.run([apksigner, 'verify', '--verbose', apk], capture_output=True, text=True)
    print("VERIFY:", "OK" if v.returncode == 0 else v.stderr[-800:])
    print("POST-EDF OK:", apk)
    return 0

if __name__ == "__main__":
    sys.exit(main())
