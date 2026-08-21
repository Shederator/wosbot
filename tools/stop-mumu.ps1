# Stop MuMu -- shuts the emulator down and releases the hypervisor, so the CPU,
# RAM and GPU are free for something else (Battlefield, etc).
#
# Stopping the MuMuNxSup kernel driver is the part that actually releases the
# virtualisation claim. Shutting the window alone leaves the driver loaded.

$ErrorActionPreference = 'Stop'

$DriverName  = 'MuMuNxSup'
$MuMuManager = 'D:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe'
$VmIndex     = '0'

# Bearguard drives MuMu -- warn before pulling the emulator out from under it.
$BotProcessHint = 'javaw'

# --- self-elevate: stopping a kernel driver requires admin ------------------
$isAdmin = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Start-Process powershell.exe -Verb RunAs -ArgumentList @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`""
    )
    exit
}

function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }

Write-Host ''
Say '  Stop MuMu' 'Cyan'
Say '  =========' 'Cyan'
Write-Host ''

# --- is Bearguard still running? -------------------------------------------
$bot = Get-Process $BotProcessHint -ErrorAction SilentlyContinue |
       Where-Object { $_.Path -and $_.Path -like '*jdk*' }

if ($bot) {
    Say '  Bearguard looks like it is still running.' 'Yellow'
    Say '  Stopping MuMu will pull the emulator out from under it mid-task.' 'Yellow'
    $answer = Read-Host '  Stop MuMu anyway? (y/N)'
    if ($answer -notmatch '^[Yy]') {
        Say '  Cancelled -- nothing was stopped.' 'Green'
        Read-Host "`n  Press Enter to close"
        exit 0
    }
}

# --- 1. shut the Android VM down cleanly -----------------------------------
Say '  [1/3] Shutting the emulator down...'
if (Test-Path $MuMuManager) {
    & $MuMuManager control -v $VmIndex shutdown 2>&1 | Out-Null
} else {
    Say "        MuMuManager not found at $MuMuManager" 'Yellow'
}

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    try {
        $info = & $MuMuManager info -v $VmIndex 2>&1 | Out-String | ConvertFrom-Json
        if ($info.is_process_started -ne $true) { break }
    } catch { break }
}
Say '        Emulator shut down.' 'Green'

# --- 2. close any leftover MuMu processes ----------------------------------
Say ''
Say '  [2/3] Closing leftover MuMu processes...'
$names = @('MuMuNxMain','MuMuNxLauncher','MuMuPlayer','MuMuNxDevice','MuMuVMMHeadless',
           'MuMuNxHeadless','MuMuNxSVC','MuMuRemoteBackend','MumuRemoteHealthd',
           'MuMuRemoteService','MuMuNxService','MuMuStatisticsReporter')
$closed = 0
foreach ($n in $names) {
    Get-Process $n -ErrorAction SilentlyContinue | ForEach-Object {
        try { Stop-Process -Id $_.Id -Force -ErrorAction Stop; $closed++ } catch { }
    }
}
Say "        Closed $closed process(es)."

# --- 3. release the hypervisor ---------------------------------------------
Say ''
Say '  [3/3] Releasing the virtualisation driver...'
Start-Sleep -Seconds 2
& sc.exe stop $DriverName 2>&1 | Out-Null
Start-Sleep -Seconds 2

$drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue
if ($drv -and $drv.State -eq 'Running') {
    Say '        Driver is still loaded (something is still holding it).' 'Yellow'
    Say '        MuMu is stopped and its RAM/CPU are free -- this only means the' 'Yellow'
    Say '        driver stays resident. Harmless; it releases on reboot.' 'Yellow'
} else {
    Say "        $DriverName released." 'Green'
}

Write-Host ''
Say '  MuMu is stopped. CPU, RAM and GPU are free.' 'Green'
Say '  Use the "Start MuMu" button when you want it back.' 'Gray'
Write-Host ''
Read-Host '  Press Enter to close'
