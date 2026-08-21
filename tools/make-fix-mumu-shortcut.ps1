# Creates the "Fix MuMu" desktop button.
$desktop  = [Environment]::GetFolderPath('Desktop')
$lnkPath  = Join-Path $desktop 'Fix MuMu.lnk'
$script   = 'C:\Bearguard\tools\fix-mumu.ps1'

$shell = New-Object -ComObject WScript.Shell
$lnk   = $shell.CreateShortcut($lnkPath)
$lnk.TargetPath       = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$lnk.Arguments        = "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
$lnk.WorkingDirectory = 'C:\Bearguard\tools'
$lnk.IconLocation     = "$env:SystemRoot\System32\shell32.dll,238"
$lnk.Description      = 'Restart the MuMu VBox support driver and relaunch the emulator'
$lnk.Save()

# Flag the .lnk to always run as administrator (byte 21 of the header, bit 0x20).
# The script self-elevates anyway; this makes the UAC prompt appear immediately.
$bytes = [IO.File]::ReadAllBytes($lnkPath)
$bytes[0x15] = $bytes[0x15] -bor 0x20
[IO.File]::WriteAllBytes($lnkPath, $bytes)

"Created: $lnkPath"
