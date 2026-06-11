$root = "E:\CordysCRM-1.6.0"
$jar = Join-Path $root "backend\app\target\app-main.jar"
$config = "file:///E:/CordysCRM-1.6.0/cordys-crm.properties"
$logbackConfig = "file:///E:/CordysCRM-1.6.0/backend/app/src/main/resources/logback-spring.xml"

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
    "--logging.file.path=$logDirProperty"
