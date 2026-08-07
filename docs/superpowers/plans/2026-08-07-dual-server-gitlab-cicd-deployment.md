# Dual-Server GitLab CI/CD Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Produce the two repository CI pipelines, production Docker Compose assets, Nginx configuration, rollback/backup scripts, and a single Chinese runbook needed to rebuild the two servers and operate push-to-deploy safely.

**Architecture:** GitLab CE, Container Registry, and a single-concurrency Docker build Runner run on the 180GB JD Cloud server. The Tencent Cloud server runs a shared platform Compose stack, the two application stacks, a Shell deployment Runner, and host Nginx/Certbot; immutable SHA images move between the servers over HTTPS.

**Tech Stack:** Ubuntu 22.04, GitLab CE Omnibus, GitLab Runner, Docker Engine, Docker Compose v2, Bash, PowerShell validation, Nginx, Certbot, MySQL 8.4, Redis 7.4, MinIO, Qdrant, Nacos 3.1.1, Sentinel Dashboard, Maven, npm.

---

## File map

### llm-gateway-project

- Create: deploy/platform/docker-compose.yml — shared MySQL, Redis, MinIO, Qdrant, Nacos, and Sentinel services.
- Create: deploy/platform/.env.example — platform secret and port contract.
- Create: deploy/platform/mysql/init/10-create-app-databases.sh — creates both databases and least-privilege users.
- Create: deploy/platform/nacos-init/init.sh — canonical Nacos configuration initializer.
- Create: deploy/production/docker-compose.yml — gateway and UI only.
- Create: deploy/production/.env.example — gateway runtime contract.
- Create: deploy/nginx/00-acme-bootstrap.conf — temporary ACME webroot configuration.
- Create: deploy/nginx/proxy-common.conf — shared reverse-proxy headers.
- Create: deploy/nginx/gateway.ztmdcg.cn.conf — Gateway UI, admin API, and SSE routing.
- Create: deploy/scripts/deploy-production.sh — locked SHA deployment with rollback.
- Create: deploy/scripts/backup-runtime.sh — MySQL, MinIO, and Qdrant backup entrypoint.
- Create: deploy/scripts/restore-mysql.sh — controlled database restore with application stop/start.
- Create: deploy/tests/validate-dual-server.ps1 — structural and Compose validation.
- Modify: .gitlab-ci.yml — HTTPS Registry build and automatic deployment.
- Modify: docs/dual-server-gitlab-docker-compose-guide.md — final operator runbook.
- Modify: README.md — link the consolidated runbook.

### 软项智训

- Create: .gitlab-ci.yml — verification, image build, and deployment.
- Create: deploy/production/docker-compose.yml — backend and frontend only.
- Create: deploy/production/.env.example — runtime contract.
- Create: deploy/nginx/ztmdcg.cn.conf — SPA and API routing.
- Create: deploy/scripts/deploy-production.sh — locked SHA deployment with rollback.
- Modify: README.md — link the consolidated runbook.

The existing local development Compose files remain unchanged.

### Task 1: Add a deployment validation harness

**Files:**
- Create: llm-gateway-project/deploy/tests/validate-dual-server.ps1

- [ ] **Step 1: Write the validator before deployment files exist**

~~~powershell
param([string]$WorkspaceRoot = 'C:\practice')

$ErrorActionPreference = 'Stop'
$gatewayRepo = Join-Path $WorkspaceRoot 'llm-gateway-project'
$softRepo = Join-Path $WorkspaceRoot '软项智训'

$requiredFiles = @(
    (Join-Path $gatewayRepo 'deploy\platform\docker-compose.yml'),
    (Join-Path $gatewayRepo 'deploy\platform\.env.example'),
    (Join-Path $gatewayRepo 'deploy\production\docker-compose.yml'),
    (Join-Path $gatewayRepo 'deploy\production\.env.example'),
    (Join-Path $gatewayRepo 'deploy\scripts\deploy-production.sh'),
    (Join-Path $gatewayRepo 'deploy\nginx\gateway.ztmdcg.cn.conf'),
    (Join-Path $softRepo '.gitlab-ci.yml'),
    (Join-Path $softRepo 'deploy\production\docker-compose.yml'),
    (Join-Path $softRepo 'deploy\production\.env.example'),
    (Join-Path $softRepo 'deploy\scripts\deploy-production.sh'),
    (Join-Path $softRepo 'deploy\nginx\ztmdcg.cn.conf')
)

$missing = $requiredFiles | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) {
    throw ('Missing deployment files:' + [Environment]::NewLine + ($missing -join [Environment]::NewLine))
}

function Test-Compose {
    param([string]$ComposeFile, [string]$EnvFile)
    docker compose --env-file $EnvFile -f $ComposeFile config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed: $ComposeFile"
    }
}

Test-Compose -ComposeFile (Join-Path $gatewayRepo 'deploy\platform\docker-compose.yml') -EnvFile (Join-Path $gatewayRepo 'deploy\platform\.env.example')
Test-Compose -ComposeFile (Join-Path $gatewayRepo 'deploy\production\docker-compose.yml') -EnvFile (Join-Path $gatewayRepo 'deploy\production\.env.example')
Test-Compose -ComposeFile (Join-Path $softRepo 'deploy\production\docker-compose.yml') -EnvFile (Join-Path $softRepo 'deploy\production\.env.example')

$bashScripts = @(
    (Join-Path $gatewayRepo 'deploy\platform\mysql\init\10-create-app-databases.sh'),
    (Join-Path $gatewayRepo 'deploy\platform\nacos-init\init.sh'),
    (Join-Path $gatewayRepo 'deploy\scripts\deploy-production.sh'),
    (Join-Path $gatewayRepo 'deploy\scripts\backup-runtime.sh'),
    (Join-Path $gatewayRepo 'deploy\scripts\restore-mysql.sh'),
    (Join-Path $softRepo 'deploy\scripts\deploy-production.sh')
)

