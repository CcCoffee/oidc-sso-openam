#!/usr/bin/env bash
# 停止本地 OpenAM（Tomcat）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CATALINA="$ROOT/.runtime/tomcat/bin/catalina.sh"

if [[ ! -x "$CATALINA" ]]; then
  echo "未找到 Tomcat：$CATALINA" >&2
  exit 1
fi

"$CATALINA" stop || true
echo "已停止 Tomcat。"
