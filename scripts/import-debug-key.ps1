<#
.SYNOPSIS
    Decrypts the shared debug key from the "Export debug key" workflow and
    puts it where scripts/dev-install.ps1 finds it.

.DESCRIPTION
    The workflow encrypts the key with the public key in
    .github/dial-key-transfer.pub.pem. This script decrypts it with the
    matching private key, which stays on this PC at
    %USERPROFILE%\.android\dial-key-transfer.pem.

    The script does not show the key or the password.

.PARAMETER Zip
    The artifact ZIP that you downloaded from the workflow run. The default is
    the newest dial-debug-key-encrypted*.zip in your Downloads folder.

.PARAMETER NewTransferKey
    Make a new transfer key pair (for a new PC). The script writes the private
    key to %USERPROFILE%\.android and the public key to
    .github/dial-key-transfer.pub.pem. Commit the public key, then run the
    workflow.
#>
[CmdletBinding()]
param(
    [string]$Zip,
    [switch]$NewTransferKey
)

$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$AndroidDir = Join-Path $env:USERPROFILE '.android'
$PrivateKey = Join-Path $AndroidDir 'dial-key-transfer.pem'
$PublicKey = Join-Path $RepoRoot '.github\dial-key-transfer.pub.pem'
$Target = Join-Path $AndroidDir 'dial-shared.keystore'

function Stop-WithError([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

# Git for Windows has OpenSSL 3.
$openssl = (Get-Command openssl -ErrorAction SilentlyContinue).Source
if (-not $openssl) { $openssl = 'C:\Program Files\Git\mingw64\bin\openssl.exe' }
if (-not (Test-Path $openssl)) { Stop-WithError "OpenSSL not found. Install Git for Windows." }

if ($NewTransferKey) {
    New-Item -ItemType Directory -Force $AndroidDir | Out-Null
    & $openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out $PrivateKey 2>$null
    & $openssl pkey -in $PrivateKey -pubout -out $PublicKey
    if ($LASTEXITCODE -ne 0) { Stop-WithError "OpenSSL could not make the key pair." }
    Write-Host "New transfer key pair made. Commit $PublicKey, push it, then run the workflow." -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $PrivateKey)) {
    Stop-WithError "The private transfer key is not at '$PrivateKey'. Use -NewTransferKey first."
}

if (-not $Zip) {
    $latest = Get-ChildItem (Join-Path $env:USERPROFILE 'Downloads') -Filter 'dial-debug-key-encrypted*.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { Stop-WithError "No dial-debug-key-encrypted*.zip in Downloads. Use -Zip." }
    $Zip = $latest.FullName
}
Write-Host "Artifact: $Zip"

$work = Join-Path ([IO.Path]::GetTempPath()) ("dial-key-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
try {
    Expand-Archive $Zip -DestinationPath $work
    foreach ($f in 'keystore.enc', 'pass.enc', 'certificate-sha256.txt') {
        if (-not (Test-Path (Join-Path $work $f))) { Stop-WithError "The ZIP does not contain $f." }
    }

    & $openssl pkeyutl -decrypt -inkey $PrivateKey -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 `
        -in (Join-Path $work 'pass.enc') -out (Join-Path $work 'pass.txt')
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "Decryption failed. The workflow used a public key that does not match '$PrivateKey'."
    }
    $plain = Join-Path $work 'dial-shared.keystore'
    & $openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in (Join-Path $work 'keystore.enc') `
        -out $plain -pass ("file:" + (Join-Path $work 'pass.txt'))
    if ($LASTEXITCODE -ne 0) { Stop-WithError "Decryption of the keystore failed." }

    # Compare the certificate with the value that the workflow wrote.
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (-not (Test-Path $keytool)) { $keytool = 'keytool' }
    $line = & $keytool -list -v -keystore $plain -storepass android -alias androiddebugkey 2>$null |
        Select-String 'SHA256:' | Select-Object -First 1
    $actual = ($line.Line -replace '.*SHA256:\s*', '').Trim()
    $expected = (Get-Content (Join-Path $work 'certificate-sha256.txt') -Raw).Trim()
    if (-not $actual -or $actual -ne $expected) { Stop-WithError "The certificate does not agree with the workflow output." }

    New-Item -ItemType Directory -Force $AndroidDir | Out-Null
    Copy-Item $plain $Target -Force
    Write-Host "Shared key installed at $Target" -ForegroundColor Green
    Write-Host "Certificate SHA-256: $actual"
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
