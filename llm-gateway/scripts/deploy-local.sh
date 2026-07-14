#!/usr/bin/env bash
# 本机部署:重建随代码变化的 gateway/ui 并等待健康。
# 由 GitHub Actions self-hosted runner 调用;也可手动执行:bash llm-gateway/scripts/deploy-local.sh
set -euo pipefail

cd "$(dirname "$0")/.."
compose() { docker compose -f docker-compose.yml "$@"; }

echo "==> build gateway ui"
compose build gateway ui

echo "==> up -d --wait gateway ui"
compose up -d --wait gateway ui

echo "==> prune dangling images"
docker image prune -f

echo "==> deploy done"
