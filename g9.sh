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
# ARM64 的 AAPT2 包装器路径放在 ~/.gradle/gradle.properties；本脚本会同步这份本机配置。
# 也可传入 -Pandroid.aapt2FromMavenOverride=/absolute/path/to/aapt2，不要写入仓库配置。
# 包装器版本必须与 AGP 同步，详见 DEVELOPMENT.md 的本机 AAPT2 覆盖说明。
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

# 缓存：优先把磁盘上的 ~/.gradle/caches 用 bind mount 挂进 /dev。
# 直接复制会把 3 GB+ 缓存搬进 tmpfs＝RAM，多轮构建足以把整机推进 swap。
mkdir -p "$GUH"
# 阿里云镜像 init 脚本（dl.google.com 不可达）；/dev 是 tmpfs，每次启动同步一份
[ -f /root/.gradle/init.gradle ] && cp -f /root/.gradle/init.gradle "$GUH/init.gradle" 2>/dev/null || true
if [ -d /root/.gradle/caches ] && ! mountpoint -q "$GUH/caches" 2>/dev/null; then
    mkdir -p "$GUH/caches" "$GUH/wrapper"
    mount --bind /root/.gradle/caches "$GUH/caches" 2>/dev/null || true
    mount --bind /root/.gradle/wrapper "$GUH/wrapper" 2>/dev/null || true
fi
if [ ! -d "$GUH/caches" ] || [ ! -d "$GUH/wrapper" ]; then
    echo "[g9] 重建 $GUH ..."
    mkdir -p "$GUH/tmp"
    [ -d /root/.gradle/caches ] && cp -r /root/.gradle/caches "$GUH/" 2>/dev/null || true
    [ -d /root/.gradle/wrapper ] && cp -r /root/.gradle/wrapper "$GUH/" 2>/dev/null || true
fi

# Changing GRADLE_USER_HOME must not discard the user's machine-local AAPT2 override.
if [ -f "$HOME/.gradle/gradle.properties" ]; then
    cp "$HOME/.gradle/gradle.properties" "$GUH/gradle.properties"
else
    rm -f "$GUH/gradle.properties"
fi

TMPD=/dev/gtmp2
mkdir -p "$TMPD"

# aapt2：ARM64 上必须用 qemu 包装过的 9.4.1（仓库里的路径是固定约定，换机器时用 FM_AAPT2 覆盖）
AAPT2="${FM_AAPT2:-/workspace/tools/aapt2-941/aapt2}"
# 工作区迁移后（2026-10-27）旧路径可能不存在，兜底找当前工作区里的副本
[ -x "$AAPT2" ] || AAPT2="/workspace/mounts/oldworkplace/fm/tools/aapt2-941/aapt2"
[ -d "$AAPT2" ] && AAPT2="$AAPT2/aapt2"
if [ -x "$AAPT2" ]; then
    set -- "$@" "-Pandroid.aapt2FromMavenOverride=$AAPT2"
fi

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

# ⚠️ GRADLE_USER_HOME 在 /dev（tmpfs）＝ 直接吃 RAM。构建多次后缓存能堆到 5 GB+，
# 设备总内存 15 GB，堆起来会开始吃 swap 卡整机。收尾时回收可重建的部分：
#   1. 旧 Gradle 版本缓存（当前 9.6.0，8.x 已无用）
#   2. kotlin-daemon 日志
#   3. 构建缓存超过 600 MB 就清掉（下次首次构建慢一点，好过堆满内存）
rm -rf "$GUH"/caches/8.* 2>/dev/null || true
rm -f "$TMPD"/kotlin-daemon.*.log 2>/dev/null || true
BC="$GUH/caches/build-cache-1"
if [ -d "$BC" ]; then
    SZ=$(du -sm "$BC" 2>/dev/null | cut -f1)
    [ -n "$SZ" ] && [ "$SZ" -gt 600 ] && rm -rf "$BC" 2>/dev/null || true
fi

exit $STATUS
