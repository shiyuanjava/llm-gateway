#!/bin/sh
# 首启向 Nacos 发布:网关运营参数 + Sentinel 热点参数限流规则。
# Nacos v3 兼容 v1 配置发布 API;发布是覆盖语义,重复执行幂等。
set -eu
NACOS="http://nacos:8848/nacos/v1/cs/configs"

# 运营参数:与 application.yaml 中 gateway.* 同结构,Nacos 侧优先级更高
curl -fsS -X POST "$NACOS" \
  --data-urlencode "dataId=llm-gateway.yaml" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=gateway:
  rate-limit:
    requests-per-minute: 60
  quota:
    tokens-per-tenant: 1000000
  resilience:
    max-retries: 2
    circuit-breaker:
      failure-threshold: 5
      open-seconds: 30
"
echo ""

# Sentinel 热点参数规则:chat-completion 资源,参数 0(租户),单租户 5 QPS
curl -fsS -X POST "$NACOS" \
  --data-urlencode "dataId=llm-gateway-param-flow-rules" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=json" \
  --data-urlencode 'content=[
  {
    "resource": "chat-completion",
    "paramIdx": 0,
    "grade": 1,
    "count": 5,
    "durationInSec": 1
  }
]'
echo ""
echo "nacos-init done"
