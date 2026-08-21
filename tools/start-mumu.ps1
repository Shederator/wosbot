# Start MuMu -- brings the emulator up, repairing it first if it is broken.
#
# Also handles the "Startup failed" case: MuMu's Android VM refuses to boot with a
# generic VERR_NEM_VM_CREATE_FAILED. That message is misleading. The VM's own
# VBox.log records the real cause:
#
#     VERR_VM_DRIVER_NOT_INSTALLED -- "VirtualBox kernel driver not installed"
#
# The driver is MuMuNxSup ("VBox Support Driver"), a KERNEL driver with start type
# Manual. It is not a Win32 service, so services.msc never lists it -- there is no
# way to start it from the normal Services console. Hence this script.

$ErrorActionPreference = 'Stop'

$DriverName  = 'MuMuNxSup'
$MuMuManager = 'D:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe'
$SupInstall  = 'C:\Program Files\MuMuNxVbox\LoadedDrivers\SUPInstall.exe'
$Adb         = 'C:\Bearguard\tools\adb\adb.exe'
$AdbSerial   = '127.0.0.1:16384'
$VmIndex     = '0'

# --- self-elevate: starting a kernel driver requires admin -------------------
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
Say '  Start MuMu' 'Cyan'
Say '  ==========' 'Cyan'
Write-Host ''

# --- already up? ------------------------------------------------------------
try {
    $info = & $MuMuManager info -v $VmIndex 2>&1 | Out-String | ConvertFrom-Json
    if ($info.is_android_started -eq $true) {
        Say '  MuMu is already running.' 'Green'
        & $Adb connect $AdbSerial 2>&1 | Out-Null
        Start-Sleep -Seconds 1
        Write-Host ''
        & $Adb devices 2>&1 | Out-String | Write-Host
        Read-Host '  Press Enter to close'
        exit 0
    }
} catch { }

# --- 1. the driver ----------------------------------------------------------
$drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue

if ($drv -and $drv.State -eq 'Running') {
    Say "  [1/4] Driver $DriverName already running."
} else {
    Say "  [1/4] Driver $DriverName is $(if ($drv) { $drv.State } else { 'not registered' }) -- starting it."
    & sc.exe start $DriverName 2>&1 | Out-Null
    $drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue

    if (-not ($drv -and $drv.State -eq 'Running')) {
        Say '        Plain start failed -- reinstalling the driver...' 'Yellow'
        if (Test-Path $SupInstall) {
            & $SupInstall | Out-Host
            Start-Sleep -Seconds 2
            & sc.exe start $DriverName 2>&1 | Out-Null
            $drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue
        }
    }

    if ($drv -and $drv.State -eq 'Running') {
        Say "        OK -- $DriverName is running." 'Green'
    } else {
        Say '        Driver will not load.' 'Red'
        Say '        Usual cause: a Windows update replaced the hypervisor and the machine' 'Red'
        Say '        has not been rebooted since. Reboot, then run this again.' 'Red'
        Read-Host "`n  Press Enter to close"
        exit 1
    }
}

# --- 2. clear any half-dead instance ----------------------------------------
Say ''
Say '  [2/4] Clearing any stuck instance...'
& $MuMuManager control -v $VmIndex shutdown 2>&1 | Out-Null
Start-Sleep -Seconds 3

# --- 3. launch --------------------------------------------------------------
Say ''
Say '  [3/4] Launching the emulator...'
& $MuMuManager control -v $VmIndex launch 2>&1 | Out-Null

# --- 4. wait for Android + adb ---------------------------------------------
Say ''
Say '  [4/4] Waiting for Android to boot (up to 3 min)...'

$booted = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $info = & $MuMuManager info -v $VmIndex 2>&1 | Out-String | ConvertFrom-Json
        if ($info.is_android_started -eq $true) { $booted = $true; break }
        if ($info.launch_err_msg) { Say "        launch error: $($info.launch_err_msg)" 'Yellow' }
    } catch { }
    if ($i % 5 -eq 0) { Write-Host '        ...still booting' }
}

if (-not $booted) {
    Say '        Android did not report started in time -- check the MuMu window.' 'Red'
    Read-Host "`n  Press Enter to close"
    exit 1
}

Say '        Android is up.' 'Green'
Say ''
Say '        Connecting adb...'
& $Adb connect $AdbSerial 2>&1 | Out-Null
Start-Sleep -Seconds 2
$devices = & $Adb devices 2>&1 | Out-String

Write-Host ''
if ($devices -match [regex]::Escape($AdbSerial) -and $devices -notmatch 'offline') {
    Say '  MuMu is UP and adb is connected. Bearguard can run.' 'Green'
} else {
    Say '  Booted, but adb is not attached yet -- give it a few seconds.' 'Yellow'
}
Write-Host ''
Write-Host $devices
Read-Host '  Press Enter to close'
