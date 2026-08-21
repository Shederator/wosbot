# Fix MuMu -- restarts the MuMu VirtualBox support driver, then the emulator.
#
# Why this exists: MuMu's Android VM refuses to boot with the launcher showing a
# generic "Startup failed" / VERR_NEM_VM_CREATE_FAILED. That message is misleading.
# The VM's own VBox.log records the real cause:
#
#     VERR_VM_DRIVER_NOT_INSTALLED -- "VirtualBox kernel driver not installed"
#
# The driver is MuMuNxSup ("VBox Support Driver"), a KERNEL driver, start type
# Manual. It is not a Win32 service, so services.msc never lists it -- there is
# no way to restart it from the normal Services console. Hence this script.

$ErrorActionPreference = 'Stop'

$DriverName   = 'MuMuNxSup'
$MuMuManager  = 'D:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe'
$SupInstall   = 'C:\Program Files\MuMuNxVbox\LoadedDrivers\SUPInstall.exe'
$Adb          = 'C:\Bearguard\tools\adb\adb.exe'
$AdbSerial    = '127.0.0.1:16384'
$VmIndex      = '0'

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
Say '  Fix MuMu' 'Cyan'
Say '  =========' 'Cyan'
Write-Host ''

# --- 1. the driver ----------------------------------------------------------
$drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue

if (-not $drv) {
    Say "  [1/4] Driver $DriverName is not registered at all -- reinstalling it." 'Yellow'
    if (Test-Path $SupInstall) {
        & $SupInstall | Out-Host
    } else {
        Say "        SUPInstall.exe missing at $SupInstall" 'Red'
        Say '        MuMu needs a repair/reinstall -- this script cannot fix that.' 'Red'
        Read-Host "`n  Press Enter to close"
        exit 1
    }
} else {
    Say "  [1/4] Driver $DriverName is currently: $($drv.State)"
    if ($drv.State -eq 'Running') {
        Say '        Stopping it first so we get a clean load...'
        & sc.exe stop $DriverName | Out-Null
        Start-Sleep -Seconds 2
    }
}

Say '        Starting driver...'
$scOut = & sc.exe start $DriverName 2>&1 | Out-String

$drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue
if ($drv -and $drv.State -eq 'Running') {
    Say "        OK -- $DriverName is Running." 'Green'
} else {
    Say '        Plain start failed. Reinstalling the driver...' 'Yellow'
    Write-Host $scOut
    if (Test-Path $SupInstall) {
        & $SupInstall | Out-Host
        Start-Sleep -Seconds 2
        & sc.exe start $DriverName 2>&1 | Out-String | Write-Host
        $drv = Get-CimInstance Win32_SystemDriver -Filter "Name='$DriverName'" -ErrorAction SilentlyContinue
    }
    if ($drv -and $drv.State -eq 'Running') {
        Say "        OK -- $DriverName is Running." 'Green'
    } else {
        Say '        Driver STILL will not load.' 'Red'
        Say '        Most likely cause: a Windows update replaced the hypervisor and' 'Red'
        Say '        the machine has not been rebooted since. Reboot, then run this again.' 'Red'
        Read-Host "`n  Press Enter to close"
        exit 1
    }
}

# --- 2. shut the half-dead emulator down ------------------------------------
Say ''
Say '  [2/4] Shutting down the stuck emulator instance...'
if (Test-Path $MuMuManager) {
    & $MuMuManager control -v $VmIndex shutdown 2>&1 | Out-Null
    Start-Sleep -Seconds 3
} else {
    Say "        MuMuManager not found at $MuMuManager" 'Yellow'
}

# --- 3. start it again ------------------------------------------------------
Say ''
Say '  [3/4] Launching the emulator...'
& $MuMuManager control -v $VmIndex launch 2>&1 | Out-Null

# --- 4. wait for Android + adb ---------------------------------------------
Say ''
Say '  [4/4] Waiting for Android to finish booting (up to 3 min)...'

$booted = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $info = & $MuMuManager info -v $VmIndex 2>&1 | Out-String | ConvertFrom-Json
        if ($info.is_android_started -eq $true) { $booted = $true; break }
        if ($info.launch_err_msg -and $info.launch_err_msg -ne '') {
            Say "        launch error: $($info.launch_err_msg)" 'Yellow'
        }
    } catch { }
    if ($i % 5 -eq 0) { Write-Host '        ...still booting' }
}

if (-not $booted) {
    Say '        Android did not report started in time.' 'Red'
    Say '        Check the MuMu window -- it may be showing an error.' 'Red'
    Read-Host "`n  Press Enter to close"
    exit 1
}

Say '        Android is up.' 'Green'

Say ''
Say '        Connecting adb...'
& $Adb connect $AdbSerial 2>&1 | Out-Null
Start-Sleep -Seconds 2
$devices = & $Adb devices 2>&1 | Out-String

if ($devices -match [regex]::Escape($AdbSerial) -and $devices -notmatch 'offline') {
    Write-Host ''
    Say '  MuMu is FIXED and adb is connected.' 'Green'
    Write-Host ''
    Write-Host $devices
} else {
    Write-Host ''
    Say '  Emulator booted but adb is not attached yet:' 'Yellow'
    Write-Host $devices
    Say '  Give it another few seconds, then re-run if needed.' 'Yellow'
}

Write-Host ''
Read-Host '  Press Enter to close'
