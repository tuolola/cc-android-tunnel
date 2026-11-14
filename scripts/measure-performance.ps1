<#
.SYNOPSIS
Collect repeatable ADB startup or UI frame samples without changing app data or settings.
.EXAMPLE
.\scripts\measure-performance.ps1 -Mode Startup -Serial emulator-5554 -Iterations 10
.EXAMPLE
.\scripts\measure-performance.ps1 -Mode Frames -Scenario drawer -DurationSeconds 20
.NOTES
Startup force-stops the selected app before EVERY launch. Finish active work first.
Frames measures the current app process: open the desired screen before running it,
then repeat the named interaction during the sampling window. Do not restart the app.
These are diagnostic samples, not Macrobenchmark results or a pass/fail assessment.
#>
[CmdletBinding()]
param(
    [string]$AdbPath = '',
    [string]$Serial = '',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')]
    [string]$Package = 'dev.ccdesk.nativeapp',
    [ValidateSet('Startup', 'Frames')]
    [string]$Mode = 'Frames',
    [ValidateRange(1, 1000)]
    [int]$Iterations = 10,
    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 15,
    [string]$Scenario = '',
    [string]$OutputDirectory = '',
    [string]$Component = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) { $AdbPath = Join-Path $projectRoot '.android-sdk\platform-tools\adb.exe' }
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
if (-not $Component) { $Component = "$Package/.MainActivity" }
if ($Component -notmatch '^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$' -or
    $Component.Split('/')[0] -ne $Package) {
    throw 'Component must be a package/activity component in the selected Package.'
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$WithoutSerial)
    $commandArguments = @()
    if (-not $WithoutSerial) { $commandArguments += @('-s', $script:Serial) }
    $commandArguments += $Arguments
    $lines = & $script:AdbPath @commandArguments 2>&1
    $code = $LASTEXITCODE
    $result = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0) { throw "ADB failed (exit $code): $result" }
    return $result
}

function Get-Number {
    param([string]$Text, [string]$Pattern)
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($match.Success) {
        return [double]::Parse($match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    }
    return $null
}

function Get-TimingSummary {
    param([double[]]$Values)
    $sorted = @($Values | Sort-Object)
    $count = $sorted.Count
    if ($count -eq 0) { return $null }
    $middle = [int][math]::Floor($count / 2)
    $median = $sorted[$middle]
    if ($count % 2 -eq 0) { $median = ($sorted[$middle - 1] + $sorted[$middle]) / 2 }
    return [ordered]@{
        count = $count
        minMs = $sorted[0]
        medianMs = $median
        p90Ms = $sorted[[int][math]::Ceiling($count * 0.9) - 1]
        maxMs = $sorted[$count - 1]
    }
}

function Get-FrameProcess {
    param([string]$Text)
    $processes = [regex]::Matches($Text, 'Graphics info for pid\s+(\d+)\s+\[([^\]]+)\]')
    if ($processes.Count -ne 1) {
        throw 'Expected exactly one gfxinfo process. Missing or multiple processes cannot be safely aggregated by this script.'
    }
    return [ordered]@{
        processId = $processes[0].Groups[1].Value
        processName = $processes[0].Groups[2].Value
    }
}

function Assert-SameFrameProcess {
    param($Expected, $Actual)
    if ($Expected.processId -ne $Actual.processId -or $Expected.processName -ne $Actual.processName) {
        throw 'The app process changed during frame sampling. Discard this sample and repeat without restarting the app.'
    }
}

$deviceList = Invoke-Adb -Arguments @('devices', '-l') -WithoutSerial
$devices = @([regex]::Matches($deviceList, '(?m)^(\S+)\s+device(?:\s|$)') |
    ForEach-Object { $_.Groups[1].Value })
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Connect exactly one ready device or select one explicitly with -Serial.' }
    $Serial = $devices[0]
} elseif ($Serial -notin $devices) {
    throw "Device '$Serial' is not connected and authorized. Check adb devices -l."
}

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot ('artifacts\performance\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
if (Test-Path -LiteralPath (Join-Path $OutputDirectory 'environment.json')) {
    throw 'OutputDirectory already contains a run. Choose a new directory to preserve previous samples.'
}
if (-not $Scenario) { $Scenario = $Mode.ToLowerInvariant() }

$packageInfo = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Package)
if ($packageInfo -notmatch '(?m)^\s*versionCode=') { throw "Package '$Package' is not installed." }
$display = Invoke-Adb -Arguments @('shell', 'dumpsys', 'display')
$environment = [ordered]@{
    capturedAt = (Get-Date).ToString('o')
    serial = $Serial
    model = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')).Trim()
    androidVersion = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.release')).Trim()
    api = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    hardware = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.hardware')).Trim()
    qemuProperty = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Trim()
    package = $Package
    debuggable = [bool]($packageInfo -match '\bDEBUGGABLE\b')
    packageVersionLines = @($packageInfo -split "`n" | Where-Object { $_ -match 'versionName=|versionCode=|lastUpdateTime=' } | ForEach-Object { $_.Trim() })
    screenSize = (Invoke-Adb -Arguments @('shell', 'wm', 'size')).Trim()
    displayTimingLines = @($display -split "`n" | Where-Object { $_ -match 'mActiveMode|mModeId|refreshRate|renderFrameRate' } | Select-Object -First 50 | ForEach-Object { $_.Trim() })
    animationScales = [ordered]@{}
}
foreach ($setting in @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')) {
    $environment.animationScales[$setting] = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'global', $setting)).Trim()
}
$environment | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'environment.json') -Encoding UTF8
$summary = [ordered]@{
    mode = $Mode
    scenario = $Scenario
    environment = $environment
    limitations = @('ADB diagnostic sample, not Macrobenchmark.', 'Debug builds and emulators are not release-device performance acceptance evidence.', 'No performance pass/fail threshold is applied.')
}

