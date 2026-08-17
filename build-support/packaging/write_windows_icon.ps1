param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$png = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputPath))
$signature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
if ($png.Length -lt 24) {
    throw "The Windows icon source must be a PNG file"
}
for ($index = 0; $index -lt $signature.Length; $index++) {
    if ($png[$index] -ne $signature[$index]) {
        throw "The Windows icon source must be a PNG file"
    }
}

# PNG stores the IHDR dimensions in big-endian order. ICO uses a zero byte for
# a 256-pixel dimension and can embed the original PNG payload without lossy
# conversion on supported Windows versions.
$width = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($png, 16))
$height = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($png, 20))
if ($width -lt 1 -or $width -gt 256 -or $height -lt 1 -or $height -gt 256) {
    throw "The PNG dimensions must be between 1 and 256 pixels"
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$stream = [System.IO.File]::Open($OutputPath, [System.IO.FileMode]::Create)
$writer = [System.IO.BinaryWriter]::new($stream)
try {
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]1)
    $writer.Write([byte]($(if ($width -eq 256) { 0 } else { $width })))
    $writer.Write([byte]($(if ($height -eq 256) { 0 } else { $height })))
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$png.Length)
    $writer.Write([uint32]22)
    $writer.Write($png)
}
finally {
    $writer.Dispose()
}
