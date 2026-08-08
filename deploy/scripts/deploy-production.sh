#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DIR=/opt/ztmdcg/platform
APP_DIR=/opt/ztmdcg/apps/llm-gateway
NGINX_DIR=/opt/ztmdcg/nginx
OPS_DIR=/opt/ztmdcg/scripts
SECRETS_DIR=/opt/ztmdcg/secrets
STATE_FILE="$APP_DIR/release.env"
LOCK_FILE=/var/lock/ztmdcg-deploy.lock

required_ci=(CI_REGISTRY CI_REGISTRY_USER CI_REGISTRY_PASSWORD CI_REGISTRY_IMAGE CI_COMMIT_SHA)
for name in "${required_ci[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "missing CI variable: $name" >&2; exit 1; }
done

for file in "$SECRETS_DIR/platform.env" "$SECRETS_DIR/llm-gateway.env"; do
  [[ -r "$file" ]] || { echo "missing secret file: $file" >&2; exit 1; }
done

exec 9>"$LOCK_FILE"
flock -w 600 9

install -d -m 750 "$APP_DIR" "$NGINX_DIR/conf.d" "$NGINX_DIR/snippets" "$OPS_DIR"
# 这两个目录要被以非 root 用户运行的容器读取,必须 755:
#   mysql 的 entrypoint 降权到 mysql(uid 999)后才扫 /docker-entrypoint-initdb.d,
#   目录不可遍历时 glob 返回空 —— 静默跳过建库,容器仍然 Healthy,直到应用连不上才暴露;
#   nacos-init 用的 curlimages/curl 以 curl_user(uid 100)运行,读不到脚本时
#   sh 打不开文件直接退出,表现为 exited (2) 而没有任何脚本输出。
# 两个脚本都不含密钥(口令由 compose 经环境变量注入),放开读权限没有暴露面。
install -d -m 755 "$PLATFORM_DIR/mysql/init" "$PLATFORM_DIR/nacos-init"
install -m 640 "$SOURCE_ROOT/deploy/platform/docker-compose.yml" "$PLATFORM_DIR/docker-compose.yml"
install -m 755 "$SOURCE_ROOT/deploy/platform/mysql/init/10-create-app-databases.sh" "$PLATFORM_DIR/mysql/init/10-create-app-databases.sh"
install -m 755 "$SOURCE_ROOT/deploy/platform/nacos-init/init.sh" "$PLATFORM_DIR/nacos-init/init.sh"
install -m 640 "$SOURCE_ROOT/deploy/production/docker-compose.yml" "$APP_DIR/docker-compose.yml"
install -m 640 "$SOURCE_ROOT/deploy/nginx/proxy-common.conf" "$NGINX_DIR/snippets/proxy-common.conf"
install -m 750 "$SOURCE_ROOT/deploy/scripts/backup-runtime.sh" "$OPS_DIR/backup-runtime.sh"
install -m 750 "$SOURCE_ROOT/deploy/scripts/restore-mysql.sh" "$OPS_DIR/restore-mysql.sh"

docker network inspect ztmdcg-net >/dev/null 2>&1 || docker network create ztmdcg-net

platform_compose=(docker compose --env-file "$SECRETS_DIR/platform.env" -f "$PLATFORM_DIR/docker-compose.yml")
"${platform_compose[@]}" up -d --wait

# 两个一次性任务容器带 profiles,上面的 up --wait 不会碰它们(--wait 把「已退出」
# 一律判为失败,即使退出码 0)。这里显式跑,退出码非 0 时 set -e 会终止部署。
# 都是幂等的:建库用 CREATE ... IF NOT EXISTS,发配置只在 dataId 不存在时创建。
"${platform_compose[@]}" run --rm mysql-init
"${platform_compose[@]}" run --rm nacos-init

set -a
source "$SECRETS_DIR/platform.env"
source "$SECRETS_DIR/llm-gateway.env"
set +a

export GATEWAY_IMAGE="$CI_REGISTRY_IMAGE/gateway:$CI_COMMIT_SHA"
export UI_IMAGE="$CI_REGISTRY_IMAGE/ui:$CI_COMMIT_SHA"

previous_gateway=
previous_ui=
if [[ -r "$STATE_FILE" ]]; then
  source "$STATE_FILE"
  previous_gateway="${GATEWAY_IMAGE_CURRENT:-}"
  previous_ui="${UI_IMAGE_CURRENT:-}"
fi

nginx_target="$NGINX_DIR/conf.d/gateway.ztmdcg.cn.conf"
nginx_backup="$APP_DIR/gateway.ztmdcg.cn.conf.previous"
had_nginx_config=false
if [[ -f "$nginx_target" ]]; then
  # 该文件的生命周期归本脚本:备份 -> 覆盖 -> nginx -t 失败则回滚。若它是 root:root
  # 640(手册 7.4 早期版本用 `sudo install` 装出来的),这里连读都读不了。裸的
  # "cp: Permission denied" 看不出该怎么办,所以显式给出修复命令。
  if [[ ! -r "$nginx_target" ]]; then
    echo "无法读取 $nginx_target(当前属主 $(stat -c '%U:%G %a' "$nginx_target"))。" >&2
    echo "该文件应归 gitlab-runner 所有,在腾讯云执行:" >&2
    echo "  sudo chown gitlab-runner:gitlab-runner $nginx_target" >&2
    exit 1
  fi
  cp "$nginx_target" "$nginx_backup"
  had_nginx_config=true
fi
install -m 640 "$SOURCE_ROOT/deploy/nginx/gateway.ztmdcg.cn.conf" "$nginx_target"
if ! sudo /usr/sbin/nginx -t; then
  if [[ "$had_nginx_config" == true ]]; then
    install -m 640 "$nginx_backup" "$nginx_target"
  else
    rm -f "$nginx_target"
  fi
  sudo /usr/sbin/nginx -t || true
  exit 1
fi

docker_config="$(mktemp -d)"
export DOCKER_CONFIG="$docker_config"
trap 'rm -rf "$docker_config"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"

compose=(docker compose -f "$APP_DIR/docker-compose.yml")
"${compose[@]}" config --quiet
"${compose[@]}" pull

if ! "${compose[@]}" up -d --wait; then
  rollback_ok=true
  if [[ -n "$previous_gateway" && -n "$previous_ui" ]]; then
    export GATEWAY_IMAGE="$previous_gateway"
    export UI_IMAGE="$previous_ui"
    docker pull "$previous_gateway" || rollback_ok=false
    docker pull "$previous_ui" || rollback_ok=false
    "${compose[@]}" up -d --wait || rollback_ok=false
  else
    rollback_ok=false
  fi

  if [[ "$had_nginx_config" == true ]]; then
    install -m 640 "$nginx_backup" "$nginx_target"
  else
    rm -f "$nginx_target"
  fi
  sudo /usr/sbin/nginx -t && sudo /bin/systemctl reload nginx || true

  if [[ "$rollback_ok" != true ]]; then
    echo "automatic rollback failed; inspect containers and rerun a known-good pipeline" >&2
  fi
  exit 1
fi

sudo /bin/systemctl reload nginx
printf 'GATEWAY_IMAGE_CURRENT=%q\nUI_IMAGE_CURRENT=%q\n' "$GATEWAY_IMAGE" "$UI_IMAGE" > "$STATE_FILE"
chmod 640 "$STATE_FILE"
docker image prune -af --filter until=168h
