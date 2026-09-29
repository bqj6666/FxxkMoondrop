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
# 用法：./g9.sh assembleDebug
#      ./g9.sh testDebugUnitTest
#      ./g9.sh :app:assembleRelease

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

exec "$JAVA_HOME/bin/java" \
    -Djava.io.tmpdir="$TMPD" \
    -Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 \
    -cp "$LAUNCHER" \
    org.gradle.launcher.GradleMain "$@"
