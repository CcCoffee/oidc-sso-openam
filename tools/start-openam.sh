#!/usr/bin/env bash
# 启动本地 OpenAM（Tomcat 11 + OpenAM 16.1.4 WAR），并等待 discovery 端点可用。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$ROOT/.runtime"
CATALINA="$RUNTIME/tomcat/bin/catalina.sh"
OPENAM_URL="${OPENAM_URL:-http://localhost:8090/openam}"

if [[ ! -x "$CATALINA" ]]; then
  echo "未找到 Tomcat：$CATALINA" >&2
  echo "请先按 README 的『准备 OpenAM 运行时』步骤下载并解压 Tomcat 与 OpenAM WAR。" >&2
  exit 1
fi

if curl -sf -o /dev/null "$OPENAM_URL/oauth2/.well-known/openid-configuration"; then
  echo "OpenAM 已在运行：$OPENAM_URL"
  exit 0
fi

echo "启动 Tomcat ..."
"$CATALINA" start

echo "等待 OpenAM 就绪（最长 120 秒）..."
for _ in $(seq 1 60); do
  if curl -sf -o /dev/null "$OPENAM_URL/oauth2/.well-known/openid-configuration"; then
    echo "OpenAM 已就绪：$OPENAM_URL"
    exit 0
  fi
  sleep 2
done

echo "OpenAM 启动超时，请查看 $RUNTIME/logs/catalina.out" >&2
exit 1
