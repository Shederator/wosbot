param(
    [Parameter(Mandatory = $true)]
    [string]$ImagePath,
    [ValidateSet("stable", "nightly")]
    [string]$Channel = "stable",
    [string]$ProductName = "Frostguard",
    [string]$BootstrapProductName = $ProductName
)

$ErrorActionPreference = "Stop"
$image = (Resolve-Path -LiteralPath $ImagePath).Path
$appLauncher = Join-Path $image "$ProductName.exe"
$watcherName = if ($Channel -eq "nightly") { "FrostguardNightlyWatcher" } else { "FrostguardWatcher" }
$watcherLauncher = Join-Path $image "$watcherName.exe"
if (-not (Test-Path -LiteralPath $appLauncher -PathType Leaf)) {
    throw "$ProductName.exe is missing from $image"
}
if (-not (Test-Path -LiteralPath $watcherLauncher -PathType Leaf)) {
    throw "$watcherName.exe is missing from $image"
}

$versionInfo = (Get-Item -LiteralPath $appLauncher).VersionInfo
if ($versionInfo.ProductName -ne $BootstrapProductName -or
        $versionInfo.CompanyName -ne "Frostguard" -or
        $versionInfo.FileDescription -ne "$BootstrapProductName automation desktop application") {
    throw "$ProductName.exe has incomplete Windows application metadata"
}
Add-Type -AssemblyName System.Drawing
$icon = [System.Drawing.Icon]::ExtractAssociatedIcon($appLauncher)
if ($null -eq $icon -or $icon.Width -lt 16 -or $icon.Height -lt 16) {
    throw "Frostguard.exe has no usable Windows application icon"
}
$icon.Dispose()

$smokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("frostguard-native-smoke-" + [guid]::NewGuid().ToString("N"))
$appWorkspace = Join-Path $smokeRoot "app-workspace"
$watcherWorkspace = Join-Path $smokeRoot "watcher-workspace"
New-Item -ItemType Directory -Path $appWorkspace, $watcherWorkspace | Out-Null
$originalPath = $env:PATH
$hadJavaHome = Test-Path Env:JAVA_HOME
$originalJavaHome = $env:JAVA_HOME

try {
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $env:FROSTGUARD_WORKSPACE = $appWorkspace
    $app = Start-Process -FilePath $appLauncher -ArgumentList "--frostguard-native-smoke-test" -Wait -PassThru
    if ($app.ExitCode -ne 0) {
        throw "Native Frostguard launcher exited with $($app.ExitCode)"
    }
    $marker = Join-Path $appWorkspace "frostguard-workspace.json"
    if (-not (Test-Path -LiteralPath $marker)) {
        throw "Native launcher did not create its isolated workspace marker"
    }
    $workspaceMetadata = Get-Content -Raw -LiteralPath $marker | ConvertFrom-Json
    if ($workspaceMetadata.channel -ne $Channel) {
        throw "Native launcher selected '$($workspaceMetadata.channel)' instead of $Channel"
    }
    $appEvidencePath = Join-Path $appWorkspace "cache/native-app-smoke.properties"
    if (-not (Test-Path -LiteralPath $appEvidencePath)) {
        throw "Native app launcher did not write smoke-test evidence"
    }
    $appEvidence = @{}
    Get-Content -LiteralPath $appEvidencePath | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') {
            $appEvidence[$matches[1]] = $matches[2]
        }
    }
    $expectedApplicationId = if ($Channel -eq "nightly") {
        "dev.frostguard.desktop.nightly"
    } else {
        "dev.frostguard.desktop"
    }
    if ($appEvidence["channel"] -ne $Channel -or
            $appEvidence["applicationId"] -ne $expectedApplicationId -or
            [System.IO.Path]::GetFullPath($appEvidence["workspace"]) -ne $appWorkspace -or
            [System.IO.Path]::GetFullPath($appEvidence["applicationDir"]) -ne (Join-Path $image "app") -or
            [System.IO.Path]::GetFullPath($appEvidence["appLauncher"]) -ne $appLauncher) {
        throw "Native app launcher did not receive its packaged product identity"
    }

    $env:FROSTGUARD_WORKSPACE = $watcherWorkspace
    $watcher = Start-Process -FilePath $watcherLauncher `
        -ArgumentList "--frostguard-native-smoke-test" -Wait -PassThru
    if ($watcher.ExitCode -ne 0) {
        throw "Native Frostguard watcher launcher exited with $($watcher.ExitCode)"
    }
    $watcherConfig = Join-Path $watcherWorkspace "watcher/telegram-watcher.properties"
    if (-not (Test-Path -LiteralPath $watcherConfig)) {
        throw "Native watcher launcher did not reach workspace configuration"
    }
    $watcherMarker = Join-Path $watcherWorkspace "frostguard-workspace.json"
    if (-not (Test-Path -LiteralPath $watcherMarker)) {
        throw "Native watcher launcher did not create its workspace marker"
    }
    $watcherMetadata = Get-Content -Raw -LiteralPath $watcherMarker | ConvertFrom-Json
    if ($watcherMetadata.channel -ne $Channel) {
        throw "Native watcher launcher selected '$($watcherMetadata.channel)' instead of $Channel"
    }
    $watcherEvidencePath = Join-Path $watcherWorkspace "cache/native-watcher-smoke.properties"
    if (-not (Test-Path -LiteralPath $watcherEvidencePath)) {
        throw "Native watcher launcher did not write smoke-test evidence"
    }
    $watcherEvidence = @{}
    Get-Content -LiteralPath $watcherEvidencePath | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') {
            $watcherEvidence[$matches[1]] = $matches[2]
        }
    }
    if ($watcherEvidence["channel"] -ne $Channel -or
            $watcherEvidence["applicationId"] -ne $expectedApplicationId -or
            [System.IO.Path]::GetFullPath($watcherEvidence["workspace"]) -ne $watcherWorkspace -or
            [System.IO.Path]::GetFullPath($watcherEvidence["applicationDir"]) -ne (Join-Path $image "app") -or
            [System.IO.Path]::GetFullPath($watcherEvidence["appLauncher"]) -ne $appLauncher -or
            [System.IO.Path]::GetFullPath($watcherEvidence["watcherLauncher"]) -ne $watcherLauncher) {
        throw "Native watcher launcher did not receive its packaged runtime contract"
    }
    Write-Host "Native app-image smoke test passed (app and watcher exit 0 without system Java)."
}
finally {
    Remove-Item Env:FROSTGUARD_WORKSPACE -ErrorAction SilentlyContinue
    $env:PATH = $originalPath
    if ($hadJavaHome) {
        $env:JAVA_HOME = $originalJavaHome
    } else {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    }
    $resolvedSmokeRoot = [System.IO.Path]::GetFullPath($smokeRoot)
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedSmokeRoot.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedSmokeRoot).StartsWith("frostguard-native-smoke-")) {
        Remove-Item -LiteralPath $resolvedSmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
