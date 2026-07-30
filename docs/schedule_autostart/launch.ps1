<#
.SYNOPSIS
    Starts the Frostguard JAR, waits for completion or timeout, and performs process cleanup.

.DESCRIPTION
    The script resolves the latest Frostguard JAR by wildcard pattern, stops leftover Java processes
    launched with that same JAR, starts a fresh Frostguard instance with --autostart, waits for the
    configured timeout, then stops Frostguard and the configured emulator process.
#>

param(
    [string]$JarPattern = "frostguard-*.jar",
    [string]$VmProcessName = "MuMuNxMain",
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
        Write-Log "Process tree stopped for PID=$ProcessId"
    }
    catch {
        Write-Log "Failed to stop process tree for PID=$ProcessId : $($_.Exception.Message)"
    }
}

function Resolve-FrostguardJar {
    param(
        [string]$SearchRoot,
        [string]$Pattern
    )

    $matches = @(Get-ChildItem -Path $SearchRoot -Filter $Pattern -File -Recurse |
        Sort-Object LastWriteTime -Descending)

    if ($matches.Count -eq 0) {
        throw "No jar found matching pattern '$Pattern' under '$SearchRoot'"
    }

    if ($matches.Count -gt 1) {
        Write-Log "Multiple jar files matched '$Pattern'. Selecting: $($matches[0].FullName)"
    }
    else {
        Write-Log "Single jar matched '$Pattern': $($matches[0].FullName)"
    }

    return $matches[0]
}

Write-Log "============================================================"
Write-Log "Script started"
Write-Log "Parameters: JarPattern='$JarPattern', VmProcessName='$VmProcessName', TimeoutSec=$TimeoutSec"

$proc = $null

try {
    $jarFile = Resolve-FrostguardJar -SearchRoot $PSScriptRoot -Pattern $JarPattern
    $jarPath = $jarFile.FullName
    $jarName = $jarFile.Name

    Write-Log "Resolved Frostguard jar: $jarPath"

    $oldJavaProcesses = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -like "*$jarName*" }

    foreach ($oldProcess in $oldJavaProcesses) {
        Write-Log "Found leftover Frostguard Java process PID=$($oldProcess.ProcessId)"
        Stop-ProcessTreeByPid -ProcessId $oldProcess.ProcessId
    }

    Write-Log "Starting Frostguard: java -jar `"$jarPath`" --autostart"
    $proc = Start-Process -FilePath "java.exe" `
        -ArgumentList @("-jar", $jarPath, "--autostart") `
        -PassThru

    Write-Log "Frostguard started with PID=$($proc.Id)"

    try {
        Write-Log "Waiting up to $TimeoutSec seconds for PID=$($proc.Id)"
        $null = Wait-Process -Id $proc.Id -Timeout $TimeoutSec
        Write-Log "Frostguard PID=$($proc.Id) exited before timeout"
    }
    catch {
        Write-Log "Timeout reached or wait interrupted for PID=$($proc.Id)"
    }
    finally {
        if ($null -ne $proc) {
            Stop-ProcessTreeByPid -ProcessId $proc.Id
        }
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
    Write-Log "Script ended"
}
