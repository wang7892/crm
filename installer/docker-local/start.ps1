param(
    [int]$Port = 8081,
    [string]$ConfigPath,
    [string]$FilesDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $ConfigPath) { $ConfigPath = Join-Path $repoRoot 'cordys-crm.properties' }
$ConfigPath = (Resolve-Path -LiteralPath $ConfigPath).Path
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Port must be between 1 and 65535.' }

$properties = @{}
foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $ConfigPath) {
    if ($line -match '^\s*([^#!\s][^=]*?)\s*=(.*)$') { $properties[$Matches[1].Trim()] = $Matches[2].Trim() }
}
if (-not $properties['spring.datasource.url']) { throw 'spring.datasource.url is missing in the selected configuration.' }

function Convert-LocalServiceUrl([string]$Url) {
    # Loopback inside a Linux container points at the container, not Windows.
    return $Url -replace '(?i)(://)(localhost|127\.0\.0\.1|\[::1\])(?=[:/?]|$)', '$1host.docker.internal'
}

if (-not $FilesDirectory) {
    $existingFiles = 'E:/opt/cordys/data/files'
    if (Test-Path -LiteralPath $existingFiles) { $FilesDirectory = $existingFiles }
    else { $FilesDirectory = Join-Path $repoRoot 'runtime/docker/files' }
}
New-Item -ItemType Directory -Force -Path $FilesDirectory | Out-Null
foreach ($runtimePath in @('runtime/tmp/upload', 'runtime/logs/cordys-crm', 'runtime/task-attachments', 'runtime/uploads/knowledge')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot $runtimePath) | Out-Null
}

$envFile = Join-Path $PSScriptRoot '.env'
$redisPassword = [guid]::NewGuid().ToString('N')
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match "^CRM_REDIS_PASSWORD='([a-f0-9]{32})'$" ) { $redisPassword = $Matches[1] }
    }
}

$values = [ordered]@{
    CRM_WEB_PORT = [string]$Port
    CRM_CONFIG_FILE = $ConfigPath.Replace('\', '/')
    CRM_FILES_DIR = (Resolve-Path -LiteralPath $FilesDirectory).Path.Replace('\', '/')
    CRM_DATABASE_URL = Convert-LocalServiceUrl $properties['spring.datasource.url']
    CRM_REDIS_PASSWORD = $redisPassword
    CRM_EXTERNAL_ORDER_URL = Convert-LocalServiceUrl $properties['crm.ai-agent.external-order.url']
    CRM_LLM_BASE_URL = Convert-LocalServiceUrl $properties['crm.ai-agent.llm.base-url']
    CRM_LLM_PROVIDER_0_BASE_URL = Convert-LocalServiceUrl $properties['crm.ai-agent.llm.providers[0].base-url']
}
$envLines = foreach ($item in $values.GetEnumerator()) {
    $envValue = [string]$item.Value
    if ($envValue.Contains("'")) { throw "Unsupported quote in configuration value: $($item.Key)" }
    "$($item.Key)='$envValue'"
}
[System.IO.File]::WriteAllLines($envFile, $envLines, [System.Text.UTF8Encoding]::new($false))
rtk proxy docker compose --env-file $envFile -f (Join-Path $PSScriptRoot 'compose.yaml') up -d --pull never
if ($LASTEXITCODE -ne 0) { throw 'CRM container startup failed.' }
Write-Host "CRM URL: http://localhost:${Port}/"
