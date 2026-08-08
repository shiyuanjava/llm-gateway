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

function Get-ComposeModel {
    param([string]$ComposeFile, [string]$EnvFile)

    $renderedJson = docker compose --env-file $EnvFile -f $ComposeFile config --format json
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed: $ComposeFile"
    }

    try {
        return ($renderedJson | ConvertFrom-Json)
    }
    catch {
        throw "docker compose config returned invalid JSON: $ComposeFile"
    }
}

$platformComposePath = Join-Path $gatewayRepo 'deploy\platform\docker-compose.yml'
$platformEnvPath = Join-Path $gatewayRepo 'deploy\platform\.env.example'
$productionComposePath = Join-Path $gatewayRepo 'deploy\production\docker-compose.yml'
$productionEnvPath = Join-Path $gatewayRepo 'deploy\production\.env.example'
$platformComposeModel = Get-ComposeModel -ComposeFile $platformComposePath -EnvFile $platformEnvPath
$productionComposeModel = Get-ComposeModel -ComposeFile $productionComposePath -EnvFile $productionEnvPath
Test-Compose -ComposeFile (Join-Path $softRepo 'deploy\production\docker-compose.yml') -EnvFile (Join-Path $softRepo 'deploy\production\.env.example')

$composeContractViolations = @()
$productionGateway = $productionComposeModel.services.gateway
$gatewayHealthcheck = @($productionGateway.healthcheck.test) -join ' '
if ($gatewayHealthcheck -notmatch [regex]::Escape('/actuator/health/readiness')) {
    $composeContractViolations += 'Production gateway healthcheck must use /actuator/health/readiness'
}
if ($gatewayHealthcheck -match '/actuator/health(?:\s|["'']|$)') {
    $composeContractViolations += 'Production gateway healthcheck must not use the root /actuator/health endpoint'
}

$requiredGatewayEnvironment = [ordered]@{
    GATEWAY_ENVIRONMENT = 'prod'
    GATEWAY_REDIS_CONTROL_NODE = 'ztmdcg-redis-gateway:6379'
    GATEWAY_REDIS_CACHE_NODE = 'ztmdcg-redis-gateway:6379'
    GATEWAY_REDIS_CONTROL_MODE = 'standalone'
    GATEWAY_REDIS_CACHE_MODE = 'standalone'
}
foreach ($setting in $requiredGatewayEnvironment.GetEnumerator()) {
    $property = $productionGateway.environment.PSObject.Properties[$setting.Key]
    if (-not $property -or [string]$property.Value -cne $setting.Value) {
        $actualValue = if ($property) { [string]$property.Value } else { '<missing>' }
        $composeContractViolations += "Production gateway environment $($setting.Key) must be '$($setting.Value)' (actual: '$actualValue')"
    }
}

$gatewayRedis = $platformComposeModel.services.'redis-gateway'
$gatewayRedisCommand = @($gatewayRedis.command) -join ' '
$requiredGatewayRedisOptions = [ordered]@{
    'appendonly yes' = '--appendonly(?:\s+|=)yes(?:\s|$)'
    'appendfsync everysec' = '--appendfsync(?:\s+|=)everysec(?:\s|$)'
    'maxmemory-policy noeviction' = '--maxmemory-policy(?:\s+|=)noeviction(?:\s|$)'
}
foreach ($option in $requiredGatewayRedisOptions.GetEnumerator()) {
    if ($gatewayRedisCommand -notmatch $option.Value) {
        $composeContractViolations += "Platform redis-gateway command must contain $($option.Key)"
    }
}

$forbiddenGatewayRedisOptions = [ordered]@{
    'appendonly no' = '--appendonly(?:\s+|=)no(?:\s|$)'
    'maxmemory-policy allkeys-lru' = '--maxmemory-policy(?:\s+|=)allkeys-lru(?:\s|$)'
}
foreach ($option in $forbiddenGatewayRedisOptions.GetEnumerator()) {
    if ($gatewayRedisCommand -match $option.Value) {
        $composeContractViolations += "Platform redis-gateway command must not contain $($option.Key)"
    }
}

