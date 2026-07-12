$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root "src\main\java"
$resources = Join-Path $root "src\main\resources"
$out = Join-Path $root "out"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files..."
& javac -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

if (Test-Path $resources) {
    Write-Host "Copying resources..."
    Copy-Item -Path (Join-Path $resources "*") -Destination $out -Recurse -Force
}
if (-not (Test-Path (Join-Path $out "dxvfix\license\public.key"))) {
    Write-Warning "src/main/resources/dxvfix/license/public.key is missing - license verification will fail at runtime. Generate a keypair with GenerateKeyPairMain and put the public key there."
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
"Main-Class: dxvfix.Main" | Out-File -FilePath $manifest -Encoding ascii

$jarPath = Join-Path $root "dxvfix.jar"
Push-Location $out
try {
    & $jarExe cfm $jarPath "MANIFEST.MF" .
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host "Built $jarPath"
Write-Host "Run with: java -jar dxvfix.jar"
