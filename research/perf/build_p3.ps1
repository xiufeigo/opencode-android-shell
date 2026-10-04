# build_p3.ps1 — 抢锁 → 构建 :webapp → 装机 → 释放
$ErrorActionPreference = 'Continue'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
Set-Location $root
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# --- 抢锁：等锁消失 ---
for ($i = 0; $i -lt 180; $i++) {
  if (-not (Test-Path "$root\.agent-gradle-lock")) { break }
  Start-Sleep -Seconds 5
}
if (Test-Path "$root\.agent-gradle-lock") { Write-Output "LOCK-BUSY timeout"; exit 1 }
Set-Content -Path "$root\.agent-gradle-lock" -Value "P3 frametime/gzip perf build`nagent=mavis`n$(Get-Date -Format s)"
Write-Output "LOCK-TAKEN"

try {
  Write-Output "=== gradle assembleDebug ==="
  & "$root\gradlew.bat" :webapp:assembleDebug --console=plain 2>&1 | Select-Object -Last 25
  Write-Output "GRADLE_EXIT=$LASTEXITCODE"

  Write-Output "=== install ==="
  & $adb -s emulator-5636 install -r -t "webapp\build\outputs\apk\debug\webapp-debug.apk" 2>&1 | Select-Object -Last 5
  Write-Output "INSTALL_EXIT=$LASTEXITCODE"
}
finally {
  Remove-Item -Path "$root\.agent-gradle-lock" -Force -ErrorAction SilentlyContinue
  Write-Output "LOCK-RELEASED exists=$(Test-Path "$root\.agent-gradle-lock")"
}
