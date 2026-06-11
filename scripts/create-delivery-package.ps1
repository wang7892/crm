param(
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "outputs"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stageName = "cordys-crm-code-delivery-$stamp"
$stage = Join-Path $OutputRoot $stageName
$zip = Join-Path $OutputRoot "$stageName.zip"

New-Item -ItemType Directory -Force -Path $stage | Out-Null

$excludeDirNames = @(
    ".git",
    ".idea",
    ".vscode",
    ".codex_tmp",
    ".tmp_verify_mapper",
    "runtime",
    "tmp",
    "outputs",
    "node_modules",
    ".node",
    "target",
    "out",
    "attachments",
    "%SystemDrive%",
    '$root'
)

$excludeRelativeFiles = @(
    "cordys-crm.properties",
    "crm-run.log",
    "crm-run.err.log",
    "mail monitoring\config.properties",
    "wecom monitoring\config.properties",
    "frontend\packages\web\vite-dev.err.log",
    "frontend\packages\web\vite-dev.out.log"
)

$excludeExtensions = @(".class", ".log")

function Test-ShouldIncludeFile {
    param(
        [System.IO.FileInfo]$File
    )

    $relative = $File.FullName.Substring($repoRoot.Length).TrimStart("\", "/")
    $relativeKey = $relative -replace "/", "\"
    $parts = $relativeKey -split "\\"

    if ($parts.Length -gt 1) {
        $dirParts = $parts[0..($parts.Length - 2)]
        foreach ($part in $dirParts) {
            if ($excludeDirNames -contains $part) {
                return $false
            }
        }
    }

    if ($excludeRelativeFiles -contains $relativeKey) {
        return $false
    }

    if ($excludeExtensions -contains $File.Extension.ToLowerInvariant()) {
        return $false
    }

    return $true
}

$copied = 0
Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Force |
    Where-Object { Test-ShouldIncludeFile -File $_ } |
    ForEach-Object {
        $relative = $_.FullName.Substring($repoRoot.Length).TrimStart("\", "/")
        $destination = Join-Path $stage $relative
        $destinationDir = Split-Path -Parent $destination
        New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
        $copied++
    }

Compress-Archive -LiteralPath $stage -DestinationPath $zip -Force

$zipItem = Get-Item -LiteralPath $zip
[PSCustomObject]@{
    Stage = $stage
    Zip = $zip
    FileCount = $copied
    SizeMB = [Math]::Round($zipItem.Length / 1MB, 2)
}
