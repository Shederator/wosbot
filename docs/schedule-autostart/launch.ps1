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

function Get-ProcessAncestors {
    param(
        [Parameter(Mandatory)]
        [object]$Process,
        [Parameter(Mandatory)]
        [hashtable]$ProcessesById
    )

    $ancestors = [System.Collections.Generic.List[int]]::new()
    $parentId = [int]$Process.ParentProcessId
    while ($parentId -ne 0 -and $ProcessesById.ContainsKey($parentId)) {
        $ancestors.Add($parentId)
        $parentId = [int]$ProcessesById[$parentId].ParentProcessId
    }

    return $ancestors
}

function Get-ExistingFrostguardProcesses {
    param(
        [Parameter(Mandatory)]
        [object]$Launch
    )

    $allProcesses = @(Get-CimInstance -ClassName Win32_Process)
    $processesById = @{}
    foreach ($process in $allProcesses) {
        $processesById[[int]$process.ProcessId] = $process
    }

    if ($Launch.Mode -eq "Installed") {
        $matchingProcesses = @($allProcesses | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_.ExecutablePath) -and
                [string]::Equals($_.ExecutablePath, $Launch.FilePath, [System.StringComparison]::OrdinalIgnoreCase)
            })
    }
    else {
        $repositoryPattern = [Regex]::Escape($Launch.RepositoryPath)
        $workspacePattern = [Regex]::Escape((Join-Path $Launch.RepositoryPath ".frostguard-dev"))
        $matchingProcesses = @($allProcesses | Where-Object {
                $commandLine = $_.CommandLine -replace "/", "\"
                $isMavenLaunch = $_.Name -ieq "cmd.exe" -and
                    $commandLine -match "(?i)mvnw\.cmd" -and
                    $commandLine -match "(?i)$repositoryPattern"
                $isFrostguardJvm = ($_.Name -ieq "java.exe" -or $_.Name -ieq "javaw.exe") -and
                    $commandLine -match "(?i)dev\.frostguard\.app\.bootstrap\.Main" -and
                    $commandLine -match "(?i)$workspacePattern"
                $isWatcher = ($_.Name -ieq "java.exe" -or $_.Name -ieq "javaw.exe") -and
                    $commandLine -match "(?i)frostguard-watcher" -and
                    $commandLine -match "(?i)$repositoryPattern"
                $isMavenLaunch -or $isFrostguardJvm -or $isWatcher
            })
    }

    $matchingIds = @{}
    foreach ($process in $matchingProcesses) {
        $matchingIds[[int]$process.ProcessId] = $true
    }

    return @($matchingProcesses | Where-Object {
            $ancestors = Get-ProcessAncestors -Process $_ -ProcessesById $processesById
            -not ($ancestors | Where-Object { $matchingIds.ContainsKey($_) })
        })
}

function Stop-ExistingFrostguardProcesses {
    param(
        [Parameter(Mandatory)]
        [object]$Launch
    )

    $existingProcesses = @(Get-ExistingFrostguardProcesses -Launch $Launch)
    if ($existingProcesses.Count -eq 0) {
        Write-Log "No existing Frostguard process found for $($Launch.Mode) mode"
        return
    }

    foreach ($existingProcess in $existingProcesses) {
        Write-Log "Found existing Frostguard process PID=$($existingProcess.ProcessId), Name='$($existingProcess.Name)'"
        Stop-ProcessTreeByPid -ProcessId $existingProcess.ProcessId
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $remainingProcesses = @(Get-ExistingFrostguardProcesses -Launch $Launch)
        if ($remainingProcesses.Count -eq 0) {
            Write-Log "Existing Frostguard process cleanup completed"
            return
        }

        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    $remainingPids = $remainingProcesses.ProcessId -join ", "
    throw "Existing Frostguard process cleanup did not complete within 30 seconds; remaining PIDs: $remainingPids"
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
            Mode = "Installed"
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
        Mode = "Development"
        FilePath = $mavenWrapper
        Arguments = [string[]]@("-Djavafx.args=--autostart", "javafx:run")
        WorkingDirectory = $resolvedRepositoryPath
        RepositoryPath = $resolvedRepositoryPath
        Display = "`"$mavenWrapper`" `"-Djavafx.args=--autostart`" javafx:run"
    }
}

Write-Log "============================================================"
Write-Log "Script started"
Write-Log "Parameters: Mode='$Mode', VmProcessName='$VmProcessName', TimeoutSec=$TimeoutSec"

$proc = $null

try {
    $launch = Resolve-FrostguardLaunch
    Stop-ExistingFrostguardProcesses -Launch $launch
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
