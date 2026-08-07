param([string]$WorkspaceRoot = 'C:\practice')

$ErrorActionPreference = 'Stop'
$gatewayRepo = Join-Path $WorkspaceRoot 'llm-gateway-project'
$softProjectName = -join @([char]0x8F6F, [char]0x9879, [char]0x667A, [char]0x8BAD)
$softRepo = Join-Path $WorkspaceRoot $softProjectName
$guidePath = Join-Path $gatewayRepo 'docs\dual-server-gitlab-docker-compose-guide.md'
$softReadmePath = Join-Path $softRepo 'README.md'

$bashCandidates = @(
    (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
    (Join-Path $env:ProgramFiles 'Git\usr\bin\bash.exe'),
    ((Get-Command bash -ErrorAction SilentlyContinue | Select-Object -First 1).Source)
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

$bashExecutable = $null
foreach ($candidate in $bashCandidates) {
    & $candidate --version *> $null
    if ($LASTEXITCODE -eq 0) {
        $bashExecutable = $candidate
        break
    }
}
if (-not $bashExecutable) {
    throw 'A working Bash executable is required for deployment script validation'
}

$requiredFiles = @(
    (Join-Path $gatewayRepo 'deploy\platform\docker-compose.yml'),
    (Join-Path $gatewayRepo 'deploy\platform\.env.example'),
    (Join-Path $gatewayRepo 'deploy\production\docker-compose.yml'),
    (Join-Path $gatewayRepo 'deploy\production\.env.example'),
    (Join-Path $gatewayRepo 'deploy\scripts\deploy-production.sh'),
    (Join-Path $gatewayRepo 'deploy\scripts\backup-runtime.sh'),
    (Join-Path $gatewayRepo 'deploy\scripts\restore-mysql.sh'),
    (Join-Path $gatewayRepo 'deploy\tests\test-mysql-backup-restore.sh'),
    (Join-Path $gatewayRepo 'deploy\nginx\gateway.ztmdcg.cn.conf'),
    $guidePath,
    (Join-Path $softRepo '.gitlab-ci.yml'),
    (Join-Path $softRepo 'deploy\production\docker-compose.yml'),
    (Join-Path $softRepo 'deploy\production\.env.example'),
    (Join-Path $softRepo 'deploy\scripts\deploy-production.sh'),
    (Join-Path $softRepo 'deploy\nginx\ztmdcg.cn.conf'),
    $softReadmePath
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
    (Join-Path $gatewayRepo 'deploy\tests\test-mysql-backup-restore.sh'),
    (Join-Path $softRepo 'deploy\scripts\deploy-production.sh')
)

foreach ($script in $bashScripts) {
    & $bashExecutable -n $script
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

$softCompose = Get-Content -LiteralPath (Join-Path $softRepo 'deploy\production\docker-compose.yml') -Raw
$requiredSoftSecurityPatterns = @(
    'JWT_ACCESS_EXPIRATION_SECONDS',
    'JWT_REFRESH_EXPIRATION_SECONDS',
    'BOOTSTRAP_ADMIN_ENABLED',
    'BOOTSTRAP_ADMIN_USERNAME',
    'BOOTSTRAP_ADMIN_PASSWORD'
)
foreach ($pattern in $requiredSoftSecurityPatterns) {
    if ($softCompose -notmatch [regex]::Escape($pattern)) {
        throw "Soft-training production Compose is missing security setting: $pattern"
    }
}

$backupScript = Get-Content -LiteralPath (Join-Path $gatewayRepo 'deploy\scripts\backup-runtime.sh') -Raw
$requiredBackupPatterns = @(
    '--single-transaction',
    '--add-drop-database',
    '--entrypoint /bin/sh',
    'mc ls --json',
    'MINIO_BUCKET',
    '/snapshots',
    'SHA256SUMS',
    'flock -w 600'
)
foreach ($pattern in $requiredBackupPatterns) {
    if ($backupScript -notmatch [regex]::Escape($pattern)) {
        throw "Backup script is missing required safeguard: $pattern"
    }
}
if ($backupScript -match 'mc mirror --overwrite source/ /backup/') {
    throw 'Backup script must mirror MinIO one bucket at a time'
}

$restoreScript = Get-Content -LiteralPath (Join-Path $gatewayRepo 'deploy\scripts\restore-mysql.sh') -Raw
$requiredRestorePatterns = @(
    '--confirm',
    'sha256sum -c',
    'flock -w 600',
    'stop backend frontend',
    'stop gateway ui',
    'up -d --wait gateway ui',
    'up -d --wait backend frontend'
)
foreach ($pattern in $requiredRestorePatterns) {
    if ($restoreScript -notmatch [regex]::Escape($pattern)) {
        throw "Restore script is missing required control: $pattern"
    }
}

$guide = Get-Content -LiteralPath $guidePath -Raw
$requiredGuidePatterns = @(
    'gitlab.ztmdcg.cn',
    'registry.ztmdcg.cn',
    'nslookup ztmdcg.cn',
    'openssl rand -hex 32',
    'ztmdcg-production',
    'certbot renew --dry-run',
    'restore-mysql.sh',
    'docker compose down -v',
    'curl -N https://gateway.ztmdcg.cn/v1/chat/completions'
)
foreach ($pattern in $requiredGuidePatterns) {
    if ($guide -notmatch [regex]::Escape($pattern)) {
        throw "Operator guide is missing required content: $pattern"
    }
}

$forbiddenGuidePattern = '10\.1\.0\.16|172\.16\.0\.5|:5050|deploy_k3s|\bK3s\b|manual-deploy'
if ($guide -match $forbiddenGuidePattern) {
    throw "Operator guide still contains retired deployment content: $($Matches[0])"
}

$softReadme = Get-Content -LiteralPath $softReadmePath -Raw
if ($softReadme -notmatch 'dual-server-gitlab-docker-compose-guide\.md') {
    throw 'Soft-training README does not link the consolidated production guide'
}
$gatewayAliasExplanation = ($softReadme -split "`r?`n") | Where-Object {
    $_ -match 'http://llm-gateway:8080/v1' -and $_ -match 'host\.docker\.internal'
}
if (-not $gatewayAliasExplanation) {
    throw 'Soft-training README does not explain the production Gateway Docker alias'
}

Write-Host 'Dual-server deployment validation passed.'
