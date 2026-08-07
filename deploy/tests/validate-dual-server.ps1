param([string]$WorkspaceRoot = 'C:\practice')

$ErrorActionPreference = 'Stop'
$gatewayRepo = Join-Path $WorkspaceRoot 'llm-gateway-project'
$softProjectName = -join @([char]0x8F6F, [char]0x9879, [char]0x667A, [char]0x8BAD)
$softRepo = Join-Path $WorkspaceRoot $softProjectName

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
