<#
.SYNOPSIS
    Builds the Dial dev APK on this PC and installs it on a phone with adb.

.DESCRIPTION
    This script does these steps:
      1. It finds the JDK, the Android SDK and adb.
      2. It finds the phone (USB, or wireless debugging).
      3. It builds the dev APK for arm64-v8a only.
      4. It signs the APK with the shared key, as CI does.
      5. It installs the APK over the app that is on the phone.
      6. Optionally, it starts the app and shows its log.

    The script does not show, copy or commit the keystore. It only gives the
    path of the keystore to Gradle.

    See docs/PHONE_DEV_BUILD.md for the one-time setup.

.PARAMETER Benchmark
    Build the benchmark variant (R8, not debuggable), as CI does. The build is
    slower, but the app runs at its real speed. Without this switch, the script
    builds the debug variant, which builds faster.

.PARAMETER Device
    The phone to use: an adb serial such as "192.168.1.20:41234", or the
    hardware serial of the phone (adb shell getprop ro.serialno). The
    hardware serial does not change when the Wi-Fi port changes. If you do
    not give it, the script uses DIAL_DEVICE, then ANDROID_SERIAL, then the
    only phone that adb finds.

.PARAMETER Connect
    An "IP:port" from the Wireless debugging screen on the phone. The script
    connects to it before it builds.

.PARAMETER Keystore
    The path of the shared signing keystore. The default is DIAL_KEYSTORE, or
    %USERPROFILE%\.android\dial-shared.keystore.

.PARAMETER NoBuild
    Do not build. Install the last APK that Gradle made.

.PARAMETER Logcat
    After the install, start the app and show the log of its process.

.EXAMPLE
    .\scripts\dev-install.ps1 -Logcat

.EXAMPLE
    .\scripts\dev-install.ps1 -Benchmark -Connect 192.168.1.20:41234
#>
[CmdletBinding()]
param(
    [switch]$Benchmark,
    [string]$Device,
    [string]$Connect,
    [string]$Keystore,
    [switch]$NoBuild,
    [switch]$Logcat
)

# Not 'Stop': Windows PowerShell 5.1 then stops on each line that adb or
# Gradle writes to stderr. The script checks each result itself.
$ErrorActionPreference = 'Continue'
$Package = 'com.steeben.dial'
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Stop-WithError([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

# --- 1. JDK -----------------------------------------------------------------
# The Android build needs JDK 17 or later.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk-(17|2\d)' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { Stop-WithError "No JDK found. Install a JDK 17 or later and set JAVA_HOME." }
    $env:JAVA_HOME = $jdk.FullName
}

# --- 2. Android SDK ---------------------------------------------------------
# settings.gradle.kts adds the :app module only when it finds an SDK.
$sdk = $null
foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
    if ($candidate -and (Test-Path $candidate)) { $sdk = $candidate; break }
}
if (-not $sdk) {
    Stop-WithError "No Android SDK found. Do the SDK step in docs/PHONE_DEV_BUILD.md, or set ANDROID_HOME."
}
$env:ANDROID_HOME = $sdk

# --- 3. adb -----------------------------------------------------------------
# Use the adb of the SDK if it is there. If not, use the adb on PATH.
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $onPath) { Stop-WithError "adb not found. Install the SDK platform-tools." }
    $adb = $onPath.Source
}

function Get-ReadyDevices {
    & $adb devices | Select-Object -Skip 1 | ForEach-Object {
        $parts = $_ -split "`t"
        if ($parts.Count -eq 2 -and $parts[1] -eq 'device') { $parts[0] }
    }
}

# --- 4. Phone ---------------------------------------------------------------
Write-Step "Find the phone"
if ($Connect) {
    & $adb connect $Connect | Write-Host
    if (-not $Device) { $Device = $Connect }
}
if (-not $Device -and $env:DIAL_DEVICE) { $Device = $env:DIAL_DEVICE }
if (-not $Device -and $env:ANDROID_SERIAL) { $Device = $env:ANDROID_SERIAL }