foreach ($script in $bashScripts) {
    bash -n $script
    if ($LASTEXITCODE -ne 0) {
        throw "bash syntax check failed: $script"
    }
}

$gatewayNginx = Get-Content -LiteralPath (Join-Path $gatewayRepo 'deploy\nginx\gateway.ztmdcg.cn.conf') -Raw
if ($gatewayNginx -notmatch 'proxy_buffering off' -or $gatewayNginx -notmatch 'proxy_read_timeout 330s') {
    throw 'Gateway Nginx config is missing required SSE directives'
}

$softNginx = Get-Content -LiteralPath (Join-Path $softRepo 'deploy\nginx\ztmdcg.cn.conf') -Raw
if ($softNginx -notmatch 'client_max_body_size 55m') {
    throw 'Soft-training Nginx config is missing the upload limit'
}

Write-Host 'Dual-server deployment validation passed.'
~~~

- [ ] **Step 2: Run the validator and confirm the intended failure**

~~~powershell
powershell -ExecutionPolicy Bypass -File .\llm-gateway-project\deploy\tests\validate-dual-server.ps1
~~~

Expected: FAIL with Missing deployment files.

- [ ] **Step 3: Commit only the validator**

~~~powershell
git -C llm-gateway-project add deploy/tests/validate-dual-server.ps1
git -C llm-gateway-project commit -m "test: add dual-server deployment validator"
~~~

### Task 2: Build the shared Tencent Cloud platform stack

**Files:**
- Create: llm-gateway-project/deploy/platform/docker-compose.yml
- Create: llm-gateway-project/deploy/platform/.env.example
- Create: llm-gateway-project/deploy/platform/mysql/init/10-create-app-databases.sh
- Create: llm-gateway-project/deploy/platform/nacos-init/init.sh

- [ ] **Step 1: Add the environment contract**

~~~dotenv
MYSQL_ROOT_PASSWORD=example-only-generate-with-openssl-rand-hex-32
SOFT_MYSQL_DATABASE=soft_training
SOFT_MYSQL_USER=soft_training_app
SOFT_MYSQL_PASSWORD=example-only-generate-with-openssl-rand-hex-32
GATEWAY_MYSQL_DATABASE=llm_gateway
GATEWAY_MYSQL_USER=llm_gateway_app
GATEWAY_MYSQL_PASSWORD=example-only-generate-with-openssl-rand-hex-32
SOFT_REDIS_PASSWORD=example-only-generate-with-openssl-rand-hex-32
GATEWAY_REDIS_PASSWORD=example-only-generate-with-openssl-rand-hex-32
MINIO_ROOT_USER=ztmdcgadmin
MINIO_ROOT_PASSWORD=example-only-generate-with-openssl-rand-hex-32
~~~

- [ ] **Step 2: Add deterministic database initialization**

~~~bash
#!/usr/bin/env bash
set -euo pipefail

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

mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
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
~~~

- [ ] **Step 3: Copy the canonical Nacos initializer**

Copy llm-gateway/deploy/nacos-init/init.sh to deploy/platform/nacos-init/init.sh unchanged. Keep create-if-absent behavior so later deployments never overwrite production secrets.

- [ ] **Step 4: Add the platform Compose**

Create deploy/platform/docker-compose.yml with the exact services and limits below:

~~~yaml
name: ztmdcg-platform

