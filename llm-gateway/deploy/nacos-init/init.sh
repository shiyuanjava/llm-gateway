#!/bin/sh
# 首启向 Nacos 发布配置模板:网关运营参数 + 应用密钥占位 + Sentinel 热点参数限流规则。
# 只在 dataId 不存在时发布(create-if-absent):Nacos 是这些配置的唯一事实来源,
# 重复部署/重建容器不会覆盖你在控制台里改过的值 —— 尤其是密钥。
# 提示:改过本模板后,已有环境不会自动补新键,需在控制台手动补齐(或删除 dataId 后重跑)。
# 注意:本文件是 deploy/platform/nacos-init/init.sh 的本地开发副本,改动需同步。
set -eu
# Nacos 3.x 接口:v1/v2 旧接口在 3.0 默认禁用、3.2.0 起移除,必须用 v3。
# 走 console 接口(8080)而不是 admin 接口(8848/v3/admin):3.0 起 admin 接口的鉴权
# 有独立开关且默认强制开启,NACOS_AUTH_ENABLED=false 关不掉它,脚本会拿到 403。
# 与 v1 的差异:参数 group -> groupName;namespaceId 默认值从空串改为 public。
NACOS="http://nacos:8080/v3/console/cs/config"
NS="public"

# 判断 dataId 是否已存在。误判为「不存在」会覆盖掉你在控制台填好的密钥,
# 属于静默丢数据,所以歧义时一律按「已存在」处理,宁可漏建也不覆盖。
# 但鉴权/网络故障必须当场报错退出 —— 否则会打印一串「已存在,跳过」,
# 看起来像成功,实际什么都没建,直到网关启动才暴露。
exists() {
  body=$(curl -s "$NACOS?dataId=$1&groupName=DEFAULT_GROUP&namespaceId=$NS" 2>/dev/null || true)
  case "$body" in
    *'"content"'*)                return 0 ;;
    *'"data":null'*)              return 1 ;;
    *'config data not exist'*)    return 1 ;;
    *Forbidden*|*Unauthorized*|*'User not found'*|*'403'*|*'401'*)
      echo "错误:Nacos 拒绝了本次查询($1),鉴权未通过,已放弃发布配置。" >&2
      echo "响应:$body" >&2
      exit 1
      ;;
    '')
      echo "错误:Nacos 无响应($1),已放弃发布配置。" >&2
      exit 1
      ;;
    *)
      echo "警告:无法判断 $1 是否已存在,按已存在跳过发布(不覆盖)。响应:$body" >&2
      return 0
      ;;
  esac
}

if exists "llm-gateway.yaml"; then
  echo "llm-gateway.yaml 已存在,跳过(不覆盖控制台现值)"
else
  # 运营参数:与 application.yaml 中 gateway.* 同结构,Nacos 侧优先级更高。
  # 应用密钥:以「环境变量同名的顶层键」发布,喂给 application*.yaml 里的 ${XXX:} 占位符;
  # 环境变量若显式设置仍然优先(本地开发/CI 测试用),生产以 Nacos 为准。
  curl -fsS -X POST "$NACOS" \
    --data-urlencode "dataId=llm-gateway.yaml" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "namespaceId=$NS" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=# ===== 运营参数(动态下发) =====
gateway:
  rate-limit:
    requests-per-minute: 60
  quota:
    tokens-per-tenant: 1000000
  resilience:
    max-retries: 2
    circuit-breaker:
      failure-threshold: 5
      open-seconds: 30

# ===== 应用密钥(在 Nacos 控制台填写真实值;修改后重启 gateway 生效) =====
# JWT 签名密钥,至少 32 字符;为空网关拒绝启动(fail-closed)
GATEWAY_JWT_SECRET: \"\"
# 首启引导管理员(仅 admin_user 表为空时创建)
ADMIN_USERNAME: \"\"
ADMIN_PASSWORD: \"\"
# 供应商 Key(留空则该供应商不可用,会自动 Fallback)
DEEPSEEK_API_KEY: \"\"
OPENAI_API_KEY: \"\"
ANTHROPIC_API_KEY: \"\"
# 可选:JWT 密钥轮换过渡期的旧密钥(逗号分隔);过渡期后清空
# GATEWAY_JWT_SECRETS_FALLBACK: \"\"
# 可选:控制台跨域白名单(逗号分隔),同时覆盖 /admin 与 Playground /v1;
# 同源反代部署保持缺省即可。旧 GATEWAY_ADMIN_ALLOWED_ORIGINS 仍兼容。
# GATEWAY_CORS_ALLOWED_ORIGINS: \"\"
"
  echo ""
  echo "已发布 llm-gateway.yaml 模板,请在控制台填写密钥"
fi

if exists "llm-gateway-param-flow-rules"; then
  echo "llm-gateway-param-flow-rules 已存在,跳过"
else
  # Sentinel 热点参数规则:chat-completion 资源,参数 0(租户),单租户 5 QPS
  curl -fsS -X POST "$NACOS" \
    --data-urlencode "dataId=llm-gateway-param-flow-rules" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "namespaceId=$NS" \
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
  echo "已发布 llm-gateway-param-flow-rules"
fi

echo "nacos-init done"