$gatewayRedisDataVolume = @($gatewayRedis.volumes) | Where-Object {
    $_.type -eq 'bind' -and
    $_.source -eq '/data/ztmdcg/redis-gateway' -and
    $_.target -eq '/data'
}
if (-not $gatewayRedisDataVolume) {
    $composeContractViolations += 'Platform redis-gateway must bind /data/ztmdcg/redis-gateway to /data'
}

if ($composeContractViolations) {
    throw ('Rendered production Compose contract violations:' + [Environment]::NewLine +
        (($composeContractViolations | ForEach-Object { " - $_" }) -join [Environment]::NewLine))
}

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
    'https://117.72.220.94',
    '117.72.220.94:5050',
    "letsencrypt['enable'] = false",
    'subjectAltName = IP:117.72.220.94',
    '/etc/docker/certs.d/117.72.220.94:5050',
    '--tls-ca-file',
    '--insecure-registry=117.72.220.94:5050',
    'git@117.72.220.94:ztmdcg/llm-gateway.git',
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

# The JD Cloud host has no ICP filing, so the guide must not contain an actionable
# domain-based GitLab/Registry address or any Let's Encrypt issuance step for it.
# The recovery section legitimately quotes the failed command and the bad config line
# so operators can recognise their own broken state, so a bare mention of the domain
# cannot be banned outright. The distinction is column position: a real config line
# starts at column 0 inside a fenced block, while every legitimate mention is inline
# code inside a sentence. Anchor the config patterns to line start.
$forbiddenGuidePatterns = @(
    'https://registry\.ztmdcg\.cn',
    "(?m)^external_url\s+'https://gitlab",
    "(?m)^registry_external_url\s+'https://registry",
    '--url\s+"https://gitlab',
    'git@gitlab\.ztmdcg\.cn',
    "(?m)^letsencrypt\['enable'\] = true",
    'renew-le-certs',
    '10\.1\.0\.16',
    '172\.16\.0\.5',
    'deploy_k3s',
    '\bK3s\b',
    'manual-deploy'
)
foreach ($pattern in $forbiddenGuidePatterns) {
    if ($guide -match $pattern) {
        throw "Operator guide still contains retired deployment content: $($Matches[0])"
    }
}

# The IP-based external_url must actually be present as a config line, not just prose.
if ($guide -notmatch "(?m)^external_url\s+'https://117\.72\.220\.94'") {
    throw 'Operator guide does not set external_url to the IP-based GitLab address'
}
if ($guide -notmatch "(?m)^registry_external_url\s+'https://117\.72\.220\.94:5050'") {
    throw 'Operator guide does not set registry_external_url to the IP-based registry'
}
if ($guide -notmatch "(?m)^letsencrypt\['enable'\] = false") {
    throw 'Operator guide does not disable Let us Encrypt on the unfiled JD Cloud host'
}

# Private-CA trust boundary: cross-host pulls must verify fully; only the JD Cloud
# local dind is allowed to skip verification.
$tencentInsecureRule = ($guide -split "`r?`n") | Where-Object {
    $_ -match 'daemon\.json' -and $_ -match 'insecure-registries'
}
if (-not $tencentInsecureRule) {
    throw 'Operator guide must forbid insecure-registries on the Tencent deployment host'
}

foreach ($ciPath in @((Join-Path $gatewayRepo '.gitlab-ci.yml'), (Join-Path $softRepo '.gitlab-ci.yml'))) {
    $ci = Get-Content -LiteralPath $ciPath -Raw
    if ($ci -notmatch [regex]::Escape('--insecure-registry=117.72.220.94:5050')) {
        throw "CI file does not let dind push to the private-CA registry: $ciPath"
    }
}

foreach ($envPath in @(
    (Join-Path $gatewayRepo 'deploy\production\.env.example'),
    (Join-Path $softRepo 'deploy\production\.env.example')
)) {
    $envExample = Get-Content -LiteralPath $envPath -Raw
    if ($envExample -match 'registry\.ztmdcg\.cn') {
        throw "Env example still references the retired registry domain: $envPath"
    }
    if ($envExample -notmatch [regex]::Escape('117.72.220.94:5050/ztmdcg/')) {
        throw "Env example does not use the IP-based registry path: $envPath"
    }
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