services:
  mysql:
    image: mysql:8.4
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD}
      SOFT_MYSQL_DATABASE: ${SOFT_MYSQL_DATABASE:-soft_training}
      SOFT_MYSQL_USER: ${SOFT_MYSQL_USER:-soft_training_app}
      SOFT_MYSQL_PASSWORD: ${SOFT_MYSQL_PASSWORD:?set SOFT_MYSQL_PASSWORD}
      GATEWAY_MYSQL_DATABASE: ${GATEWAY_MYSQL_DATABASE:-llm_gateway}
      GATEWAY_MYSQL_USER: ${GATEWAY_MYSQL_USER:-llm_gateway_app}
      GATEWAY_MYSQL_PASSWORD: ${GATEWAY_MYSQL_PASSWORD:?set GATEWAY_MYSQL_PASSWORD}
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --innodb-buffer-pool-size=512M
      - --max-connections=80
    volumes:
      - /data/ztmdcg/mysql:/var/lib/mysql
      - ./mysql/init/10-create-app-databases.sh:/docker-entrypoint-initdb.d/10-create-app-databases.sh:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h127.0.0.1 -uroot -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    mem_limit: 1280m
    networks:
      ztmdcg-net:
        aliases: [ztmdcg-mysql]

  redis-soft:
    image: redis:7.4-alpine
    restart: unless-stopped
    environment:
      SOFT_REDIS_PASSWORD: ${SOFT_REDIS_PASSWORD:?set SOFT_REDIS_PASSWORD}
    command: ["sh", "-ec", "exec redis-server --appendonly yes --maxmemory 192mb --maxmemory-policy allkeys-lru --requirepass \"$$SOFT_REDIS_PASSWORD\""]
    volumes: [/data/ztmdcg/redis-soft:/data]
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$SOFT_REDIS_PASSWORD\" ping | grep -q PONG"]
      interval: 10s
      timeout: 5s
      retries: 12
    mem_limit: 256m
    networks:
      ztmdcg-net:
        aliases: [ztmdcg-redis-soft]

  redis-gateway:
    image: redis:7.4-alpine
    restart: unless-stopped
    environment:
      GATEWAY_REDIS_PASSWORD: ${GATEWAY_REDIS_PASSWORD:?set GATEWAY_REDIS_PASSWORD}
    command: ["sh", "-ec", "exec redis-server --save \"\" --appendonly no --maxmemory 192mb --maxmemory-policy allkeys-lru --requirepass \"$$GATEWAY_REDIS_PASSWORD\""]
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$GATEWAY_REDIS_PASSWORD\" ping | grep -q PONG"]
      interval: 10s
      timeout: 5s
      retries: 12
    mem_limit: 256m
    networks:
      ztmdcg-net:
        aliases: [ztmdcg-redis-gateway]

  minio:
    image: minio/minio:RELEASE.2025-04-22T22-12-26Z
    restart: unless-stopped
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:?set MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}
    command: server /data --console-address :9001
    ports: ["127.0.0.1:9001:9001"]
    volumes: [/data/ztmdcg/minio:/data]
    mem_limit: 384m
    networks:
      ztmdcg-net:
        aliases: [ztmdcg-minio]

  qdrant:
    image: qdrant/qdrant:v1.17.0
    restart: unless-stopped
    ports: ["127.0.0.1:6333:6333"]
    volumes: [/data/ztmdcg/qdrant:/qdrant/storage]
    mem_limit: 512m
    networks:
      ztmdcg-net:
        aliases: [ztmdcg-qdrant]

  nacos:
    image: nacos/nacos-server:v3.1.1
    restart: unless-stopped
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLED: "false"
      NACOS_AUTH_TOKEN: SecretKey012345678901234567890123456789012345678901234567890123456789
      NACOS_AUTH_IDENTITY_KEY: nacos-local
      NACOS_AUTH_IDENTITY_VALUE: nacos-local
      JVM_XMS: 256m
      JVM_XMX: 384m
      JVM_XMN: 128m
      TZ: Asia/Shanghai
    ports:
      - 127.0.0.1:8848:8848
      - 127.0.0.1:8850:8080
    volumes: [/data/ztmdcg/nacos:/home/nacos/data]
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/v3/console/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 60
      start_period: 30s
    mem_limit: 512m
    networks:
      ztmdcg-net:
        aliases: [nacos]

  nacos-init:
    image: curlimages/curl:8.10.1
    depends_on:
      nacos:
        condition: service_healthy
    volumes: [./nacos-init/init.sh:/init.sh:ro]
    entrypoint: ["sh", "/init.sh"]
    restart: "no"
    networks: [ztmdcg-net]

  sentinel-dashboard:
    image: bladex/sentinel-dashboard:1.8.8
    restart: unless-stopped
    environment:
      JAVA_OPTS: -Xms128m -Xmx192m
    ports: ["127.0.0.1:8858:8858"]
    mem_limit: 256m
    networks:
      ztmdcg-net:
        aliases: [sentinel-dashboard]

networks:
  ztmdcg-net:
    external: true
~~~

- [ ] **Step 5: Validate and commit**

~~~powershell
docker compose --env-file .\llm-gateway-project\deploy\platform\.env.example -f .\llm-gateway-project\deploy\platform\docker-compose.yml config --quiet
bash -n .\llm-gateway-project\deploy\platform\mysql\init\10-create-app-databases.sh
bash -n .\llm-gateway-project\deploy\platform\nacos-init\init.sh
git -C llm-gateway-project add deploy/platform
git -C llm-gateway-project commit -m "feat: add shared production platform stack"
~~~

Expected: validation exits 0 and the commit contains only deploy/platform.

### Task 3: Add the LLM Gateway production stack and edge routing

**Files:**
- Create: llm-gateway-project/deploy/production/docker-compose.yml
- Create: llm-gateway-project/deploy/production/.env.example
- Create: llm-gateway-project/deploy/nginx/00-acme-bootstrap.conf
- Create: llm-gateway-project/deploy/nginx/proxy-common.conf
- Create: llm-gateway-project/deploy/nginx/gateway.ztmdcg.cn.conf
- Create: llm-gateway-project/deploy/scripts/deploy-production.sh

- [ ] **Step 1: Add the gateway production environment contract**

~~~dotenv
GATEWAY_IMAGE=registry.ztmdcg.cn/root/llm-gateway/gateway:example-commit-sha
UI_IMAGE=registry.ztmdcg.cn/root/llm-gateway/ui:example-commit-sha
GATEWAY_MYSQL_DATABASE=llm_gateway
GATEWAY_MYSQL_USER=llm_gateway_app
GATEWAY_MYSQL_PASSWORD=example-only-generate-with-openssl-rand-hex-32
GATEWAY_REDIS_PASSWORD=example-only-generate-with-openssl-rand-hex-32
~~~

- [ ] **Step 2: Add the gateway-only production Compose**

~~~yaml
name: llm-gateway-production

services:
  gateway:
    image: ${GATEWAY_IMAGE:?set GATEWAY_IMAGE}
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: ztmdcg-mysql
      MYSQL_PORT: "3306"
      MYSQL_DB: ${GATEWAY_MYSQL_DATABASE:-llm_gateway}
      MYSQL_USER: ${GATEWAY_MYSQL_USER:-llm_gateway_app}
      MYSQL_PASSWORD: ${GATEWAY_MYSQL_PASSWORD:?set GATEWAY_MYSQL_PASSWORD}
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "10"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: "2"
      GATEWAY_CACHE_STORE: redis
      REDIS_HOST: ztmdcg-redis-gateway
      REDIS_PORT: "6379"
      REDIS_PASSWORD: ${GATEWAY_REDIS_PASSWORD:?set GATEWAY_REDIS_PASSWORD}
      NACOS_SERVER_ADDR: nacos:8848
      SENTINEL_DASHBOARD: sentinel-dashboard:8858
      GATEWAY_RATE_LIMIT_STORE: sentinel
      JAVA_TOOL_OPTIONS: -Xms256m -Xmx560m -DJM.LOG.PATH=/app/logs/nacos -Dnacos.logging.default.config.enabled=false -Dcsp.sentinel.log.dir=/app/logs/sentinel
      TZ: Asia/Shanghai
    ports:
      - 127.0.0.1:18091:8080
    volumes:
      - gateway-logs:/app/logs
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:9090/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 40s
    mem_limit: 768m
    networks:
      ztmdcg-net:
        aliases: [llm-gateway]

  ui:
    image: ${UI_IMAGE:?set UI_IMAGE}
    restart: unless-stopped
    depends_on:
      gateway:
        condition: service_healthy
    ports:
      - 127.0.0.1:18081:80
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/ >/dev/null"]
      interval: 15s
      timeout: 5s
      retries: 10
    mem_limit: 128m
    networks: [ztmdcg-net]

