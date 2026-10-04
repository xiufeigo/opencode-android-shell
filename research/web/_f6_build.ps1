$ErrorActionPreference = 'Stop'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
$lock = Join-Path $root ".agent-gradle-lock"
$mine = "f6-probe-fix"

# 1) wait for the lock to disappear (other agents may hold it)
$waited = 0
while (Test-Path $lock) {
  if ($waited -gt 900) { Write-Output "ABORT: gradle lock held >15min"; exit 2 }
  Write-Output "waiting for gradle lock... (${waited}s)"
  Start-Sleep -Seconds 15
  $waited += 15
}
Write-Output "LOCK_FREE after ${waited}s"

# 2) take the lock
Set-Content -Path $lock -Value "$mine pid=$PID at=$(Get-Date -Format o)" -Encoding utf8
Write-Output "LOCK_ACQUIRED $mine"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$out = Join-Path $root "research\web\_f6_build.log"
try {
  Push-Location $root
  & ".\gradlew.bat" :webapp:assembleDebug :webapp:assembleRelease --console=plain --no-daemon 2>&1 |
    Tee-Object -FilePath $out
  $code = $LASTEXITCODE
  Pop-Location
  Write-Output "GRADLE_EXIT=$code"
  exit $code
} finally {
  Pop-Location -ErrorAction SilentlyContinue
  if (Test-Path $lock) { Remove-Item $lock -Force }
  Write-Output "LOCK_RELEASED"
}
