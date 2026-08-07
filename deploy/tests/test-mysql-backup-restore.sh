#!/usr/bin/env bash
set -Eeuo pipefail

# Git Bash otherwise rewrites container paths such as /tmp/databases.sql.
export MSYS_NO_PATHCONV=1

container="ztmdcg-mysql-restore-test-$$"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

docker run -d --name "$container" \
  -e MYSQL_ROOT_PASSWORD=testroot \
  -e SOFT_MYSQL_DATABASE=soft_training \
  -e GATEWAY_MYSQL_DATABASE=llm_gateway \
  mysql:8.4 >/dev/null

ready=0
for attempt in $(seq 1 180); do
  if docker exec "$container" sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h127.0.0.1 -uroot --silent' \
    >/dev/null 2>&1; then
    ready=1
    echo "MySQL ready after ${attempt}s"
    break
  fi
  sleep 1
done
[[ "$ready" == 1 ]] || { docker logs "$container" >&2; echo "MySQL did not become ready" >&2; exit 1; }

docker exec -i -e MYSQL_PWD=testroot "$container" mysql -uroot <<'SQL'
CREATE DATABASE soft_training;
CREATE DATABASE llm_gateway;
CREATE TABLE soft_training.probe(value VARCHAR(64));
INSERT INTO soft_training.probe VALUES ('soft-original');
CREATE TABLE llm_gateway.probe(value VARCHAR(64));
INSERT INTO llm_gateway.probe VALUES ('gateway-original');
SQL

docker exec "$container" sh -ec \
  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --single-transaction --routines --triggers --events --add-drop-database --databases "$SOFT_MYSQL_DATABASE" "$GATEWAY_MYSQL_DATABASE" > /tmp/databases.sql'

docker exec "$container" grep -Fq 'Current Database: `soft_training`' /tmp/databases.sql
docker exec "$container" grep -Fq 'Current Database: `llm_gateway`' /tmp/databases.sql

docker exec -i -e MYSQL_PWD=testroot "$container" mysql -uroot <<'SQL'
UPDATE soft_training.probe SET value = 'soft-mutated';
UPDATE llm_gateway.probe SET value = 'gateway-mutated';
CREATE TABLE soft_training.stale_only(id INT);
SQL

docker exec "$container" sh -ec \
  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysql -uroot --protocol=socket < /tmp/databases.sql'

soft_value="$(docker exec -e MYSQL_PWD=testroot "$container" mysql -N -uroot -e 'SELECT value FROM soft_training.probe;')"
gateway_value="$(docker exec -e MYSQL_PWD=testroot "$container" mysql -N -uroot -e 'SELECT value FROM llm_gateway.probe;')"
stale_count="$(docker exec -e MYSQL_PWD=testroot "$container" mysql -N -uroot -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='soft_training' AND table_name='stale_only';")"

[[ "$soft_value" == soft-original ]] || { echo "unexpected soft-training value: $soft_value" >&2; exit 1; }
[[ "$gateway_value" == gateway-original ]] || { echo "unexpected gateway value: $gateway_value" >&2; exit 1; }
[[ "$stale_count" == 0 ]] || { echo "stale table survived restore" >&2; exit 1; }

echo "MySQL two-database dump/restore integration passed."
