$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root "src\main\java"
$resources = Join-Path $root "src\main\resources"
$out = Join-Path $root "out"
$lib = Join-Path $root "lib"

$flatlafJar = Get-ChildItem -Path $lib -Filter "flatlaf-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $flatlafJar) {
    throw "FlatLaf jar not found under lib\. Expected lib\flatlaf-<version>.jar (theming depends on it)."
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files..."
& javac -encoding UTF-8 --release 17 -cp $flatlafJar.FullName -d $out @sources
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
# Class-Path is relative to dxvfix.jar's own location, so lib\ must ship alongside the jar
# (theming via FlatLaf is loaded from there at runtime instead of being merged into the jar,
# to avoid dealing with FlatLaf's multi-release-jar layout during packaging).
"Main-Class: dxvfix.Main`r`nClass-Path: lib/$($flatlafJar.Name)" | Out-File -FilePath $manifest -Encoding ascii

$jarPath = Join-Path $root "dxvfix.jar"
Push-Location $out
try {
    & $jarExe cfm $jarPath "MANIFEST.MF" .
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host "Built $jarPath"
Write-Host "Run with: java -jar dxvfix.jar (lib\$($flatlafJar.Name) must stay alongside it)"
