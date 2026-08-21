# Creates the "Start MuMu" and "Stop MuMu" desktop buttons.
# Replaces the older single "Fix MuMu" button -- Start MuMu now does the repair.

$desktop = [Environment]::GetFolderPath('Desktop')

function New-Button($name, $script, $iconIndex, $description) {
    $lnkPath = Join-Path $desktop "$name.lnk"
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut($lnkPath)
    $lnk.TargetPath       = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $lnk.Arguments        = "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
    $lnk.WorkingDirectory = 'C:\Bearguard\tools'
    $lnk.IconLocation     = "$env:SystemRoot\System32\shell32.dll,$iconIndex"
    $lnk.Description      = $description
    $lnk.Save()

    # Flag the .lnk to run as administrator (header byte 0x15, bit 0x20) so the
    # UAC prompt appears immediately rather than after the window opens.
    $bytes = [IO.File]::ReadAllBytes($lnkPath)
    $bytes[0x15] = $bytes[0x15] -bor 0x20
    [IO.File]::WriteAllBytes($lnkPath, $bytes)

    "Created: $lnkPath"
}

New-Button 'Start MuMu' 'C:\Bearguard\tools\start-mumu.ps1' 137 `
    'Start the MuMu emulator (repairs the VBox driver first if it is broken)'

New-Button 'Stop MuMu'  'C:\Bearguard\tools\stop-mumu.ps1'  132 `
    'Shut MuMu down and release the hypervisor, freeing CPU/RAM/GPU for games'

# retire the old combined button -- Start MuMu supersedes it
$old = Join-Path $desktop 'Fix MuMu.lnk'
if (Test-Path $old) {
    Remove-Item $old -Force
    "Removed superseded: $old"
}
