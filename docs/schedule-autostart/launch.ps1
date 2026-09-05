<#
.SYNOPSIS
    Starts Frostguard, waits for completion or timeout, and performs process cleanup.

.DESCRIPTION
    Installed mode starts the native executable created by the Frostguard MSI. Development mode
    starts the repository through mvnw.cmd javafx:run. Both modes pass --autostart, enforce the
    configured timeout, then stop Frostguard and the configured emulator process.
#>

param(
    [ValidateSet("Installed", "Development")]
    [string]$Mode = "Installed",
    [string]$LauncherPath = "",
    [string]$RepositoryPath = "",
    [string]$VmProcessName = "MuMuNxMain",
    [ValidateRange(1, 604800)]
    [int]$TimeoutSec = 2700
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$logFile = Join-Path $PSScriptRoot "launch.log"

function Write-Log {
    param([string]$Message)

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Add-Content -Path $logFile -Value "[$timestamp] $Message"
}

function Stop-ProcessTreeByPid {
    param([int]$ProcessId)

    try {
        Write-Log "Stopping process tree for PID=$ProcessId"
        cmd.exe /c "taskkill /PID $ProcessId /T /F" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "taskkill exited with code $LASTEXITCODE"
        }
        Write-Log "Process tree stopped for PID=$ProcessId"
    }
    catch {
        Write-Log "Failed to stop process tree for PID=$ProcessId : $($_.Exception.Message)"
    }
}

function Resolve-FrostguardLaunch {
    if ($Mode -eq "Installed") {
        $resolvedLauncherPath = $LauncherPath
        if ([string]::IsNullOrWhiteSpace($resolvedLauncherPath)) {
            $localAppData = [Environment]::GetFolderPath("LocalApplicationData")
            if ([string]::IsNullOrWhiteSpace($localAppData)) {
                throw "Windows did not provide a LocalApplicationData directory"
            }
            $resolvedLauncherPath = Join-Path $localAppData "Frostguard\Frostguard.exe"
        }

        if (-not [IO.Path]::IsPathRooted($resolvedLauncherPath)) {
            throw "LauncherPath must be an absolute path: '$resolvedLauncherPath'"
        }
        if (-not (Test-Path -LiteralPath $resolvedLauncherPath -PathType Leaf)) {
            throw "Frostguard launcher not found: '$resolvedLauncherPath'"
        }

        $resolvedLauncherPath = (Get-Item -LiteralPath $resolvedLauncherPath).FullName
        return [PSCustomObject]@{
            FilePath = $resolvedLauncherPath
            Arguments = [string[]]@("--autostart")
            WorkingDirectory = Split-Path -Parent $resolvedLauncherPath
            Display = "`"$resolvedLauncherPath`" --autostart"
        }
    }

    if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
        throw "RepositoryPath is required in Development mode"
    }
    if (-not [IO.Path]::IsPathRooted($RepositoryPath)) {
        throw "RepositoryPath must be an absolute path: '$RepositoryPath'"
    }
    if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) {
        throw "Repository directory not found: '$RepositoryPath'"
    }

    $resolvedRepositoryPath = (Get-Item -LiteralPath $RepositoryPath).FullName
    $mavenWrapper = Join-Path $resolvedRepositoryPath "mvnw.cmd"
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        throw "Maven Wrapper not found: '$mavenWrapper'"
    }

    return [PSCustomObject]@{
        FilePath = $mavenWrapper
        Arguments = [string[]]@("-Djavafx.args=--autostart", "javafx:run")
        WorkingDirectory = $resolvedRepositoryPath
        Display = "`"$mavenWrapper`" `"-Djavafx.args=--autostart`" javafx:run"
    }
}

Write-Log "============================================================"
Write-Log "Script started"
Write-Log "Parameters: Mode='$Mode', VmProcessName='$VmProcessName', TimeoutSec=$TimeoutSec"

$proc = $null

try {
    $launch = Resolve-FrostguardLaunch
    Write-Log "Starting Frostguard: $($launch.Display)"
    $proc = Start-Process -FilePath $launch.FilePath `
        -ArgumentList $launch.Arguments `
        -WorkingDirectory $launch.WorkingDirectory `
        -PassThru

    Write-Log "Frostguard started with PID=$($proc.Id)"

    Write-Log "Waiting up to $TimeoutSec seconds for PID=$($proc.Id)"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    while (-not $proc.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 1
        $proc.Refresh()
    }

    if ($proc.HasExited) {
        Write-Log "Frostguard PID=$($proc.Id) exited before timeout"
    }
    else {
        Write-Log "Timeout reached for PID=$($proc.Id)"
        Stop-ProcessTreeByPid -ProcessId $proc.Id
    }

    $vmProcesses = Get-Process -Name $VmProcessName -ErrorAction SilentlyContinue

    foreach ($vmProcess in $vmProcesses) {
        Write-Log "Found emulator process '$VmProcessName' with PID=$($vmProcess.Id)"
        Stop-ProcessTreeByPid -ProcessId $vmProcess.Id
    }

    Write-Log "Script completed successfully"
}
catch {
    Write-Log "Unhandled error: $($_.Exception.Message)"
    throw
}
finally {
    if ($null -ne $proc) {
        try {
            $proc.Refresh()
            if (-not $proc.HasExited) {
                Stop-ProcessTreeByPid -ProcessId $proc.Id
            }
        }
        catch {
            Write-Log "Failed to inspect Frostguard PID=$($proc.Id): $($_.Exception.Message)"
        }
    }
    Write-Log "Script ended"
}
