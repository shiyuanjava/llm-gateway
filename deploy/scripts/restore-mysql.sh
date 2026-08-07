#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  echo "usage: restore-mysql.sh /data/ztmdcg/backups/YYYY-MM-DD_HHMMSS/mysql/databases.sql --confirm" >&2
}

[[ $# -eq 2 && "$2" == --confirm ]] || { usage; exit 2; }

backup_file_input="$1"
platform_dir=/opt/ztmdcg/platform
secrets_file=/opt/ztmdcg/secrets/platform.env
gateway_secrets=/opt/ztmdcg/secrets/llm-gateway.env
soft_secrets=/opt/ztmdcg/secrets/soft-training.env
soft_dir=/opt/ztmdcg/apps/soft-training
gateway_dir=/opt/ztmdcg/apps/llm-gateway
lock_file=/var/lock/ztmdcg-deploy.lock

for command_name in docker flock grep realpath sha256sum; do
  command -v "$command_name" >/dev/null || { echo "$command_name is required" >&2; exit 1; }
done

[[ -r "$backup_file_input" ]] || { echo "backup file is not readable: $backup_file_input" >&2; exit 1; }
backup_file="$(realpath -e "$backup_file_input")"
[[ "$(basename "$backup_file")" == databases.sql && "$(basename "$(dirname "$backup_file")")" == mysql ]] || {
  echo "backup file must end with /mysql/databases.sql" >&2
  exit 1
}
backup_dir="$(dirname "$(dirname "$backup_file")")"
manifest="$backup_dir/SHA256SUMS"

required_files=(
  "$manifest"
  "$secrets_file"
  "$gateway_secrets"
  "$soft_secrets"
  "$platform_dir/docker-compose.yml"
  "$gateway_dir/docker-compose.yml"
  "$soft_dir/docker-compose.yml"
  "$gateway_dir/release.env"
  "$soft_dir/release.env"
)
for file in "${required_files[@]}"; do
  [[ -r "$file" ]] || { echo "required restore file is not readable: $file" >&2; exit 1; }
done

(
  cd "$backup_dir"
  sha256sum -c --quiet SHA256SUMS
)
echo "backup checksum verified: $backup_dir"

set -a
source "$secrets_file"
source "$gateway_secrets"
source "$soft_secrets"
source "$gateway_dir/release.env"
source "$soft_dir/release.env"
set +a

required_env=(
  MYSQL_ROOT_PASSWORD
  SOFT_MYSQL_DATABASE
  GATEWAY_MYSQL_DATABASE
  GATEWAY_IMAGE_CURRENT
  UI_IMAGE_CURRENT
  BACKEND_IMAGE_CURRENT
  FRONTEND_IMAGE_CURRENT
)
for name in "${required_env[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "missing required restore value: $name" >&2; exit 1; }
done

for database in "$SOFT_MYSQL_DATABASE" "$GATEWAY_MYSQL_DATABASE"; do
  grep -Fq "Current Database: \`$database\`" "$backup_file" || {
    echo "backup does not contain expected database: $database" >&2
    exit 1
  }
done

export GATEWAY_IMAGE="$GATEWAY_IMAGE_CURRENT"
export UI_IMAGE="$UI_IMAGE_CURRENT"
export BACKEND_IMAGE="$BACKEND_IMAGE_CURRENT"
export FRONTEND_IMAGE="$FRONTEND_IMAGE_CURRENT"

platform_compose=(docker compose --env-file "$secrets_file" -f "$platform_dir/docker-compose.yml")
gateway_compose=(docker compose -f "$gateway_dir/docker-compose.yml")
soft_compose=(docker compose -f "$soft_dir/docker-compose.yml")

"${platform_compose[@]}" config --quiet
"${gateway_compose[@]}" config --quiet
"${soft_compose[@]}" config --quiet

exec 9>"$lock_file"
flock -w 600 9 || { echo "timed out waiting for deployment lock: $lock_file" >&2; exit 1; }

applications_may_be_stopped=0
restore_failed() {
  rc=$?
  trap - ERR
  if ((applications_may_be_stopped)); then
    echo "restore failed; one or both application stacks may be stopped or unhealthy" >&2
    echo "inspect MySQL and Compose logs before restarting them manually" >&2
  fi
  exit "$rc"
}
trap restore_failed ERR

echo "stopping application stacks for controlled MySQL restore"
applications_may_be_stopped=1
"${soft_compose[@]}" stop backend frontend
"${gateway_compose[@]}" stop gateway ui

"${platform_compose[@]}" up -d --wait mysql
"${platform_compose[@]}" exec -T mysql sh -ec \
  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --protocol=socket' < "$backup_file"

"${gateway_compose[@]}" up -d --wait gateway ui
"${soft_compose[@]}" up -d --wait backend frontend
applications_may_be_stopped=0
trap - ERR

echo "MySQL restore complete and both application stacks are healthy."
