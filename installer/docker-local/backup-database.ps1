param([string]$ConfigPath)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $ConfigPath) { $ConfigPath = Join-Path $repoRoot 'cordys-crm.properties' }
$properties = @{}
foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $ConfigPath) {
    if ($line -match '^\s*([^#!\s][^=]*?)\s*=(.*)$') { $properties[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$jdbcUrl = [string]$properties['spring.datasource.url']
if ($jdbcUrl -notmatch '^jdbc:mysql://([^/:]+)(?::(\d+))?/([^?]+)') { throw 'Expected a MySQL JDBC URL.' }
$dbHost = $Matches[1]
$dbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }
$dbName = [uri]::UnescapeDataString($Matches[3])
if ($dbName -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Unsupported database name.' }
if ($dbHost -in @('localhost', '127.0.0.1')) { $dbHost = 'host.docker.internal' }
$backupDirectory = Join-Path $repoRoot 'runtime/docker/backups'
New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
$backupName = "${dbName}-before-docker-$(Get-Date -Format 'yyyyMMdd-HHmmss').sql"
$envNames = @('MYSQL_PWD', 'CRM_DB_HOST', 'CRM_DB_PORT', 'CRM_DB_NAME', 'CRM_DB_USER', 'CRM_BACKUP_NAME')
$previousEnv = @{}
foreach ($envName in $envNames) { $previousEnv[$envName] = [Environment]::GetEnvironmentVariable($envName, 'Process') }

try {
    $env:MYSQL_PWD = $properties['spring.datasource.password']
    $env:CRM_DB_HOST = $dbHost
    $env:CRM_DB_PORT = $dbPort
    $env:CRM_DB_NAME = $dbName
    $env:CRM_DB_USER = $properties['spring.datasource.username']
    $env:CRM_BACKUP_NAME = $backupName
    $dumpCommand = 'exec mysqldump --host="$CRM_DB_HOST" --port="$CRM_DB_PORT" --user="$CRM_DB_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers --databases "$CRM_DB_NAME" --result-file="/backup/$CRM_BACKUP_NAME"'
    rtk proxy docker run --rm --env MYSQL_PWD --env CRM_DB_HOST --env CRM_DB_PORT --env CRM_DB_NAME --env CRM_DB_USER --env CRM_BACKUP_NAME --mount "type=bind,source=$backupDirectory,target=/backup" --entrypoint sh registry.cn-qingdao.aliyuncs.com/dataease/mysql:8.4.11 -c $dumpCommand
    if ($LASTEXITCODE -ne 0) { throw 'Database backup failed; do not start the CRM until the connection and backup have been checked.' }
    $backupPath = Join-Path $backupDirectory $backupName
    if ((Get-Item -LiteralPath $backupPath).Length -eq 0) { throw 'Database backup is empty.' }
    Write-Host "Database backup: $backupPath"
} finally {
    foreach ($envName in $envNames) { [Environment]::SetEnvironmentVariable($envName, $previousEnv[$envName], 'Process') }
}
