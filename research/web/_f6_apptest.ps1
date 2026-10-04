$ErrorActionPreference = 'Stop'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
$lock = Join-Path $root ".agent-gradle-lock"
$mine = "f6-apptest"
$waited = 0
while (Test-Path $lock) {
  if ($waited -gt 900) { Write-Output "ABORT: lock held"; exit 2 }
  Start-Sleep -Seconds 15; $waited += 15
}
Set-Content -Path $lock -Value "$mine pid=$PID at=$(Get-Date -Format o)" -Encoding utf8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$out = Join-Path $root "research\web\_f6_apptest.log"
try {
  Push-Location $root
  & ".\gradlew.bat" :app:testDebugUnitTest --console=plain --no-daemon 2>&1 |
    Tee-Object -FilePath $out
  $code = $LASTEXITCODE
  Pop-Location
  Write-Output "APPTEST_EXIT=$code"
  exit $code
} finally {
  Pop-Location -ErrorAction SilentlyContinue
  if (Test-Path $lock) { Remove-Item $lock -Force }
  Write-Output "LOCK_RELEASED"
}
