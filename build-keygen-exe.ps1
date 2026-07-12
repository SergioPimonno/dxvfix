$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $root "dxvfix-license-admin.jar"
$destDir = Join-Path $root "dist"

if (-not (Test-Path $jarPath)) {
    throw "dxvfix-license-admin.jar not found. Run build-keygen.ps1 first."
}

$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if ($jpackage) {
    $jpackageExe = $jpackage.Source
} else {
    $candidate = Get-ChildItem "C:\Program Files\Java\jdk-*\bin\jpackage.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $candidate) {
        throw "jpackage.exe not found. It ships with JDK 14+; install a full JDK (not just a JRE)."
    }
    $jpackageExe = $candidate.FullName
}

$appDir = Join-Path $destDir "DxvLicenseAdmin"
if (Test-Path $appDir) { Remove-Item -Recurse -Force $appDir }
New-Item -ItemType Directory -Path $destDir -Force | Out-Null

& $jpackageExe `
    --type app-image `
    --name "DxvLicenseAdmin" `
    --input (Split-Path -Parent $jarPath) `
    --main-jar (Split-Path -Leaf $jarPath) `
    --main-class dxvfix.license.tools.LicenseAdminGui `
    --dest $destDir `
    --java-options "-Dfile.encoding=UTF-8"

if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

Write-Host "Built native app at: $appDir"
Write-Host "Run: $appDir\DxvLicenseAdmin.exe"
