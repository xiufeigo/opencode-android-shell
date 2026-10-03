. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Stop'
Write-Host '--- is the fix actually ON the device? grep dex for SshFormReach ---'
$apk = (Q shell "pm path com.xiufeigo.opencodeshell" | Out-String).Trim() -replace '^package:',''
Write-Host "APK=$apk"
Write-Host '--- lastUpdateTime / versionName ---'
(Q shell "dumpsys package com.xiufeigo.opencodeshell" | Out-String) -split "`n" | Select-String -Pattern 'versionName|lastUpdateTime|firstInstallTime' | ForEach-Object { Write-Host "  $($_.ToString().Trim())" }
Write-Host '--- classes dex string probe (SshFormReach / SshFormField / revealRefusedField) ---'
$tmp = Join-Path $env:TEMP 'oc_probe'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Q pull $apk (Join-Path $tmp 'oc.apk') | Out-Null
$apkLocal = Join-Path $tmp 'oc.apk'
Write-Host "apk bytes=$((Get-Item $apkLocal).Length)"
$found = $false
foreach($pat in @('SshFormReach','SshFormField','sshFormErrorField','sshFormDrawnFields','SshFormReachabilityV98Test')){
  $hit = Q shell "true" 2>$null | Out-Null
}
# string scan of the apk binary (dex strings are stored uncompressed in the zip)
$bytes = [IO.File]::ReadAllBytes($apkLocal)
$ascii = [Text.Encoding]::ASCII.GetString($bytes)
foreach($pat in @('SshFormReach','SshFormField','sshFormErrorField','sshFormDrawnFields','sshFormFieldsAllReachable','SshFormReachabilityV98Test','revealRefusedField')){
  $has = $ascii.Contains($pat)
  Write-Host ("  {0,-32} {1}" -f $pat, $(if($has){'FOUND-IN-APK'}else{'absent'}))
  if($has){ $found = $true }
}
Write-Host "FIX_IN_APK=$found"

Write-Host '--- gesture test: slow 100px drag, 3 variants ---'
$null = Uidump 'B0_pre'
$n = NodesNow 'B0p'
($n | Select-String -Pattern 'Host or SSH command|EditText' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "  PRE $_" }
$variants = @(
  @{n='v1_100px_300ms'; cmd='input swipe 1018 1650 1018 1550 300'},
  @{n='v2_100px_80ms';  cmd='input swipe 1018 1650 1018 1550 80'},
  @{n='v3_200px_600ms'; cmd='input swipe 1018 1680 1018 1480 600'}
)
foreach($v in $variants){
  Q shell $v.cmd | Out-Null
  Start-Sleep -Milliseconds 800
  $n = NodesNow "B0_$($v.n)"
  $h = $n | Select-String -Pattern 'Host or SSH command' | Select-Object -First 1
  $et = (Fields $n).Count
  Write-Host "  $($v.n): HostLabel=$($h.Line) EditTexts=$et"
}
ShotN 'B0_gesture'
Write-Host 'DONE-B0'