volumes:
  gateway-logs:
    name: llm-gateway-production-logs

networks:
  ztmdcg-net:
    external: true
~~~

- [ ] **Step 3: Add the Nginx source files**

Create deploy/nginx/proxy-common.conf:

~~~nginx
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header X-Request-Id $http_x_request_id;
proxy_set_header Connection "";
proxy_connect_timeout 5s;
~~~

Create deploy/nginx/00-acme-bootstrap.conf:

~~~nginx
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ztmdcg.cn www.ztmdcg.cn gateway.ztmdcg.cn;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
        default_type text/plain;
    }

    location / {
        return 503;
    }
}
~~~

Create deploy/nginx/gateway.ztmdcg.cn.conf:

~~~nginx
server {
    listen 80;
    listen [::]:80;
    server_name gateway.ztmdcg.cn;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name gateway.ztmdcg.cn;

    ssl_certificate /etc/letsencrypt/live/gateway.ztmdcg.cn/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/gateway.ztmdcg.cn/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /admin/ {
        proxy_pass http://127.0.0.1:18091;
        include /opt/ztmdcg/nginx/snippets/proxy-common.conf;
        proxy_send_timeout 35s;
        proxy_read_timeout 35s;
    }

    location /v1/ {
        proxy_pass http://127.0.0.1:18091;
        include /opt/ztmdcg/nginx/snippets/proxy-common.conf;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        proxy_send_timeout 35s;
        proxy_read_timeout 330s;
    }

    location / {
        proxy_pass http://127.0.0.1:18081;
        include /opt/ztmdcg/nginx/snippets/proxy-common.conf;
    }
}
~~~

- [ ] **Step 4: Add the deployment script**

Create deploy/scripts/deploy-production.sh:

~~~bash
#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DIR=/opt/ztmdcg/platform
APP_DIR=/opt/ztmdcg/apps/llm-gateway
NGINX_DIR=/opt/ztmdcg/nginx
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

install -d -m 750 "$PLATFORM_DIR/mysql/init" "$PLATFORM_DIR/nacos-init" "$APP_DIR" "$NGINX_DIR/conf.d" "$NGINX_DIR/snippets"
install -m 640 "$SOURCE_ROOT/deploy/platform/docker-compose.yml" "$PLATFORM_DIR/docker-compose.yml"
install -m 750 "$SOURCE_ROOT/deploy/platform/mysql/init/10-create-app-databases.sh" "$PLATFORM_DIR/mysql/init/10-create-app-databases.sh"
install -m 750 "$SOURCE_ROOT/deploy/platform/nacos-init/init.sh" "$PLATFORM_DIR/nacos-init/init.sh"
install -m 640 "$SOURCE_ROOT/deploy/production/docker-compose.yml" "$APP_DIR/docker-compose.yml"
install -m 640 "$SOURCE_ROOT/deploy/nginx/proxy-common.conf" "$NGINX_DIR/snippets/proxy-common.conf"

docker network inspect ztmdcg-net >/dev/null 2>&1 || docker network create ztmdcg-net
docker compose --env-file "$SECRETS_DIR/platform.env" -f "$PLATFORM_DIR/docker-compose.yml" up -d --wait

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
[[ ! -f "$nginx_target" ]] || cp "$nginx_target" "$nginx_backup"
install -m 640 "$SOURCE_ROOT/deploy/nginx/gateway.ztmdcg.cn.conf" "$nginx_target"
sudo /usr/sbin/nginx -t

docker_config="$(mktemp -d)"
export DOCKER_CONFIG="$docker_config"
trap 'rm -rf "$docker_config"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"

compose=(docker compose -f "$APP_DIR/docker-compose.yml")
"${compose[@]}" config --quiet
"${compose[@]}" pull

if ! "${compose[@]}" up -d --wait; then
  if [[ -n "$previous_gateway" && -n "$previous_ui" ]]; then
    export GATEWAY_IMAGE="$previous_gateway"
    export UI_IMAGE="$previous_ui"
    docker pull "$previous_gateway"
    docker pull "$previous_ui"
    "${compose[@]}" up -d --wait
  fi
  if [[ -f "$nginx_backup" ]]; then
    install -m 640 "$nginx_backup" "$nginx_target"
    sudo /usr/sbin/nginx -t
    sudo /bin/systemctl reload nginx
  fi
  exit 1
fi

sudo /bin/systemctl reload nginx
printf 'GATEWAY_IMAGE_CURRENT=%q\nUI_IMAGE_CURRENT=%q\n' "$GATEWAY_IMAGE" "$UI_IMAGE" > "$STATE_FILE"
chmod 640 "$STATE_FILE"
docker image prune -af --filter until=168h
~~~

- [ ] **Step 5: Validate and commit**

~~~powershell
docker compose --env-file .\llm-gateway-project\deploy\production\.env.example -f .\llm-gateway-project\deploy\production\docker-compose.yml config --quiet
bash -n .\llm-gateway-project\deploy\scripts\deploy-production.sh
git -C llm-gateway-project add deploy/production deploy/nginx deploy/scripts/deploy-production.sh
git -C llm-gateway-project commit -m "feat: add gateway production deployment"
~~~

Expected: Compose and Bash validation pass.

### Task 4: Convert the Gateway GitLab pipeline to automatic HTTPS deployment

