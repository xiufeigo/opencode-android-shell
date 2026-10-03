$ErrorActionPreference='Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg="com.xiufeigo.opencodeshell"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
Set-Location $dir
function F(){ (& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString() }
# Gate: every tap only when focus is ours
function Tap($x,$y,$label){
  $f=F()
  if($f -notmatch "opencodeshell"){
    & $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 1500
    $f=F()
    if($f -notmatch "opencodeshell"){ Write-Output "SKIP_TAP $label (foreign)"; return $false }
  }
  & $adb -s emulator-5554 shell input tap $x $y
  Write-Output "TAP_OK $label ($x,$y)"
  return $true
}
# Read text/desc values from a dump without touching it
function Has($file,$key){ $c=(Select-String -Path "$dir\$file" -Pattern $key -AllMatches | ForEach-Object { $_.Matches.Count } | Measure-Object -Sum).Sum; return [int]$c }

Write-Output "=== STATE READ ==="
Write-Output (F())

# 1) read current screen
powershell -NoProfile -ExecutionPolicy Bypass -File .\_shot.ps1 -Name d_05_state -Tries 6
Write-Output "--- composer zone ---"
powershell -NoProfile -ExecutionPolicy Bypass -File .\_zone.ps1 -File d_05_state.xml -MinY 1380
Write-Output ("Stop={0} Send={1} Queue={2} Steer={3} Remove={4}" -f (Has d_05_state.xml 'Stop'),(Has d_05_state.xml 'Send'),(Has d_05_state.xml 'Queue'),(Has d_05_state.xml 'Steer'),(Has d_05_state.xml 'Remove'))
