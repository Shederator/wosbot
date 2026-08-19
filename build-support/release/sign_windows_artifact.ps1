param(
    [Parameter(Mandatory = $true)]
    [string[]] $Path,

    [Parameter(Mandatory = $true)]
    [string] $CertificateThumbprint,

    [Parameter(Mandatory = $true)]
    [string] $Publisher
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" `
    -Recurse -Filter signtool.exe |
    Where-Object FullName -Match '\\x64\\' |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if ($null -eq $signtool) {
    throw "Windows SDK signtool.exe was not found"
}

foreach ($artifactPath in $Path) {
    $artifact = (Resolve-Path -LiteralPath $artifactPath).Path
    & $signtool.FullName sign /sha1 $CertificateThumbprint /fd SHA256 `
        /td SHA256 /tr http://timestamp.digicert.com $artifact
    if ($LASTEXITCODE -ne 0) {
        throw "Authenticode signing failed for $artifact"
    }

    & $signtool.FullName verify /pa /v $artifact
    if ($LASTEXITCODE -ne 0) {
        throw "Authenticode verification failed for $artifact"
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $artifact
    if ($signature.Status -ne "Valid" -or
            $signature.SignerCertificate.Subject -cne $Publisher) {
        throw "Signed artifact does not match the pinned publisher: $artifact"
    }
}