**Files:**
- Modify: llm-gateway-project/.gitlab-ci.yml

- [ ] **Step 1: Keep the existing verification jobs and replace image/deploy behavior**

Keep backend_test, frontend_build, and frontend_format. Change build_images so Docker-in-Docker uses only registry mirrors, with no insecure registry:

~~~yaml
build_images:
  stage: image
  image: docker:27-cli
  variables:
    DOCKER_HOST: tcp://docker:2375
    DOCKER_TLS_CERTDIR: ""
  services:
    - name: docker:27-dind
      alias: docker
      command:
        - --registry-mirror=https://docker.m.daocloud.io
        - --registry-mirror=https://docker.1ms.run
  before_script:
    - echo "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"
  script:
    - docker build --pull -t "$CI_REGISTRY_IMAGE/gateway:$CI_COMMIT_SHA" llm-gateway
    - docker build --pull --build-arg VITE_API_BASE= -t "$CI_REGISTRY_IMAGE/ui:$CI_COMMIT_SHA" llm-gateway-ui
    - docker push "$CI_REGISTRY_IMAGE/gateway:$CI_COMMIT_SHA"
    - docker push "$CI_REGISTRY_IMAGE/ui:$CI_COMMIT_SHA"
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
~~~

Replace both old deployment jobs with:

~~~yaml
deploy_production:
  stage: deploy
  tags: [ztmdcg-production]
  needs: [build_images]
  variables:
    GIT_DEPTH: "1"
    DOCKER_HOST: unix:///var/run/docker.sock
  script:
    - bash deploy/scripts/deploy-production.sh
  environment:
    name: production
    url: https://gateway.ztmdcg.cn
  resource_group: llm-gateway-production
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
~~~

Delete deploy_k3s and all references to KUBE_CONFIG.

- [ ] **Step 2: Validate the YAML structure**

Run:

~~~powershell
rg -n "10\.1\.0\.16|insecure-registry|deploy_k3s|KUBE_CONFIG" .\llm-gateway-project\.gitlab-ci.yml
~~~

Expected: no matches.

Run the repository test commands:

~~~powershell
Set-Location .\llm-gateway-project\llm-gateway
mvn test
Set-Location ..\llm-gateway-ui
npm ci
npm run build
Set-Location C:\practice
~~~

Expected: Maven tests and npm build pass.

- [ ] **Step 3: Commit only CI changes**

~~~powershell
git -C llm-gateway-project add .gitlab-ci.yml
git -C llm-gateway-project commit -m "ci: deploy gateway automatically from HTTPS registry"
~~~

### Task 5: Add the soft-training production stack and deployment script

**Files:**
- Create: 软项智训/deploy/production/docker-compose.yml
- Create: 软项智训/deploy/production/.env.example
- Create: 软项智训/deploy/nginx/ztmdcg.cn.conf
- Create: 软项智训/deploy/scripts/deploy-production.sh

- [ ] **Step 1: Add the soft-training environment contract**

~~~dotenv
BACKEND_IMAGE=registry.ztmdcg.cn/root/soft-training/backend:example-commit-sha
FRONTEND_IMAGE=registry.ztmdcg.cn/root/soft-training/frontend:example-commit-sha
SOFT_MYSQL_DATABASE=soft_training
SOFT_MYSQL_USER=soft_training_app
SOFT_MYSQL_PASSWORD=example-only-generate-with-openssl-rand-hex-32
SOFT_REDIS_PASSWORD=example-only-generate-with-openssl-rand-hex-32
MINIO_ROOT_USER=ztmdcgadmin
MINIO_ROOT_PASSWORD=example-only-generate-with-openssl-rand-hex-32
JWT_SECRET=example-only-generate-with-openssl-rand-hex-32
LLM_GATEWAY_API_KEY=sk-gw-example-only
DASHSCOPE_API_KEY=sk-example-only
~~~

- [ ] **Step 2: Add the production Compose**

~~~yaml
name: soft-training-production

services:
  backend:
    image: ${BACKEND_IMAGE:?set BACKEND_IMAGE}
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: ztmdcg-mysql
      MYSQL_PORT: "3306"
      MYSQL_DB: ${SOFT_MYSQL_DATABASE:-soft_training}
      MYSQL_USER: ${SOFT_MYSQL_USER:-soft_training_app}
      MYSQL_PASSWORD: ${SOFT_MYSQL_PASSWORD:?set SOFT_MYSQL_PASSWORD}
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "10"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: "2"
      REDIS_HOST: ztmdcg-redis-soft
      REDIS_PORT: "6379"
      REDIS_PASSWORD: ${SOFT_REDIS_PASSWORD:?set SOFT_REDIS_PASSWORD}
      MINIO_ENDPOINT: http://ztmdcg-minio:9000
      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER:?set MINIO_ROOT_USER}
      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}
      MINIO_BUCKET: soft-training
      QDRANT_HOST: ztmdcg-qdrant
      QDRANT_GRPC_PORT: "6334"
      QDRANT_REST_URL: http://ztmdcg-qdrant:6333
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET}
      CORS_ALLOWED_ORIGINS: https://ztmdcg.cn
      LLM_GATEWAY_BASE_URL: http://llm-gateway:8080/v1
      LLM_GATEWAY_API_KEY: ${LLM_GATEWAY_API_KEY:?set LLM_GATEWAY_API_KEY}
      LLM_GATEWAY_MODEL: default
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:?set DASHSCOPE_API_KEY}
      DASHSCOPE_BASE_URL: https://dashscope.aliyuncs.com/compatible-mode/v1
      DASHSCOPE_EMBEDDING_MODEL: text-embedding-v4
      DASHSCOPE_EMBEDDING_DIMENSION: "1024"
      DASHSCOPE_RERANK_URL: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
      DASHSCOPE_RERANK_MODEL: gte-rerank-v2
      JAVA_TOOL_OPTIONS: -Xms256m -Xmx560m
    ports:
      - 127.0.0.1:18090:8090
    volumes:
      - backend-logs:/app/logs
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8090/actuator/health | grep -q UP"]
      interval: 15s
      timeout: 5s
      retries: 18
      start_period: 40s
    mem_limit: 768m
    networks: [ztmdcg-net]

  frontend:
    image: ${FRONTEND_IMAGE:?set FRONTEND_IMAGE}
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - 127.0.0.1:18080:80
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/health | grep -q ok"]
      interval: 15s
      timeout: 5s
      retries: 10
    mem_limit: 128m
    networks: [ztmdcg-net]

