param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$inheritedDatasource = @{}
# Isolate generic datasource variables inherited from other local projects.
# Only this process is changed; user and machine environment settings stay intact.
foreach ($key in @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')) {
    $inheritedDatasource[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $null, 'Process')
}
Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipBuild) {
        & mvn -pl tduck-api -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $jarPath = Join-Path $projectRoot 'tduck-api/target/tduck-api.jar'
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the backend first, or omit -SkipBuild.' }
    & java '-Dfile.encoding=UTF-8' -jar $jarPath '--spring.profiles.active=dev'
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error. Check logs/error.log.' }
} finally {
    foreach ($key in $inheritedDatasource.Keys) {
        [Environment]::SetEnvironmentVariable($key, $inheritedDatasource[$key], 'Process')
    }
    Pop-Location
}