if ($Mode -eq 'Startup') {
    Write-Host "Measuring $Iterations force-stop launches of $Component. Every iteration, including the first, is retained."
    $samples = @()
    for ($index = 1; $index -le $Iterations; $index++) {
        $output = Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-S', '-n', $Component)
        $rawFile = 'startup-{0:D3}.txt' -f $index
        $output | Set-Content -LiteralPath (Join-Path $OutputDirectory $rawFile) -Encoding UTF8
        $total = Get-Number -Text $output -Pattern '^\s*TotalTime:\s*(\d+)\s*$'
        if ($output -notmatch '(?m)^\s*Status:\s*ok\s*$' -or $null -eq $total -or $total -le 0) {
            throw "Launch $index did not yield a successful timed launch; inspect $rawFile."
        }
        $state = [regex]::Match($output, '(?m)^\s*LaunchState:\s*(\S+)')
        $samples += [ordered]@{
            iteration = $index
            totalTimeMs = $total
            thisTimeMs = Get-Number -Text $output -Pattern '^\s*ThisTime:\s*(\d+)\s*$'
            waitTimeMs = Get-Number -Text $output -Pattern '^\s*WaitTime:\s*(\d+)\s*$'
            launchState = $(if ($state.Success) { $state.Groups[1].Value } else { $null })
            rawFile = $rawFile
        }
        Write-Host ("Launch {0}/{1}: TotalTime {2} ms" -f $index, $Iterations, $total)
        if ($index -lt $Iterations) { Start-Sleep -Seconds 1 }
    }
    $summary.component = $Component
    $summary.command = 'adb -s <serial> shell am start -W -S -n <component>'
    $summary.samples = $samples
    $summary.totalTime = Get-TimingSummary -Values @($samples | ForEach-Object { $_.totalTimeMs })
    $summary.percentileMethod = 'P90 is nearest rank; median averages the two middle samples for an even count.'
    $summary.limitations += 'TotalTime is the activity launch timing reported by Activity Manager, not time to fully drawn (TTFD), network readiness, or first usable interaction.'
    $summary.limitations += 'Force-stop creates process-cold launches; OS caches and compilation state are not reset. No first sample is discarded.'
} else {
    $before = Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package)
    if ($before -match 'No process found' -or $before -notmatch 'Total frames rendered:') {
        throw 'Open the target app and desired screen before starting a frame sample.'
    }
    $frameProcess = Get-FrameProcess -Text $before
    $reset = Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package, 'reset')
    $reset | Set-Content -LiteralPath (Join-Path $OutputDirectory 'gfxinfo-reset.txt') -Encoding UTF8
    Assert-SameFrameProcess -Expected $frameProcess -Actual (Get-FrameProcess -Text $reset)
    # Some Android versions print the OLD counters in the reset command output.
    # Read again to capture the reset epoch without assuming which behavior applies.
    $baseline = Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package)
    $baseline | Set-Content -LiteralPath (Join-Path $OutputDirectory 'gfxinfo-baseline.txt') -Encoding UTF8
    Assert-SameFrameProcess -Expected $frameProcess -Actual (Get-FrameProcess -Text $baseline)
    $baselineEpoch = [regex]::Match($baseline, 'Stats since:\s*(\d+)ns')
    $summary.samplingStartedAt = (Get-Date).ToString('o')
    Write-Host "NOW repeat '$Scenario' in the foreground app for $DurationSeconds seconds. Keep this app process running."
    for ($second = 0; $second -lt $DurationSeconds; $second++) { Start-Sleep -Seconds 1 }
    $output = Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package, 'framestats')
    $summary.samplingEndedAt = (Get-Date).ToString('o')
    $output | Set-Content -LiteralPath (Join-Path $OutputDirectory 'gfxinfo-framestats.txt') -Encoding UTF8
    Assert-SameFrameProcess -Expected $frameProcess -Actual (Get-FrameProcess -Text $output)
    $finalEpoch = [regex]::Match($output, 'Stats since:\s*(\d+)ns')
    $epochChecked = $baselineEpoch.Success -and $finalEpoch.Success
    if ($epochChecked -and $baselineEpoch.Groups[1].Value -ne $finalEpoch.Groups[1].Value) {
        throw 'Graphics statistics were reset again during sampling. Discard this sample and repeat without another collector resetting gfxinfo.'
    }
    $total = Get-Number -Text $output -Pattern '^\s*Total frames rendered:\s*(\d+)'
    $janky = Get-Number -Text $output -Pattern '^\s*Janky frames:\s*(\d+)'
    if ($null -eq $total -or $total -le 0 -or $null -eq $janky) {
        throw 'No usable frame sample was collected. Inspect gfxinfo-framestats.txt and repeat with visible interactions.'
    }
    $summary.requestedDurationSeconds = $DurationSeconds
    $summary.frameProcess = $frameProcess
    $summary.processStableAtChecks = $true
    $summary.statisticsEpochChecked = $epochChecked
    $summary.statisticsEpochNs = $(if ($epochChecked) { $finalEpoch.Groups[1].Value } else { $null })
    $summary.frames = [ordered]@{
        total = $total
        janky = $janky
        jankyPercent = [math]::Round(100 * $janky / $total, 3)
        p50Ms = Get-Number -Text $output -Pattern '^\s*50th percentile:\s*([\d.]+)ms'
        p90Ms = Get-Number -Text $output -Pattern '^\s*90th percentile:\s*([\d.]+)ms'
        p95Ms = Get-Number -Text $output -Pattern '^\s*95th percentile:\s*([\d.]+)ms'
        p99Ms = Get-Number -Text $output -Pattern '^\s*99th percentile:\s*([\d.]+)ms'
    }
    $summary.limitations += 'Percentiles and jank classification are reported by Android gfxinfo, not independently calculated from every displayed frame. The framestats history is bounded.'
    $summary.limitations += 'The window includes idle time and frames from background updates. The script does not verify that the named manual actions occurred and does not measure button touch-to-feedback latency.'
    Write-Host ("Frames: {0}; janky: {1} ({2}%)" -f $total, $janky, $summary.frames.jankyPercent)
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
Write-Host "Saved diagnostic results: $OutputDirectory"
