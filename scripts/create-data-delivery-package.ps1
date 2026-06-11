param(
    [string]$OutputRoot,
    [string]$MysqlDumpPath,
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = "root",
    [string]$MysqlPassword,
    [string[]]$Databases = @("cordys-crm", "mail_monitoring_db", "wecom_monitoring_db"),
    [switch]$SkipDatabaseDump
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "outputs"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

function Read-PropertiesFile {
    param([string]$Path)

    $result = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $result
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }
        $index = $trimmed.IndexOf("=")
        if ($index -lt 1) {
            continue
        }
        $key = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1).Trim()
        $result[$key] = $value
    }
    return $result
}

function Resolve-MysqlDump {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (-not (Test-Path -LiteralPath $ExplicitPath)) {
            throw "mysqldump path not found: $ExplicitPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $command = Get-Command mysqldump -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe",
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe",
        "C:\Program Files\MariaDB 10.11\bin\mysqldump.exe",
        "C:\Program Files\MariaDB 11.4\bin\mysqldump.exe",
        "C:\Program Files\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysqldump.exe",
        "C:\Program Files\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysqldump.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "mysqldump was not found. Install MySQL client tools or pass -MysqlDumpPath."
}

function Copy-DirectoryIfExists {
    param(
        [string]$Source,
        [string]$Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        return [PSCustomObject]@{ Source = $Source; Copied = $false; Files = 0; SizeMB = 0 }
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $files = Get-ChildItem -LiteralPath $Source -Recurse -File -Force
    $count = 0
    $bytes = 0L
    foreach ($file in $files) {
        $relative = $file.FullName.Substring((Resolve-Path -LiteralPath $Source).Path.Length).TrimStart("\", "/")
        $target = Join-Path $Destination $relative
        $targetDir = Split-Path -Parent $target
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $count++
        $bytes += $file.Length
    }
    return [PSCustomObject]@{
        Source = $Source
        Copied = $true
        Files = $count
        SizeMB = [Math]::Round($bytes / 1MB, 2)
    }
}

$crmProps = Read-PropertiesFile -Path (Join-Path $repoRoot "cordys-crm.properties")
if ([string]::IsNullOrWhiteSpace($MysqlPassword) -and $crmProps.ContainsKey("spring.datasource.password")) {
    $MysqlPassword = $crmProps["spring.datasource.password"]
}

if (-not $SkipDatabaseDump -and [string]::IsNullOrWhiteSpace($MysqlPassword)) {
    throw "MySQL password is empty. Pass -MysqlPassword or set it in cordys-crm.properties."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stageName = "cordys-crm-data-delivery-$stamp"
$stage = Join-Path $OutputRoot $stageName
$zip = Join-Path $OutputRoot "$stageName.zip"
$databaseDir = Join-Path $stage "database"
$filesDir = Join-Path $stage "files"

New-Item -ItemType Directory -Force -Path $databaseDir, $filesDir | Out-Null

$dumpFile = Join-Path $databaseDir "cordys_mysql_all.sql"
$dumpDone = $false
if (-not $SkipDatabaseDump) {
    $resolvedMysqlDump = Resolve-MysqlDump -ExplicitPath $MysqlDumpPath
    $clientFile = Join-Path $databaseDir ".mysql-client.cnf"
    @(
        "[client]",
        "host=$MysqlHost",
        "port=$MysqlPort",
        "user=$MysqlUser",
        "password=$MysqlPassword",
        "default-character-set=utf8mb4"
    ) | Set-Content -LiteralPath $clientFile -Encoding ASCII

    try {
        $args = @(
            "--defaults-extra-file=$clientFile",
            "--single-transaction",
            "--routines",
            "--triggers",
            "--events",
            "--hex-blob",
            "--databases"
        ) + $Databases + @("--result-file=$dumpFile")

        & $resolvedMysqlDump @args
        if ($LASTEXITCODE -ne 0) {
            throw "mysqldump exited with code $LASTEXITCODE"
        }
        $dumpDone = $true
    }
    finally {
        if (Test-Path -LiteralPath $clientFile) {
            Remove-Item -LiteralPath $clientFile -Force
        }
    }
}

$copiedDirs = @()
$copiedDirs += Copy-DirectoryIfExists -Source (Join-Path $repoRoot "runtime\data\files") -Destination (Join-Path $filesDir "crm-runtime-data-files")
$copiedDirs += Copy-DirectoryIfExists -Source (Join-Path $repoRoot "runtime\files") -Destination (Join-Path $filesDir "crm-runtime-files")
$copiedDirs += Copy-DirectoryIfExists -Source (Join-Path $repoRoot "runtime\upload") -Destination (Join-Path $filesDir "crm-runtime-upload")
$copiedDirs += Copy-DirectoryIfExists -Source (Join-Path $repoRoot "mail monitoring\attachments") -Destination (Join-Path $filesDir "mail-monitoring-attachments")

$manifest = Join-Path $stage "DATA_PACKAGE_MANIFEST.txt"
$manifestLines = @()
$manifestLines += "Cordys CRM data delivery package"
$manifestLines += "CreatedAt=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))"
$manifestLines += "RepoRoot=$repoRoot"
$manifestLines += "DatabaseDump=$dumpDone"
if ($dumpDone) {
    $manifestLines += "DatabaseDumpFile=database\cordys_mysql_all.sql"
    $manifestLines += "Databases=$($Databases -join ',')"
}
$manifestLines += ""
$manifestLines += "Copied directories:"
foreach ($entry in $copiedDirs) {
    $manifestLines += "- Source=$($entry.Source); Copied=$($entry.Copied); Files=$($entry.Files); SizeMB=$($entry.SizeMB)"
}
$manifestLines += ""
$manifestLines += "Import example:"
$manifestLines += "mysql -h 127.0.0.1 -P 3306 -u root -p < database/cordys_mysql_all.sql"
$manifestLines += ""
$manifestLines += "Important:"
$manifestLines += "This package can contain customer data, email content, WeCom chat content, and attachments. Send it through a trusted encrypted channel only."
$manifestLines | Set-Content -LiteralPath $manifest -Encoding UTF8

Compress-Archive -LiteralPath $stage -DestinationPath $zip -Force

$zipItem = Get-Item -LiteralPath $zip
$result = [PSCustomObject]@{
    Stage = $stage
    Zip = $zip
    SizeMB = [Math]::Round($zipItem.Length / 1MB, 2)
    DatabaseDump = $dumpDone
}
$result | Format-List
