$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root "src\main\java\dxvfix\license"
$resources = Join-Path $root "src\main\resources"
$out = Join-Path $root "out-keygen"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) license source files..."
& javac -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

if (Test-Path $resources) {
    Copy-Item -Path (Join-Path $resources "*") -Destination $out -Recurse -Force
}

$jarCmd = Get-Command jar -ErrorAction SilentlyContinue
if ($jarCmd) {
    $jarExe = $jarCmd.Source
} else {
    $candidate = Get-ChildItem "C:\Program Files\Java\jdk-*\bin\jar.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $candidate) {
        throw "jar.exe not found on PATH or under C:\Program Files\Java\jdk-*\bin. Install a JDK (not just a JRE)."
    }
    $jarExe = $candidate.FullName
}

$manifest = Join-Path $out "MANIFEST.MF"
"Main-Class: dxvfix.license.tools.LicenseAdminGui" | Out-File -FilePath $manifest -Encoding ascii

$jarPath = Join-Path $root "dxvfix-license-admin.jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }
Push-Location $out
try {
    & $jarExe cfm $jarPath "MANIFEST.MF" .
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host "Built $jarPath"
Write-Host "Run with: java -jar dxvfix-license-admin.jar"
Write-Host "(To also build a native .exe, run build-keygen-exe.ps1 afterward.)"
