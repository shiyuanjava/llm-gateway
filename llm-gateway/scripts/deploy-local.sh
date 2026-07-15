#!/usr/bin/env bash
# 本机部署:重建随代码变化的 gateway/ui 并等待健康。
# 由 GitHub Actions self-hosted runner 调用;也可手动执行:bash llm-gateway/scripts/deploy-local.sh
# 敏感变量不入库:runner 的全新 checkout 没有 .env,统一从本机固定路径读取(可用 LLM_GATEWAY_ENV_FILE 覆盖)。
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="${LLM_GATEWAY_ENV_FILE:-$HOME/.llm-gateway/deploy.env}"
ENV_ARGS=()
if [ -f "$ENV_FILE" ]; then
  ENV_ARGS=(--env-file "$ENV_FILE")
elif [ ! -f .env ]; then
  echo "缺少部署环境变量:既没有 $ENV_FILE,当前目录也没有 .env" >&2
  echo "初始化一次即可:mkdir -p ~/.llm-gateway && cp llm-gateway/.env ~/.llm-gateway/deploy.env" >&2
  exit 1
fi

compose() { docker compose "${ENV_ARGS[@]}" -f docker-compose.yml "$@"; }

echo "==> build gateway ui"
compose build gateway ui

echo "==> up -d --wait gateway ui"
compose up -d --wait gateway ui

echo "==> prune dangling images"
docker image prune -f

echo "==> deploy done"
