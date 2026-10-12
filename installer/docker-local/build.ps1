$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$buildContext = Join-Path $PSScriptRoot '.build'
$originalMavenOpts = $env:MAVEN_OPTS

Push-Location $repoRoot
try {
    Push-Location (Join-Path $repoRoot 'frontend')
    try {
        rtk proxy pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        rtk proxy pnpm --workspace-concurrency=1 -r run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend type checking or production build failed.' }
    } finally { Pop-Location }

    $env:MAVEN_OPTS = "$originalMavenOpts -Xmx1024m"
    rtk proxy mvn -pl backend/app -am package '-Dmaven.test.skip=true'
    if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }

    New-Item -ItemType Directory -Force -Path $buildContext | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'backend/app/target/app-main.jar') -Destination (Join-Path $buildContext 'app.jar') -Force
    rtk proxy docker build --pull=false -t cordys-crm-custom:1.6.0 -f (Join-Path $PSScriptRoot 'Dockerfile') $buildContext
    if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
    Write-Host 'Built cordys-crm-custom:1.6.0 from the current local source. Backend tests were skipped during packaging.'
} finally {
    $env:MAVEN_OPTS = $originalMavenOpts
    Pop-Location
}