volumes:
  backend-logs:
    name: soft-training-production-logs

networks:
  ztmdcg-net:
    external: true
~~~

- [ ] **Step 3: Add the public Nginx virtual host**

~~~nginx
server {
    listen 80;
    listen [::]:80;
    server_name ztmdcg.cn www.ztmdcg.cn;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://ztmdcg.cn$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ztmdcg.cn www.ztmdcg.cn;

    ssl_certificate /etc/letsencrypt/live/ztmdcg.cn/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ztmdcg.cn/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 55m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://127.0.0.1:18090/api/;
        include /opt/ztmdcg/nginx/snippets/proxy-common.conf;
        proxy_read_timeout 70s;
    }

    location / {
        proxy_pass http://127.0.0.1:18080;
        include /opt/ztmdcg/nginx/snippets/proxy-common.conf;
    }
}
~~~

- [ ] **Step 4: Add the soft-training deployment script**

Create deploy/scripts/deploy-production.sh:

~~~bash
#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DIR=/opt/ztmdcg/platform
APP_DIR=/opt/ztmdcg/apps/soft-training
NGINX_DIR=/opt/ztmdcg/nginx
SECRETS_DIR=/opt/ztmdcg/secrets
STATE_FILE="$APP_DIR/release.env"
LOCK_FILE=/var/lock/ztmdcg-deploy.lock

required_ci=(CI_REGISTRY CI_REGISTRY_USER CI_REGISTRY_PASSWORD CI_REGISTRY_IMAGE CI_COMMIT_SHA)
for name in "${required_ci[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "missing CI variable: $name" >&2; exit 1; }
done

for file in "$SECRETS_DIR/platform.env" "$SECRETS_DIR/soft-training.env" "$PLATFORM_DIR/docker-compose.yml" "$NGINX_DIR/snippets/proxy-common.conf"; do
  [[ -r "$file" ]] || { echo "missing required file: $file" >&2; exit 1; }
done

exec 9>"$LOCK_FILE"
flock -w 600 9

install -d -m 750 "$APP_DIR" "$NGINX_DIR/conf.d"
install -m 640 "$SOURCE_ROOT/deploy/production/docker-compose.yml" "$APP_DIR/docker-compose.yml"

set -a
source "$SECRETS_DIR/platform.env"
source "$SECRETS_DIR/soft-training.env"
set +a

docker compose --env-file "$SECRETS_DIR/platform.env" -f "$PLATFORM_DIR/docker-compose.yml" up -d --wait

export BACKEND_IMAGE="$CI_REGISTRY_IMAGE/backend:$CI_COMMIT_SHA"
export FRONTEND_IMAGE="$CI_REGISTRY_IMAGE/frontend:$CI_COMMIT_SHA"

previous_backend=
previous_frontend=
if [[ -r "$STATE_FILE" ]]; then
  source "$STATE_FILE"
  previous_backend="${BACKEND_IMAGE_CURRENT:-}"
  previous_frontend="${FRONTEND_IMAGE_CURRENT:-}"
fi

nginx_target="$NGINX_DIR/conf.d/ztmdcg.cn.conf"
nginx_backup="$APP_DIR/ztmdcg.cn.conf.previous"
[[ ! -f "$nginx_target" ]] || cp "$nginx_target" "$nginx_backup"
install -m 640 "$SOURCE_ROOT/deploy/nginx/ztmdcg.cn.conf" "$nginx_target"
sudo /usr/sbin/nginx -t

docker_config="$(mktemp -d)"
export DOCKER_CONFIG="$docker_config"
trap 'rm -rf "$docker_config"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"

compose=(docker compose -f "$APP_DIR/docker-compose.yml")
"${compose[@]}" config --quiet
"${compose[@]}" pull

if ! "${compose[@]}" up -d --wait; then
  if [[ -n "$previous_backend" && -n "$previous_frontend" ]]; then
    export BACKEND_IMAGE="$previous_backend"
    export FRONTEND_IMAGE="$previous_frontend"
    docker pull "$previous_backend"
    docker pull "$previous_frontend"
    "${compose[@]}" up -d --wait
  fi
  if [[ -f "$nginx_backup" ]]; then
    install -m 640 "$nginx_backup" "$nginx_target"
    sudo /usr/sbin/nginx -t
    sudo /bin/systemctl reload nginx
  fi
  exit 1
fi

sudo /bin/systemctl reload nginx
printf 'BACKEND_IMAGE_CURRENT=%q\nFRONTEND_IMAGE_CURRENT=%q\n' "$BACKEND_IMAGE" "$FRONTEND_IMAGE" > "$STATE_FILE"
chmod 640 "$STATE_FILE"
docker image prune -af --filter until=168h
~~~

- [ ] **Step 5: Validate and commit**

~~~powershell
docker compose --env-file '.\软项智训\deploy\production\.env.example' -f '.\软项智训\deploy\production\docker-compose.yml' config --quiet
bash -n '.\软项智训\deploy\scripts\deploy-production.sh'
git -C '软项智训' add deploy
git -C '软项智训' commit -m "feat: add soft-training production deployment"
~~~

Expected: Compose and Bash validation pass.

### Task 6: Add the soft-training GitLab pipeline

**Files:**
- Create: 软项智训/.gitlab-ci.yml

