#!/usr/bin/env bash
set -euo pipefail

# 由 compose 的 mysql-init 服务执行(见 deploy/platform/docker-compose.yml),
# 不再挂到 mysql 的 /docker-entrypoint-initdb.d —— 那里只在数据卷为空时跑一次。
# 因为跑在独立容器里,必须走 TCP 连 mysql 服务名,不能用 --protocol=socket。
required=(
  MYSQL_ROOT_PASSWORD
  SOFT_MYSQL_DATABASE
  SOFT_MYSQL_USER
  SOFT_MYSQL_PASSWORD
  GATEWAY_MYSQL_DATABASE
  GATEWAY_MYSQL_USER
  GATEWAY_MYSQL_PASSWORD
)

for name in "${required[@]}"; do
  value="${!name:-}"
  if [[ -z "$value" || ! "$value" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "$name must contain only letters, digits, and underscores" >&2
    exit 1
  fi
done

mysql -h "${MYSQL_HOST:-mysql}" -P 3306 -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS ${SOFT_MYSQL_DATABASE}
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${SOFT_MYSQL_USER}'@'%'
  IDENTIFIED BY '${SOFT_MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON ${SOFT_MYSQL_DATABASE}.* TO '${SOFT_MYSQL_USER}'@'%';
CREATE DATABASE IF NOT EXISTS ${GATEWAY_MYSQL_DATABASE}
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${GATEWAY_MYSQL_USER}'@'%'
  IDENTIFIED BY '${GATEWAY_MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON ${GATEWAY_MYSQL_DATABASE}.* TO '${GATEWAY_MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
