param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleCache = '',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = $projectRoot
if (-not $AndroidSdk) {
    $localSdk = Join-Path $projectRoot '.android-sdk'
    if (Test-Path -LiteralPath $localSdk) { $AndroidSdk = $localSdk }
}
if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk with an Android SDK containing platform 36.' }
$resolvedSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path.Replace('\','/').Replace(':','\:')
Set-Content -LiteralPath (Join-Path $nativeRoot 'local.properties') -Value ('sdk.dir=' + $resolvedSdk) -Encoding utf8
if ($GradleCache) {
    New-Item -ItemType Directory -Force -Path $GradleCache | Out-Null
    $env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath $GradleCache).Path
}
$tasks = @(':app:assembleDebug')
if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest'; $tasks += ':app:lintDebug' }
Push-Location $nativeRoot
try {
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw ('Native build failed with exit code ' + $LASTEXITCODE) }
} finally { Pop-Location }
$output = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$apk = Join-Path $output 'CC-Android-Tunnel-debug.apk'
Copy-Item -LiteralPath (Join-Path $nativeRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Output ('APK: ' + $apk)