- [ ] **Step 1: Add verification, image, and deployment jobs**

~~~yaml
stages: [verify, image, deploy]

variables:
  MAVEN_OPTS: -Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository
  GIT_DEPTH: "20"

cache:
  paths:
    - .m2/repository/
    - frontend/.npm/

backend_test:
  stage: verify
  image: maven:3.9-eclipse-temurin-17
  before_script:
    - mkdir -p ~/.m2
    - printf '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > ~/.m2/settings.xml
  script:
    - cd backend
    - mvn --batch-mode test
  artifacts:
    when: always
    reports:
      junit: backend/target/surefire-reports/TEST-*.xml

frontend_lint_build:
  stage: verify
  image: node:22-alpine
  before_script:
    - cd frontend
    - npm config set registry https://registry.npmmirror.com
    - npm ci --cache .npm --prefer-offline
  script:
    - npm run lint
    - npm run build

build_images:
  stage: image
  image: docker:27-cli
  variables:
    DOCKER_HOST: tcp://docker:2375
    DOCKER_TLS_CERTDIR: ""
  services:
    - name: docker:27-dind
      alias: docker
      command:
        - --registry-mirror=https://docker.m.daocloud.io
        - --registry-mirror=https://docker.1ms.run
  before_script:
    - echo "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"
  script:
    - docker build --pull -t "$CI_REGISTRY_IMAGE/backend:$CI_COMMIT_SHA" backend
    - docker build --pull -t "$CI_REGISTRY_IMAGE/frontend:$CI_COMMIT_SHA" frontend
    - docker push "$CI_REGISTRY_IMAGE/backend:$CI_COMMIT_SHA"
    - docker push "$CI_REGISTRY_IMAGE/frontend:$CI_COMMIT_SHA"
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'

deploy_production:
  stage: deploy
  tags: [ztmdcg-production]
  needs: [build_images]
  variables:
    GIT_DEPTH: "1"
    DOCKER_HOST: unix:///var/run/docker.sock
  script:
    - bash deploy/scripts/deploy-production.sh
  environment:
    name: production
    url: https://ztmdcg.cn
  resource_group: soft-training-production
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
~~~

- [ ] **Step 2: Run project verification locally**

~~~powershell
Set-Location 'C:\practice\软项智训\frontend'
npm ci
npm run lint
npm run build
Set-Location '..\backend'
mvn test
Set-Location 'C:\practice'
~~~

Expected: all commands pass.

- [ ] **Step 3: Commit the pipeline**

~~~powershell
git -C '软项智训' add .gitlab-ci.yml
git -C '软项智训' commit -m "ci: add automatic production pipeline"
~~~

### Task 7: Add backup operations and the consolidated runbook

**Files:**
- Create: llm-gateway-project/deploy/scripts/backup-runtime.sh
- Modify: llm-gateway-project/docs/dual-server-gitlab-docker-compose-guide.md
- Modify: llm-gateway-project/README.md
- Modify: 软项智训/README.md

- [ ] **Step 1: Add the backup script**

~~~bash
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PLATFORM_DIR=/opt/ztmdcg/platform
SECRETS_FILE=/opt/ztmdcg/secrets/platform.env
BACKUP_ROOT=/data/ztmdcg/backups
stamp="$(date +%F_%H%M%S)"
target="$BACKUP_ROOT/$stamp"

[[ -r "$SECRETS_FILE" ]] || { echo "missing $SECRETS_FILE" >&2; exit 1; }
install -d -m 700 "$target/mysql" "$target/minio" "$target/qdrant"

set -a
source "$SECRETS_FILE"
set +a

compose=(docker compose --env-file "$SECRETS_FILE" -f "$PLATFORM_DIR/docker-compose.yml")

"${compose[@]}" exec -T mysql sh -ec \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --databases "$SOFT_MYSQL_DATABASE" "$GATEWAY_MYSQL_DATABASE"' \
  > "$target/mysql/databases.sql"

docker run --rm --network ztmdcg-net \
  -v "$target/minio:/backup" \
  minio/mc:RELEASE.2025-04-16T18-13-26Z \
  sh -ec "mc alias set source http://ztmdcg-minio:9000 '$MINIO_ROOT_USER' '$MINIO_ROOT_PASSWORD' && mc mirror --overwrite source /backup"

collections="$(curl -fsS http://127.0.0.1:6333/collections | jq -r '.result.collections[].name')"
for collection in $collections; do
  response="$(curl -fsS -X POST "http://127.0.0.1:6333/collections/$collection/snapshots")"
  snapshot="$(printf '%s' "$response" | jq -r '.result.name')"
  curl -fsS "http://127.0.0.1:6333/collections/$collection/snapshots/$snapshot" \
    -o "$target/qdrant/$collection-$snapshot"
done

find "$target" -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > "$target/SHA256SUMS"
echo "backup complete: $target"
~~~

- [ ] **Step 2: Add the controlled MySQL restore script**

Create deploy/scripts/restore-mysql.sh:

~~~bash
#!/usr/bin/env bash
set -Eeuo pipefail

backup_file="${1:?usage: restore-mysql.sh /data/ztmdcg/backups/YYYY-MM-DD_HHMMSS/mysql/databases.sql}"
platform_dir=/opt/ztmdcg/platform
secrets_file=/opt/ztmdcg/secrets/platform.env
gateway_secrets=/opt/ztmdcg/secrets/llm-gateway.env
soft_secrets=/opt/ztmdcg/secrets/soft-training.env
soft_dir=/opt/ztmdcg/apps/soft-training
gateway_dir=/opt/ztmdcg/apps/llm-gateway

[[ -r "$backup_file" ]] || { echo "backup file is not readable: $backup_file" >&2; exit 1; }
for file in "$secrets_file" "$gateway_secrets" "$soft_secrets" "$gateway_dir/release.env" "$soft_dir/release.env"; do
  [[ -r "$file" ]] || { echo "required restore file is not readable: $file" >&2; exit 1; }
