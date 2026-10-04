$ErrorActionPreference = 'Continue'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
Set-Location $root
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$log = "$root\research\web\_r2_build.log"

# ---- lock protocol: wait for existing lock to disappear, then take it ----
for ($i=0; $i -lt 240; $i++) {
  if (-not (Test-Path "$root\.agent-gradle-lock")) { break }
  Write-Host "waiting for gradle lock (poll $i)"
  Start-Sleep -Seconds 5
}
Set-Content -Path "$root\.agent-gradle-lock" -Value "mavis R2 DELIVER_REBUILD serial=emulator-5900 avd=OC_Deliver_R2 started=$(Get-Date -Format s)" -Encoding UTF8
Write-Host "=== LOCK TAKEN ==="

"=== R2 BUILD START $(Get-Date -Format s) ===" | Out-File $log -Encoding UTF8

"--- assembleDebug ---" | Out-File $log -Append -Encoding UTF8
& "$root\gradlew.bat" :webapp:assembleDebug --console=plain 2>&1 | Tee-Object -FilePath $log -Append | Select-Object -Last 25
Write-Host "DEBUG_EXIT=$LASTEXITCODE"
"=== DEBUG_EXIT=$LASTEXITCODE ===" | Out-File $log -Append -Encoding UTF8

"--- assembleRelease (non-R8, existing config) ---" | Out-File $log -Append -Encoding UTF8
& "$root\gradlew.bat" :webapp:assembleRelease --console=plain 2>&1 | Tee-Object -FilePath $log -Append | Select-Object -Last 25
Write-Host "RELEASE_EXIT=$LASTEXITCODE"
"=== RELEASE_EXIT=$LASTEXITCODE ===" | Out-File $log -Append -Encoding UTF8

"=== OUTPUTS ===" | Out-File $log -Append -Encoding UTF8
Get-ChildItem "$root\webapp\build\outputs\apk" -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
  ForEach-Object { "$($_.FullName)`t$($_.Length)`t$((Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower())" } |
  Out-File $log -Append -Encoding UTF8

Remove-Item "$root\.agent-gradle-lock" -ErrorAction SilentlyContinue
Write-Host "=== LOCK RELEASED ==="
Write-Host "=== R2 BUILD DONE $(Get-Date -Format s) ==="
