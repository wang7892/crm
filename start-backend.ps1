param(
    [string]$JarPath
)

$root = $PSScriptRoot
$jar = Join-Path $root "backend\app\target\app-main.jar"
if (-not [string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = if ([System.IO.Path]::IsPathRooted($JarPath)) {
        $JarPath
    } else {
        Join-Path $root $JarPath
    }
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Backend JAR not found: $jar"
    }
    $jar = (Resolve-Path -LiteralPath $jar).ProviderPath
}
$config = ([System.Uri](Join-Path $root "cordys-crm.properties")).AbsoluteUri
$logbackConfig = ([System.Uri](Join-Path $root "backend\app\src\main\resources\logback-spring.xml")).AbsoluteUri

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
chcp.com 65001 | Out-Null

$runtimeDir = Join-Path $root "runtime"
$tmpDir = Join-Path $runtimeDir "tmp"
$uploadTmpDir = Join-Path $tmpDir "upload"
$logDir = Join-Path $runtimeDir "logs\cordys-crm"
$logDirProperty = $logDir -replace '\\', '/'

# Local Redis uses a URL without credentials to override legacy global passwords.
$redisUrl = 'redis://127.0.0.1:6379'

New-Item -ItemType Directory -Force -Path $tmpDir, $uploadTmpDir, $logDir | Out-Null

& java `
    "-Dfile.encoding=UTF-8" `
    "-Dsun.stdout.encoding=UTF-8" `
    "-Dsun.stderr.encoding=UTF-8" `
    "-Djava.io.tmpdir=$tmpDir" `
    "-Dlogging.file.path=$logDirProperty" `
    -jar $jar `
    "--spring.config.additional-location=$config" `
    "--logging.config=$logbackConfig" `
    "--logging.file.path=$logDirProperty" `
    "--spring.servlet.multipart.location=$uploadTmpDir" `
    "--spring.data.redis.url=$redisUrl"
