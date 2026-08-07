#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PLATFORM_DIR=/opt/ztmdcg/platform
SECRETS_FILE=/opt/ztmdcg/secrets/platform.env
BACKUP_ROOT=/data/ztmdcg/backups
LOCK_FILE=/var/lock/ztmdcg-deploy.lock
stamp="$(date +%F_%H%M%S)"
target="$BACKUP_ROOT/$stamp"
partial="$target.partial"

[[ -r "$SECRETS_FILE" ]] || { echo "missing $SECRETS_FILE" >&2; exit 1; }
[[ -r "$PLATFORM_DIR/docker-compose.yml" ]] || { echo "missing $PLATFORM_DIR/docker-compose.yml" >&2; exit 1; }
[[ ! -e "$target" && ! -e "$partial" ]] || { echo "backup target already exists: $target" >&2; exit 1; }

for command_name in curl docker flock jq sha256sum; do
  command -v "$command_name" >/dev/null || { echo "$command_name is required" >&2; exit 1; }
done

exec 9>"$LOCK_FILE"
flock -w 600 9 || { echo "timed out waiting for deployment lock: $LOCK_FILE" >&2; exit 1; }

install -d -m 700 "$partial/mysql" "$partial/minio" "$partial/qdrant"

finish() {
  rc=$?
  trap - EXIT
  if ((rc != 0)); then
    echo "backup failed; partial data kept at $partial" >&2
  fi
  exit "$rc"
}
trap finish EXIT

set -a
source "$SECRETS_FILE"
set +a

required_env=(
  MYSQL_ROOT_PASSWORD
  SOFT_MYSQL_DATABASE
  GATEWAY_MYSQL_DATABASE
  MINIO_ROOT_USER
  MINIO_ROOT_PASSWORD
)
for name in "${required_env[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "missing required value in $SECRETS_FILE: $name" >&2; exit 1; }
done

compose=(docker compose --env-file "$SECRETS_FILE" -f "$PLATFORM_DIR/docker-compose.yml")

"${compose[@]}" exec -T mysql sh -ec \
  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers --events --add-drop-database --databases "$SOFT_MYSQL_DATABASE" "$GATEWAY_MYSQL_DATABASE"' \
  > "$partial/mysql/databases.sql"
[[ -s "$partial/mysql/databases.sql" ]] || { echo "MySQL dump is empty" >&2; exit 1; }

minio_listing="$partial/minio/BACKUP_BUCKETS.jsonl"
docker run --rm --network ztmdcg-net \
  -e MINIO_ROOT_USER \
  -e MINIO_ROOT_PASSWORD \
  --entrypoint /bin/sh \
  minio/mc:RELEASE.2025-04-16T18-13-26Z \
  -ec 'mc alias set source http://ztmdcg-minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --json source/' \
  > "$minio_listing"

mapfile -t minio_buckets < <(jq -r 'select(.type == "folder") | .key | rtrimstr("/")' "$minio_listing")
for bucket in "${minio_buckets[@]}"; do
  [[ "$bucket" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]] || { echo "unsupported MinIO bucket name: $bucket" >&2; exit 1; }
  docker run --rm --network ztmdcg-net \
    -e MINIO_ROOT_USER \
    -e MINIO_ROOT_PASSWORD \
    -e MINIO_BUCKET="$bucket" \
    -v "$partial/minio:/backup" \
    --entrypoint /bin/sh \
    minio/mc:RELEASE.2025-04-16T18-13-26Z \
    -ec 'mc alias set source http://ztmdcg-minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mkdir -p "/backup/$MINIO_BUCKET" && mc mirror --overwrite "source/$MINIO_BUCKET" "/backup/$MINIO_BUCKET"'
done

collections_file="$partial/qdrant/collections.json"
curl -fsS http://127.0.0.1:6333/collections -o "$collections_file"
mapfile -t collections < <(jq -r '.result.collections[]?.name' "$collections_file")

for collection in "${collections[@]}"; do
  [[ "$collection" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "unsupported Qdrant collection name: $collection" >&2; exit 1; }
  encoded_collection="$(jq -rn --arg value "$collection" '$value|@uri')"
  response="$(curl -fsS -X POST "http://127.0.0.1:6333/collections/$encoded_collection/snapshots")"
  snapshot="$(printf '%s' "$response" | jq -r '.result.name')"
  [[ -n "$snapshot" && "$snapshot" != null ]] || { echo "failed to create Qdrant snapshot for $collection" >&2; exit 1; }
  encoded_snapshot="$(jq -rn --arg value "$snapshot" '$value|@uri')"
  curl -fsS "http://127.0.0.1:6333/collections/$encoded_collection/snapshots/$encoded_snapshot" \
    -o "$partial/qdrant/$collection--$snapshot"
  curl -fsS -X DELETE "http://127.0.0.1:6333/collections/$encoded_collection/snapshots/$encoded_snapshot" >/dev/null
done

(
  cd "$partial"
  find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS
  sha256sum -c --quiet SHA256SUMS
)

mv "$partial" "$target"
trap - EXIT
echo "backup complete: $target"
