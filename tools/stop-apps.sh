#!/usr/bin/env bash
# 停止 start-apps.sh 启动的三个 Spring Boot 服务。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$ROOT/.runtime/pids"

for name in client-app1 client-app2 resource-server; do
  pid_file="$PID_DIR/$name.pid"
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" && echo "已停止 $name（pid $pid）"
    fi
    rm -f "$pid_file"
  fi
done
