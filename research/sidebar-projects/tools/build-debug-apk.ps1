<#
  Build the OpenCode Android debug APK from the patched UI checkout.

    pwsh -File research/sidebar-projects/tools/build-debug-apk.ps1
    pwsh -File research/sidebar-projects/tools/build-debug-apk.ps1 -StripOfficeWasm
    pwsh -File research/sidebar-projects/tools/build-debug-apk.ps1 -SkipUiBuild

  Steps: vite build the patched UI (env: android platform, prod channel, no server
  mode) -> stage into webapp/src/main/assets/www/ui (rename _assets -> oa) ->
  :webapp:assembleDebug with the shared debug keystore -> verify package id and
  signing cert -> copy the APK to dist/apk.

  The debug build type carries applicationIdSuffix = ".debug" so the APK can be
  installed next to the official package.
#>
param(
    [switch]$SkipUiBuild,
    [switch]$StripOfficeWasm,
    [string]$ApkName = ""
)

$ErrorActionPreference = "Stop"
$root = "C:\sensorsdata\main\program\opencode-android-shell"
$checkout = Join-Path $root "_scratch_root\desktop-parity\checkout"
$uiSource = Join-Path $checkout "packages\app\dist"
$uiTarget = Join-Path $root "webapp\src\main\assets\www\ui"
$buildTools = "C:\Users\xiufe\AppData\Local\Android\Sdk\build-tools\35.0.0"
$keystoreZip = "C:\sensorsdata\main\packages\android-debug-keystore-cursor.zip"
$keystore = "C:\sensorsdata\main\packages\debug.keystore"

# --- 1. build the patched UI -------------------------------------------------
if (-not $SkipUiBuild) {
    Write-Host "[apk] building UI from $checkout"
    $env:VITE_OPENCODE_SERVER_MODE = "none"
    $env:VITE_OPENCODE_PLATFORM = "android"
    $env:OPENCODE_CHANNEL = "prod"
    Push-Location (Join-Path $checkout "packages\app")
    try {
        bun run build --base / --sourcemap false --logLevel error
        if ($LASTEXITCODE -ne 0) { throw "vite build failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

# --- 2. stage assets ---------------------------------------------------------
Write-Host "[apk] staging UI assets"
if (Test-Path $uiTarget) { Remove-Item $uiTarget -Recurse -Force }
Copy-Item $uiSource $uiTarget -Recurse
Rename-Item (Join-Path $uiTarget "_assets") "oa"

if ($StripOfficeWasm) {
    Get-ChildItem $uiTarget -Recurse -Filter "*.wasm" | ForEach-Object {
        Write-Host ("[apk] strip " + $_.Name + " (" + [math]::Round($_.Length / 1MB, 2) + " MB)")
        Remove-Item $_.FullName -Force
    }
}

$pin = Get-Content (Join-Path $root "frontend\android\upstream.json") -Raw | ConvertFrom-Json
$meta = [ordered]@{
    repository = $pin.repository
    commit     = $pin.commit
    version    = $pin.version
    bun        = $pin.bun
    platform   = "android"
}
$meta | ConvertTo-Json | Set-Content (Join-Path $uiTarget "opencode-build.json") -Encoding utf8

# --- 3. gradle debug build ---------------------------------------------------
Write-Host "[apk] building :webapp:assembleDebug"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($keystoreZip)
try {
    $entry = $zip.Entries | Where-Object { $_.Name -match "README" } | Select-Object -First 1
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $text = $reader.ReadToEnd()
    $reader.Close()
} finally {
    $zip.Dispose()
}
$storepass = [regex]::Match($text, "(?im)^storepass\s*[:=]\s*(\S+)").Groups[1].Value
$keypass = [regex]::Match($text, "(?im)^keypass\s*[:=]\s*(\S+)").Groups[1].Value
if (-not $storepass -or -not $keypass) { throw "keystore README is missing storepass/keypass" }
if (-not (Test-Path $keystore)) { throw "keystore not found: $keystore" }

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:DEBUG_KEYSTORE_PATH = $keystore
$env:DEBUG_KEYSTORE_PASSWORD = $storepass
$env:DEBUG_KEY_ALIAS = "androiddebugkey"
$env:DEBUG_KEY_PASSWORD = $keypass
Push-Location $root
try {
    # A stale output APK with removed assets keeps orphaned zip payload; delete first.
    Remove-Item "webapp\build\outputs\apk\debug\webapp-debug.apk" -Force -ErrorAction SilentlyContinue
    .\gradlew.bat :webapp:assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$apk = Join-Path $root "webapp\build\outputs\apk\debug\webapp-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not produced: $apk" }

# --- 4. verify ---------------------------------------------------------------
$badging = & (Join-Path $buildTools "aapt.exe") dump badging $apk 2>&1 | Select-String -Pattern "^package:"
$cert = & (Join-Path $buildTools "apksigner.bat") verify --print-certs $apk 2>&1 | Select-String -Pattern "SHA-256 digest"
Write-Host "[apk] $badging"
Write-Host "[apk] $cert"
if (-not ($badging -match "com\.xiufeigo\.opencodeshell\.debug")) { throw "package id does not end with .debug" }
if (-not ($cert -match "760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6")) { throw "signing cert mismatch" }

# --- 5. copy deliverable -----------------------------------------------------
if (-not $ApkName) {
    $ApkName = if ($StripOfficeWasm) { "opencode-android-debug-slim.apk" } else { "opencode-android-debug.apk" }
}
$outDir = Join-Path $root "dist\apk"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir $ApkName
Copy-Item $apk $out -Force
$size = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host "[apk] done: $out ($size MB)"
Get-FileHash $out -Algorithm SHA256 | Select-Object Hash
