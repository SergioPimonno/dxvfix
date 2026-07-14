$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $root "dxvfix.jar"
$flatlafJar = Get-ChildItem (Join-Path $root "lib") -Filter "flatlaf-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
$destDir = Join-Path $root "dist"

if (-not (Test-Path $jarPath)) {
    throw "dxvfix.jar not found. Run build.ps1 first."
}
if (-not $flatlafJar) {
    throw "FlatLaf jar not found under lib\. Run build.ps1 first (it checks for this too)."
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

# jpackage bundles every jar it finds in --input onto the launcher's classpath, not just
# --main-jar -- so both dxvfix.jar and its FlatLaf dependency need to sit in the same staging
# directory. This is what actually fixes the "works here, crashes on another machine" report:
# the plain jar alone needs both a Java runtime AND lib\flatlaf-*.jar sitting next to it, neither
# of which travels with a bare file copy. This produces a self-contained folder with its own
# private Java runtime and a native .exe launcher, so neither is needed on the target machine.
$stageDir = Join-Path $root "out-appimage-stage"
if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
Copy-Item $jarPath $stageDir
Copy-Item $flatlafJar.FullName $stageDir

$appName = "DXVFrameDoctor"
$appDir = Join-Path $destDir $appName
if (Test-Path $appDir) { Remove-Item -Recurse -Force $appDir }
New-Item -ItemType Directory -Path $destDir -Force | Out-Null

$versionFile = Join-Path $root "src\main\java\dxvfix\AppVersion.java"
$versionMatch = Select-String -Path $versionFile -Pattern 'VERSION = "([\d.]+)"' | Select-Object -First 1
$appVersion = if ($versionMatch) { $versionMatch.Matches[0].Groups[1].Value } else { "1.0" }
Write-Host "App version: $appVersion (read from AppVersion.java)"

& $jpackageExe `
    --type app-image `
    --name $appName `
    --input $stageDir `
    --main-jar (Split-Path -Leaf $jarPath) `
    --main-class dxvfix.Main `
    --dest $destDir `
    --app-version $appVersion `
    --vendor "SergioPimonno" `
    --java-options "-Dfile.encoding=UTF-8"

if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

Remove-Item -Recurse -Force $stageDir

# Zipped here (not left as a bare folder) so the self-updater (UpdateManager.java) has a single
# predictable asset name to fetch from a GitHub Release -- it always requests "DXVFrameDoctor.zip"
# for Windows, expecting the zip to contain this folder as its own top-level entry (i.e. zipping
# the folder itself, not just its contents), which is exactly what Compress-Archive does when
# given a directory path.
$zipPath = Join-Path $destDir "$appName.zip"
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
Compress-Archive -Path $appDir -DestinationPath $zipPath

Write-Host "Built self-contained app at: $appDir"
Write-Host "Run: $appDir\$appName.exe"
Write-Host "Zipped for distribution/release upload: $zipPath"
