$ErrorActionPreference = 'Continue'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
Set-Location $root
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$log = "$root\research\web\_r2_build2.log"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"

for ($i=0; $i -lt 240; $i++) {
  if (-not (Test-Path "$root\.agent-gradle-lock")) { break }
  Write-Host "waiting for gradle lock (poll $i)"; Start-Sleep -Seconds 5
}
Set-Content -Path "$root\.agent-gradle-lock" -Value "mavis R2 force-debug-rebuild $(Get-Date -Format s)" -Encoding UTF8
Write-Host "=== LOCK TAKEN ==="

"=== R2 FORCE DEBUG REBUILD $(Get-Date -Format s) ===" | Out-File $log -Encoding UTF8
& "$root\gradlew.bat" :webapp:assembleDebug --rerun-tasks --console=plain 2>&1 | Tee-Object -FilePath $log -Append | Select-Object -Last 15
Write-Host "DEBUG_RERUN_EXIT=$LASTEXITCODE"
"=== DEBUG_RERUN_EXIT=$LASTEXITCODE ===" | Out-File $log -Append -Encoding UTF8
Remove-Item "$root\.agent-gradle-lock" -ErrorAction SilentlyContinue
Write-Host "=== LOCK RELEASED ==="

# ---- collect evidence on the fresh outputs ----
$dbg = "$root\webapp\build\outputs\apk\debug\webapp-debug.apk"
$rel = "$root\webapp\build\outputs\apk\release\webapp-release.apk"
$ev = "$root\research\web\_r2_evidence.txt"
"=== EVIDENCE $(Get-Date -Format s) ===" | Out-File $ev -Encoding UTF8

foreach ($pair in @(@("DEBUG",$dbg), @("RELEASE",$rel))) {
  $tag = $pair[0]; $p = $pair[1]
  "`n########## $tag : $p" | Out-File $ev -Append -Encoding UTF8
  if (-not (Test-Path $p)) { "MISSING!" | Out-File $ev -Append -Encoding UTF8; continue }
  $f = Get-Item $p
  "size_bytes = $($f.Length)" | Out-File $ev -Append -Encoding UTF8
  "lastwrite  = $($f.LastWriteTime.ToString('s'))" | Out-File $ev -Append -Encoding UTF8
  "md5        = $((Get-FileHash $p -Algorithm MD5).Hash.ToLower())" | Out-File $ev -Append -Encoding UTF8
  $sha = (Get-FileHash $p -Algorithm SHA256).Hash.ToLower()
  "sha256     = $sha" | Out-File $ev -Append -Encoding UTF8

  "-- aapt2 badging (package: line) --" | Out-File $ev -Append -Encoding UTF8
  $bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
  & "$($bt.FullName)\aapt2.exe" dump badging $p 2>&1 | Select-String '^package:' | Out-File $ev -Append -Encoding UTF8
  & "$($bt.FullName)\aapt2.exe" dump badging $p 2>&1 | Select-String 'application-label:' | Select-Object -First 1 | Out-File $ev -Append -Encoding UTF8

  "-- apksigner verify --print-certs --verbose --" | Out-File $ev -Append -Encoding UTF8
  & "$($bt.FullName)\apksigner.bat" verify --print-certs --verbose $p 2>&1 | Out-File $ev -Append -Encoding UTF8

  "-- does the APK carry the f989907 fix? (grep in assets) --" | Out-File $ev -Append -Encoding UTF8
  $tmp = "$env:TEMP\r2apk_$tag"
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Path $tmp -Force | Out-Null
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($p)
    $names = $zip.Entries | ForEach-Object { $_.FullName }
    $names | Select-String 'oc-host.js|index.html' | ForEach-Object { "  entry: $_" } | Out-File $ev -Append -Encoding UTF8
    $e = $zip.Entries | Where-Object { $_.FullName -eq 'assets/www/oc-host.js' }
    if ($e) {
      $sr = New-Object System.IO.StreamReader($e.Open())
      $txt = $sr.ReadToEnd(); $sr.Close()
      "  oc-host.js bytes = $($txt.Length)" | Out-File $ev -Append -Encoding UTF8
      $hits = $regex = $txt -split "`n" | Select-String 'notificationclick|routeToSession|oc_session_route|sessionOpen|focusSession|768'
      "  fix-marker hits: $($hits.Count)" | Out-File $ev -Append -Encoding UTF8
      $hits | Select-Object -First 12 | ForEach-Object { "    $($_.Line.Trim())" } | Out-File $ev -Append -Encoding UTF8
    } else { "  oc-host.js NOT FOUND in $tag" | Out-File $ev -Append -Encoding UTF8 }
    $zip.Dispose()
  } catch { "  zip read failed: $_" | Out-File $ev -Append -Encoding UTF8 }
}
Write-Host "=== EVIDENCE DONE ==="
Get-Content $ev | Select-Object -First 40
