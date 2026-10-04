#!/bin/sh
# FxxkMoondrop · 本机 Gradle 9 调用封装
#
# 为什么需要这个：本机是 PROot/容器环境，JDK 的 Files.getFileStore() 对
# 除 /dev 外的所有路径都抛 "Mount point not found"（/proc/self/mountinfo
# 只有 27 行且无根挂载记录）。Gradle 8.9 不查文件系统所以没事，
# Gradle 9 建临时目录要查，因此需要两条同时生效的绕行：
#
#   1. GRADLE_USER_HOME 指向 /dev 下的**真实目录**（不能用符号链接，实测也炸）
#   2. launcher JVM 的 -Djava.io.tmpdir 指向 /dev 下
#
# ⚠️ JAVA_OPTS / GRADLE_OPTS / -Dorg.gradle.jvmargs 都**无效**：
#    FileSystem 服务在读这些之前就建好了，必须直接启 launcher。
#
# ⚠️ /dev 是 tmpfs，重启即失。缓存没了就重新执行 setup 段。
#
# ⚠️ gradle.properties 里的 aapt2FromMavenOverride **必须保留**。
#    本机是 aarch64，官方 aapt2 是 x86_64 二进制，必须经 qemu 包装才能跑；
#    删掉它会报 "AAPT2 Daemon startup failed"。
#    该行必须与 AGP 版本严格同步 —— AGP 8.6.1 时它指向 aapt2-861，
#    升到 9.4.1 后若不更新，资源打包会**静默失败**：
#    assembleRelease 每一步都报成功，产物却缺 AndroidManifest.xml 与整个 res/。
#    详见 gradle.properties 里的注释。
#
# 用法：./g9.sh assembleDebug
#      ./g9.sh testDebugUnitTest
#      ./g9.sh :app:assembleRelease -PfxxkKeypass=...

set -e

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

GUH=/dev/gh2
export GRADLE_USER_HOME="$GUH"

# 缓存缺失时重建（首次约需数分钟）
if [ ! -d "$GUH/caches" ] || [ ! -d "$GUH/wrapper" ]; then
    echo "[g9] 重建 $GUH ..."
    mkdir -p "$GUH/tmp"
    [ -d /root/.gradle/caches ] && cp -r /root/.gradle/caches "$GUH/" 2>/dev/null || true
    [ -d /root/.gradle/wrapper ] && cp -r /root/.gradle/wrapper "$GUH/" 2>/dev/null || true
fi

TMPD=/dev/gtmp2
mkdir -p "$TMPD"

LAUNCHER=$(find /root/.gradle/wrapper/dists -name 'gradle-launcher-9.6.0.jar' 2>/dev/null | head -1)
[ -z "$LAUNCHER" ] && LAUNCHER=$(find "$GUH/wrapper" -name 'gradle-launcher-*.jar' 2>/dev/null | head -1)

if [ -z "$LAUNCHER" ]; then
    echo "[g9] 找不到 gradle-launcher jar，请先确认 gradle-9.6.0-bin 已下载到 /root/.gradle/wrapper/dists"
    exit 1
fi

# ⚠️ 内存：本机手机总内存约 15.8G，但同时运行微信/浏览器/各系统进程。
# 原来 -Xmx4g 会让 Gradle daemon 吃掉 4G，再加 Kotlin daemon，
# 构建完完进程不退出（已实测残留 5 个守护进程）。
# 2g 足够编译本项目（Compose + R8 编译峰值约 1.2G）。
#
# 同时在脚本退出时停掉 daemon，避免残留。
# 大量构建（release + 单测）请走 GitHub Actions，手机不再磁腿。
GRADLE_OPTS="-Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx2g"
export GRADLE_OPTS

"$JAVA_HOME/bin/java" \
    -Djava.io.tmpdir="$TMPD" \
    -Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 \
    -cp "$LAUNCHER" \
    org.gradle.launcher.GradleMain "$@"
STATUS=$?

# 构建完清场：回收 daemon / Kotlin daemon 的内存。
# 无条件先 kill 一下 --stop 失败也不影响退出码。
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$TMPD" -cp "$LAUNCHER" \
    org.gradle.launcher.GradleMain --stop >/dev/null 2>&1 || true
pkill -f 'kotlin-daemon-embeddable' 2>/dev/null || true

exit $STATUS