$ready = @(Get-ReadyDevices)
if (-not $Device -and $ready.Count -eq 0) {
    # A paired phone with Wireless debugging on announces itself on the
    # network. Its port changes each time, so look for it.
    # Connect only if there is one such phone. With more, use -Connect.
    $found = @(& $adb mdns services 2>$null | Where-Object { $_ -match '_adb-tls-connect' } |
        ForEach-Object { if ($_ -match '(\d{1,3}(\.\d{1,3}){3}:\d+)') { $Matches[1] } } |
        Select-Object -Unique)
    if ($found.Count -gt 1) {
        Stop-WithError ("More than one phone has Wireless debugging on: $($found -join ', '). " +
            "Use -Connect IP:port to select one.")
    }
    if ($found.Count -eq 1) {
        $found = $found[0]
        Write-Host "Connect to $found (Wireless debugging)"
        & $adb connect $found | Write-Host
        $ready = @(Get-ReadyDevices)
    }
}
if (-not $Device -and $ready.Count -gt 1) {
    # One phone on USB and on Wi-Fi shows two times. Count each phone once.
    $phones = @($ready | ForEach-Object { (& $adb -s $_ shell getprop ro.serialno | Out-String).Trim() } |
        Select-Object -Unique)
    if ($phones.Count -eq 1) { $ready = @($ready[0]) }
}
if (-not $Device) {
    if ($ready.Count -eq 1) {
        $Device = $ready[0]
    } elseif ($ready.Count -eq 0) {
        Stop-WithError ("No phone found. Connect a USB cable, or open Wireless debugging on the phone " +
            "and run this script with -Connect IP:port.")
    } else {
        Stop-WithError ("More than one phone found: $($ready -join ', '). Use -Device to select one.")
    }
} elseif ($ready -notcontains $Device) {
    # The Wi-Fi port changes, but the hardware serial does not. Thus -Device
    # can also be the serial of the phone (adb shell getprop ro.serialno).
    $bySerial = @($ready | Where-Object { (& $adb -s $_ shell getprop ro.serialno | Out-String).Trim() -eq $Device })
    if ($bySerial.Count -eq 0) {
        Stop-WithError "The phone '$Device' is not ready. Do 'adb devices' to see its state."
    }
    $Device = $bySerial[0]
}
$abi = (& $adb -s $Device shell getprop ro.product.cpu.abi).Trim()
Write-Host "Phone: $Device ($abi)"
if ($abi -ne 'arm64-v8a') {
    Stop-WithError "This script builds for arm64-v8a only. This phone is $abi."
}

# --- 5. Signing key ---------------------------------------------------------
# Android installs an update only if the key is the same as the key of the
# app on the phone. CI signs with the shared key, so this build must also use
# it. If Gradle cannot find a key, it makes a new one. That APK does not
# install over the CI build. Thus the script stops if the key is missing.
if (-not $Keystore) {
    if ($env:DIAL_KEYSTORE) { $Keystore = $env:DIAL_KEYSTORE }
    else { $Keystore = Join-Path $env:USERPROFILE '.android\dial-shared.keystore' }
}
if (-not (Test-Path $Keystore)) {
    Stop-WithError ("The shared keystore is not at '$Keystore'. Do the keystore step in " +
        "docs/PHONE_DEV_BUILD.md, or use -Keystore.")
}
$Keystore = (Resolve-Path $Keystore).Path

# --- 6. Build ---------------------------------------------------------------
if ($Benchmark) {
    $variant = 'benchmark'
    $gradleArgs = @(':app:assembleDevBenchmark', '-x', 'lintVitalDevBenchmark', '-PnoBenchmarkSuffix')
} else {
    $variant = 'debug'
    $gradleArgs = @(':app:assembleDevDebug')
}
$gradleArgs += @(
    '-Parm64Only',
    "-Pandroid.injected.signing.store.file=$Keystore",
    '-Pandroid.injected.signing.store.password=android',
    '-Pandroid.injected.signing.key.alias=androiddebugkey',
    '-Pandroid.injected.signing.key.password=android'
)

if (-not $NoBuild) {
    Write-Step "Build the dev $variant APK (arm64-v8a)"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $RepoRoot
    try {
        & (Join-Path $RepoRoot 'gradlew.bat') @gradleArgs
        if ($LASTEXITCODE -ne 0) { Stop-WithError "The Gradle build failed. Read the errors above." }
    } finally {
        Pop-Location
    }
    Write-Host ("Build time: {0:mm\:ss}" -f $timer.Elapsed)
}

$apkDir = Join-Path $RepoRoot "app\build\outputs\apk\dev\$variant"
$apk = Get-ChildItem $apkDir -Filter '*arm64-v8a*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Stop-WithError "No APK found in $apkDir." }

# --- 7. Install -------------------------------------------------------------
Write-Step "Install $($apk.Name)"
# -r: replace the app and keep its data. -d: permit a lower versionCode.
$result = & $adb -s $Device install -r -d $apk.FullName 2>&1 | Out-String
Write-Host $result.Trim()
if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Stop-WithError ("The key of this APK is not the key of the app on the phone. The script does not " +
        "uninstall the app, because that deletes its data and sign-in. Make sure that '$Keystore' " +
        "is the shared key.")
}
if ($result -notmatch 'Success') { Stop-WithError "The install failed." }

if (-not $Benchmark) {
    # A debug APK is not compiled ahead of time, and each start verifies all
    # its classes. One "verify" pass now removes this delay (as the Gradle
    # task verifyDevInstall does).
    Write-Step "Pre-verify the debug build on the phone"
    & $adb -s $Device shell cmd package compile -m verify -f $Package | Write-Host
}

# --- 8. Log -----------------------------------------------------------------
if ($Logcat) {
    Write-Step "Start Dial and show its log (Ctrl+C to stop)"
    & $adb -s $Device shell am force-stop $Package
    & $adb -s $Device logcat -c
    & $adb -s $Device shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    $appPid = ''
    for ($i = 0; $i -lt 20 -and -not $appPid; $i++) {
        Start-Sleep -Milliseconds 250
        $appPid = (& $adb -s $Device shell pidof -s $Package 2>$null | Out-String).Trim()
    }
    if (-not $appPid) { Stop-WithError "Dial did not start." }
    & $adb -s $Device logcat --pid=$appPid -v color
} else {
    Write-Host ""
    Write-Host "Done. To see the log: .\scripts\dev-install.ps1 -NoBuild -Logcat" -ForegroundColor Green
}
