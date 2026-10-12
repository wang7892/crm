$ErrorActionPreference = 'Stop'
$originalMavenOpts = $env:MAVEN_OPTS

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
chcp.com 65001 | Out-Null

Push-Location $PSScriptRoot
try {
    $env:MAVEN_OPTS = "$originalMavenOpts -Xmx1024m -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
    rtk proxy (Join-Path $PSScriptRoot 'mvnw.cmd') -pl backend/app -am clean install '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. See the Maven output above.' }
} finally {
    $env:MAVEN_OPTS = $originalMavenOpts
    Pop-Location
}
