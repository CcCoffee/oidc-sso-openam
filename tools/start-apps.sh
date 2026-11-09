#!/usr/bin/env bash
# 构建并启动三个 Spring Boot 服务：
#   - client-app（app1，端口 8091，注册为 openam-client-1）
#   - client-app（app2，端口 8092，注册为 openam-client-2）
#   - resource-server（端口 8093，JWT 资源服务器）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$ROOT/.runtime"
LOG_DIR="$RUNTIME/logs"
PID_DIR="$RUNTIME/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

cd "$ROOT"

echo "构建项目 ..."
mvn -q -DskipTests package

start_app() {
  local name="$1"; shift
  local port="$1"; shift
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "$name 已在端口 $port 运行，跳过"
    return
  fi
  echo "启动 $name（端口 $port）..."
  nohup java -jar "$@" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$PID_DIR/$name.pid"
}

start_app client-app1 8091 client-app/target/client-app-1.0.0-SNAPSHOT.jar
start_app client-app2 8092 client-app/target/client-app-1.0.0-SNAPSHOT.jar --spring.profiles.active=app2
start_app resource-server 8093 resource-server/target/resource-server-1.0.0-SNAPSHOT.jar

echo "等待服务就绪 ..."
for _ in $(seq 1 60); do
  ok=1
  for port in 8091 8092 8093; do
    curl -s -o /dev/null "http://localhost:$port/" || ok=0
  done
  if [[ "$ok" == "1" ]]; then
    echo "全部服务已启动："
    echo "  http://localhost:8091  （app1）"
    echo "  http://localhost:8092  （app2）"
    echo "  http://localhost:8093  （resource-server，受保护）"
    exit 0
  fi
  sleep 2
done

echo "服务启动超时，请查看 $LOG_DIR 下的日志。" >&2
exit 1
