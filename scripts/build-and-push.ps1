<#
.SYNOPSIS
    Builds HushGram17 and pushes the .mpp bundle to your phone via ADB.

.DESCRIPTION
    1. Runs the Gradle build to produce the .mpp patch bundle.
    2. Finds the .mpp in patches/build/release/.
    3. Pushes it to /sdcard/Download/ on the connected Android device.
    4. You then open Morphe Manager → Local sources → pick the file.

.PARAMETER SkipBuild
    If set, skips the Gradle build and pushes the existing .mpp.

.PARAMETER Destination
    Phone path to push to. Defaults to /sdcard/Download/.

.EXAMPLE
    .\scripts\build-and-push.ps1
    .\scripts\build-and-push.ps1 -SkipBuild
#>
param(
    [switch]$SkipBuild,
    [string]$Destination = "/sdcard/Download/"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path "$root\gradlew.bat")) { $root = Split-Path -Parent $PSScriptRoot }
if (-not (Test-Path "$root\gradlew.bat")) { $root = $PSScriptRoot }
# Try to find root by walking up
$check = (Get-Item $PSScriptRoot).Parent.FullName
while ($check -and -not (Test-Path "$check\gradlew.bat")) {
    $check = (Get-Item $check).Parent.FullName
}
if ($check -and (Test-Path "$check\gradlew.bat")) { $root = $check }

Set-Location $root
$adb = "C:\Users\arham\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# --- Step 1: Build ---
if (-not $SkipBuild) {
    Write-Host "`n=== Building HushGram17 ===" -ForegroundColor Cyan
    $env:GITHUB_ACTOR = "dummy"
    $env:GITHUB_TOKEN = "dummy"
    & .\gradlew.bat :patches:buildAndroid --no-daemon 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n BUILD FAILED" -ForegroundColor Red
        exit 1
    }
    Write-Host "`n BUILD SUCCESSFUL" -ForegroundColor Green
}

# --- Step 2: Find the .mpp ---
$mpp = Get-ChildItem "patches\build\release\*.mpp" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $mpp) {
    Write-Host " No .mpp found in patches\build\release\. Run without -SkipBuild first." -ForegroundColor Red
    exit 1
}
Write-Host "`nBundle: $($mpp.Name) ($([math]::Round($mpp.Length / 1MB, 1)) MB)" -ForegroundColor Yellow

# --- Step 3: Push to phone ---
Write-Host "`n=== Pushing to phone ===" -ForegroundColor Cyan

# Check ADB
if (-not (Test-Path $adb)) {
    Write-Host " ADB not found at $adb" -ForegroundColor Red
    Write-Host "   Set the `$adb variable in this script to your adb.exe path." -ForegroundColor Gray
    exit 1
}

$devices = & $adb devices 2>&1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host " No device connected. Connect your phone with USB debugging on." -ForegroundColor Red
    Write-Host "   Tip: You can also copy the .mpp manually from:" -ForegroundColor Gray
    Write-Host "   $($mpp.FullName)" -ForegroundColor Gray
    exit 1
}

$phonePath = "$Destination$($mpp.Name)"
& $adb push $mpp.FullName $phonePath 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) {
    Write-Host " ADB push failed" -ForegroundColor Red
    exit 1
}

Write-Host "`n Done! Bundle pushed to $phonePath" -ForegroundColor Green
Write-Host "   Open Morphe Manager -> add as Local source -> pick from Downloads" -ForegroundColor Gray
Write-Host ""
