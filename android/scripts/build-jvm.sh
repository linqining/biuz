#!/usr/bin/env bash
#
# BiuZ Android JVM 构建入口（供人用；固定本地 Gradle 路径 /Users/mac/opt/gradle/gradle-8.14.3）。
#
# 【并行构建互杀 daemon，禁绕过】多个 Gradle 进程并发构建同一工程会互相停掉对方的
# 共享热 daemon（org.gradle.daemon=true 单例），表象是「Daemon disappeared / 构建随机失败」。
# macOS 无 flock(1)，本脚本用 mkdir 的原子性做互斥锁强制串行：同名目录只有一个进程 mkdir 成功。
# 禁止绕过本脚本并发直接调 gradle（包括 gradlew）。
#
# 用法：android/scripts/build-jvm.sh [gradle 任务与参数…]
#   例：android/scripts/build-jvm.sh :protocol:build
#       android/scripts/build-jvm.sh :protocol:test --console=plain
#   无参数时默认跑 build。

set -euo pipefail

GRADLE="/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle"
LOCK_DIR="/tmp/biuz-android-build.lock"

log() { echo "[build-jvm] $*"; }

lock_pid_alive() {
  local pid
  pid="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

acquire_lock() {
  local missing_pid_rounds=0
  while true; do
    if mkdir "$LOCK_DIR" 2>/dev/null; then
      echo $$ > "$LOCK_DIR/pid"
      log "已取得构建锁 $LOCK_DIR (pid $$)"
      return 0
    fi
    if [ -n "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" ]; then
      missing_pid_rounds=0
      if lock_pid_alive; then
        log "构建锁被 pid $(cat "$LOCK_DIR/pid") 持有，等待…（并行构建互杀 daemon，禁绕过）"
      else
        log "发现残留死锁（持有进程 pid $(cat "$LOCK_DIR/pid") 已不存在），回收后重试…"
        rm -rf "$LOCK_DIR"
        continue
      fi
    else
      # mkdir 与写入 pid 之间有微小窗口：连续两轮（约 4s）仍读不到 pid 才判死锁。
      missing_pid_rounds=$((missing_pid_rounds + 1))
      if [ "$missing_pid_rounds" -ge 2 ]; then
        log "锁目录无 pid 记录（疑似中断残留），回收后重试…"
        rm -rf "$LOCK_DIR"
        missing_pid_rounds=0
        continue
      fi
      log "构建锁刚被创建、pid 未落盘，稍候复判…"
    fi
    sleep 2
  done
}

release_lock() {
  if [ -d "$LOCK_DIR" ] && [ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" = "$$" ]; then
    rm -rf "$LOCK_DIR"
  fi
}
trap release_lock EXIT

[ -x "$GRADLE" ] || { log "错误：未找到 Gradle：$GRADLE"; exit 1; }

acquire_lock

# 固定到 android/ 工程根（脚本所在目录的上一级）。
cd "$(cd "$(dirname "$0")/.." && pwd)"

if [ "$#" -eq 0 ]; then
  set -- build
fi

log "gradle -p $PWD $* --console=plain"
"$GRADLE" -p "$PWD" "$@" --console=plain