done

set -a
source "$secrets_file"
source "$gateway_secrets"
source "$soft_secrets"
source "$gateway_dir/release.env"
source "$soft_dir/release.env"
set +a

export GATEWAY_IMAGE="$GATEWAY_IMAGE_CURRENT"
export UI_IMAGE="$UI_IMAGE_CURRENT"
export BACKEND_IMAGE="$BACKEND_IMAGE_CURRENT"
export FRONTEND_IMAGE="$FRONTEND_IMAGE_CURRENT"

docker compose -f "$soft_dir/docker-compose.yml" stop backend frontend || true
docker compose -f "$gateway_dir/docker-compose.yml" stop gateway ui || true
docker compose --env-file "$secrets_file" -f "$platform_dir/docker-compose.yml" up -d --wait mysql
docker compose --env-file "$secrets_file" -f "$platform_dir/docker-compose.yml" exec -T mysql sh -ec \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --protocol=socket' < "$backup_file"
docker compose -f "$gateway_dir/docker-compose.yml" up -d --wait gateway ui
docker compose -f "$soft_dir/docker-compose.yml" up -d --wait backend frontend
echo "MySQL restore complete."
~~~

- [ ] **Step 3: Rewrite the operator guide with exact phases**

The final guide must contain these numbered sections and complete commands:

1. Final topology and port table.
2. DNSPod records and propagation checks using nslookup.
3. Security group rules for both clouds.
4. Ubuntu reinstallation checks, timezone, 4GB swap, and Docker installation.
5. JD Cloud GitLab CE installation from the official package repository.
6. Exact gitlab.rb settings for HTTPS, Registry, memory tuning, disabled sign-up, and backup retention.
7. Build Runner registration with Docker executor, privileged mode, and concurrent = 1.
8. Tencent Cloud directory creation, Docker network creation, Nginx/Certbot installation, and the restricted sudoers file.
9. Exact generation of platform.env, llm-gateway.env, and soft-training.env using openssl rand -hex 32.
10. ACME bootstrap configuration, Certbot webroot issuance, final Nginx configuration, and renewal verification.
11. Deployment Runner registration with tag ztmdcg-production and project locking.
12. Creating the two private GitLab projects and pushing both local repositories.
13. First deployment order: platform and llm-gateway first, Nacos secrets second, create a Gateway business API Key third, soft-training fourth.
14. Nacos SSH tunnel, exact keys to populate, and copying the generated Gateway business API Key into soft-training.env.
15. Acceptance checks for HTTPS, login, uploads, Gateway non-stream/SSE, and soft-training AI calls.
16. Automatic rollback, manual rerun of an old pipeline, and the prohibition on down -v.
17. Docker daemon JSON log rotation, daily backup command, systemd timer example, restore drill, disk monitoring, and troubleshooting decision tree.

The guide must use gitlab.ztmdcg.cn and registry.ztmdcg.cn everywhere; remove all old 10.1.0.16, 172.16.0.5, port 5050, K3s, and manual-deploy instructions.

- [ ] **Step 4: Update both READMEs**

Add a prominent link to the consolidated guide near the top of each README. The soft-training README must also state that production calls the Gateway through the Docker alias llm-gateway rather than host.docker.internal.

- [ ] **Step 5: Validate and commit documentation/operations**

~~~powershell
bash -n .\llm-gateway-project\deploy\scripts\backup-runtime.sh
bash -n .\llm-gateway-project\deploy\scripts\restore-mysql.sh
rg -n "10\.1\.0\.16|172\.16\.0\.5|:5050|deploy_k3s|K3s" .\llm-gateway-project\docs\dual-server-gitlab-docker-compose-guide.md
~~~

Expected: Bash validation passes and ripgrep returns no matches.

Commit without staging the unrelated CacheKey changes:

~~~powershell
git -C llm-gateway-project add deploy/scripts/backup-runtime.sh deploy/scripts/restore-mysql.sh docs/dual-server-gitlab-docker-compose-guide.md README.md
git -C llm-gateway-project commit -m "docs: add dual-server production runbook"
git -C '软项智训' add README.md
git -C '软项智训' commit -m "docs: link production deployment guide"
~~~

### Task 8: Run the complete verification gate

**Files:**
- Verify all files changed in Tasks 1-7.

- [ ] **Step 1: Run deployment validation**

~~~powershell
powershell -ExecutionPolicy Bypass -File .\llm-gateway-project\deploy\tests\validate-dual-server.ps1
~~~

Expected: Dual-server deployment validation passed.

- [ ] **Step 2: Run both backend and frontend checks**

~~~powershell
Set-Location 'C:\practice\llm-gateway-project\llm-gateway'
mvn test
Set-Location '..\llm-gateway-ui'
npm ci
npm run build
npm run format:check

Set-Location 'C:\practice\软项智训\backend'
mvn test
Set-Location '..\frontend'
npm ci
npm run lint
npm run build
Set-Location 'C:\practice'
~~~

Expected: all mandatory checks pass; the existing Gateway format check may remain an allowed CI warning only if its pre-existing files still fail.

- [ ] **Step 3: Review Git diffs without disturbing user changes**

~~~powershell
git -C llm-gateway-project status --short
git -C llm-gateway-project diff --check
git -C '软项智训' status --short
git -C '软项智训' diff --check
~~~

Expected:

- llm-gateway-project still preserves any pre-existing user modifications to CacheKey.java and ExactMatchCacheTest.java.
- No deployment file has trailing whitespace or conflict markers.
- Soft-training contains the intended tracked project and deployment assets.

- [ ] **Step 4: Final documentation audit**

Confirm the runbook has an explicit success indicator and failure action for every command that mutates either server. Confirm no password, token, or API key with a usable real value appears in Git history.
